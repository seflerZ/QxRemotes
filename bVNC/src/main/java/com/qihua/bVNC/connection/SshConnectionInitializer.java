package com.qihua.bVNC.connection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.DoubleBufferBitmapData;
import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.ssh.SshTerminalConnection;
import com.qihua.bVNC.communicator.SshCommunicator;
import com.qihua.bVNC.input.RemoteSshKeyboard;
import com.qihua.bVNC.input.RemoteSshPointer;
import com.qihua.bVNC.ssh.SshShellChannel;
import com.qihua.bVNC.ssh.SshTerminalRenderer;
import com.qihua.bVNC.ssh.SshTerminalScaling;
import com.qihua.bVNC.ssh.libvterm.SshTermStateMachine;
import com.trilead.ssh2.Session;
import com.undatech.opaque.Connection;
import com.undatech.opaque.DrawTask;

import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH lifecycle owner. Phase 2: trilead Session on background thread →
 * {@link SshShellChannel} pipe pumps → {@link SshTerminalRenderer} writes
 * into mbitmap. Same shape as RDP/NVStream (worker thread + bitmap
 * callback); the difference is the worker outputs a char grid, not pixels.
 */
public class SshConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "SshConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    /** TermSession UpdateCallback — fires on every screen mutation. */
    private final Runnable sshUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!canvas.isRunning || canvas.bitmapData == null || canvas.rfbconn == null) {
                return;
            }
            // Snap scrollback to live bottom on new output.
            if (renderer != null && renderer.getTermSession() != null
                    && renderer.getTermSession().getScrollOffset() > 0) {
                renderer.getTermSession().scrollToBottom();
            }

            // Refresh IME push-up only when cursor Y or fb size changed —
            // unconditional repan flickers the +panY/-panY undo/apply pair.
            if (canvas.activity != null
                    && canvas.activity.isSoftKeyboardUp()) {
                int cursorY = renderer != null ? renderer.getCursorPixelY() : 0;
                int fbW = canvas.rfbconn != null ? canvas.rfbconn.framebufferWidth() : 0;
                int fbH = canvas.rfbconn != null ? canvas.rfbconn.framebufferHeight() : 0;
                if (cursorY != lastPannedCursorY || fbW != lastPannedFbW || fbH != lastPannedFbH) {
                    lastPannedCursorY = cursorY;
                    lastPannedFbW = fbW;
                    lastPannedFbH = fbH;
                    // Post to main — repanCanvas4SSH mutates cursor/fb state
                    // the IME listener also writes; direct call races the listener.
                    if (canvas.handler != null) {
                        canvas.handler.post(() -> {
                            if (canvas.activity != null) {
                                canvas.activity.repanCanvas4SSH(true);
                            }
                        });
                    }
                }
            }

            paintAndRedraw();
        }
    };

    private RemoteCanvas canvas;
    private SshTerminalRenderer renderer;
    /** 1:1 scaler so SSH reuses the RDP/VNC pan pipeline. */
    private SshTerminalScaling sshScaler;

    /** 5 FPS heartbeat — catches shell bursts that UpdateCallback misses. */
    private static final long HEARTBEAT_INTERVAL_MS = 200L;
    /** Shell quiet time that means "the prompt has settled, type now". */
    private static final long STARTUP_CMD_IDLE_MS = 400L;
    /** How often we re-check whether the shell is ready. */
    private static final long STARTUP_CMD_POLL_MS = 100L;
    /** Give up waiting for the shell after this long and type anyway. */
    private static final long STARTUP_CMD_TIMEOUT_MS = 8000L;

    /** How many times we try to rebuild the SSH session before giving up. */
    private static final int RECONNECT_MAX_ATTEMPTS = 8;
    /** Delay before each reconnect attempt (ms). The first attempt is immediate. */
    private static final long[] RECONNECT_BACKOFF_MS = {0, 1000, 2000, 4000, 8000, 15000, 15000, 15000};
    private volatile long lastPaintAt = 0;
    /** Minimum gap between paints (~60 FPS). Without it a TUI's end-of-frame
     *  static re-dump paints once per 4K read chunk and visibly scrolls. */
    private static final long MIN_PAINT_INTERVAL_MS = 16L;
    /** Cap on how long a DEC 2026 synchronized update may hold off a paint. */
    private static final long SYNC_OUTPUT_MAX_DEFER_MS = 150L;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                paintAndRedraw();
            } catch (Throwable t) {
                Log.w(TAG, "heartbeat paint failed", t);
            } finally {
                if (canvas != null && canvas.handler != null) {
                    canvas.handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        }
    };
    private boolean heartbeatStarted;

    /** Real SSH terminal connection; built in initialize(), used on connectThread. */
    private SshTerminalConnection sshTerminal;
    private String sshHost;
    private int sshPort;
    private String sshUser;
    private String sshPassword;
    /** Pubkey auth fields — base64 PKCS#8, possibly passphrase-encrypted. */
    private String sshPrivKey;
    private String sshPubKey;
    private String sshPassPhrase;
    private boolean useSshPubKey;
    private String savedHostKey;
    /** Typed into the shell once it is ready. Empty = disabled. */
    private String sshStartupCommand;
    /** Bridge between trilead Session and TermSession pipes; attach()ed in doConnect(). */
    private SshShellChannel channel;
    private Thread connectThread;
    private volatile boolean connectStarted;
    /** Set to true when doConnect() finishes successfully. */
    private volatile boolean shellReady;
    /** The user closed the session — do not reconnect. */
    private volatile boolean userDisconnected = false;
    /** A reconnect is in progress (guards duplicate attempts). */
    private volatile boolean reconnecting = false;
    /** Number of reconnect attempts done since the link died. */
    private int reconnectAttempt = 0;
    /** Guards teardown / rebuild against the in-flight connect thread. */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /** Off-main paint thread — renderInto blocks 10-30 ms, would flicker UI otherwise. */
    private HandlerThread paintThread;
    private Handler paintHandler;
    /** One outstanding paint request; prevents scroll storms from starving it. */
    private final java.util.concurrent.atomic.AtomicBoolean paintScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private float density;
    /** Cursor/fb snapshot from last IME push-up — skips redundant repans. */
    private int lastPannedCursorY = Integer.MIN_VALUE;
    private int lastPannedFbW = -1;
    private int lastPannedFbH = -1;

    public SshConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.SSH;
    }

    /** Exposed for SSH selection-menu Paste (writes to state machine directly). */
    public SshTerminalRenderer getSshRenderer() {
        return renderer;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_SSH;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        this.canvas = canvas;
        this.density = ctx.getResources().getDisplayMetrics().density;
        Log.i(TAG, "initialize: Phase 2 — TermSession + SshShellChannel + trilead.");

        int fbW = computeFbW();
        int fbH = computeFbH();
        Log.i(TAG, "SSH framebuffer size = " + fbW + " x " + fbH
                + " (displayRect " + canvas.displayRect.width() + "x" + canvas.displayRect.height()
                + ", factor " + Constants.SSH_SMART_RESOLUTION_FACTOR + ")");

        // Pull SSH terminal params (uses VNC-style fields, not the SshXxx tunnel fields).
        sshHost = conn.getAddress();
        sshPort = conn.getPort() == 0 ? 22 : conn.getPort();
        sshUser = conn.getUserName();
        sshPassword = conn.getPassword();
        // Pubkey fields read here so connect thread just picks an auth path.
        sshPrivKey = conn.getSshPrivKey() == null ? "" : conn.getSshPrivKey();
        sshPubKey = conn.getSshPubKey() == null ? "" : conn.getSshPubKey();
        sshPassPhrase = conn.getSshPassPhrase() == null ? "" : conn.getSshPassPhrase();
        useSshPubKey = conn.getUseSshPubKey();
        savedHostKey = conn.getSshHostKey() == null ? "" : conn.getSshHostKey();
        // Startup command reuses the bean's sshRemoteCommand slot, which the
        // VNC-over-SSH tunnel path never reads.
        sshStartupCommand = conn.getSshRemoteCommand() == null ? "" : conn.getSshRemoteCommand();
        sshTerminal = new SshTerminalConnection(sshHost, sshPort, savedHostKey);

        SshCommunicator sshComm = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
        // SshCommunicator.close() must tear down the SSH terminal too.
        sshComm.setSshTerminalConnection(sshTerminal);
        canvas.rfbconn = sshComm;

        canvas.pointer = new RemoteSshPointer(canvas.rfbconn, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteSshKeyboard(canvas.rfbconn, ctx, canvas.handler, App.debugLog);

        channel = new SshShellChannel();
        // When the remote shell exits (exit / Ctrl+D, SSH session ends),
        // navigate back to the main connection list instead of leaving
        // the user staring at a dead canvas. A *transport* loss (network)
        // is different — that one reconnects (see onTransportLost).
        wireChannel(channel);
        // trilead's connection monitor + the keepalive ping both land here.
        sshTerminal.setOnTransportLost(this::onTransportLost);
        renderer = new SshTerminalRenderer(density, channel, ctx);
        // Forward grid-size changes to remote PTY (resizePTY is no-op pre-shell).
        renderer.setGridSizeListener((cols, rows) -> {
            if (sshTerminal != null) sshTerminal.resizePty(cols, rows);
        });
        // Keyboard wired in openRenderer() — stateMachine is null until then.
        sshScaler = new SshTerminalScaling();
        sshScaler.attachTo(canvas);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Phase 2 — opening terminal + connecting SSH.");
        canvas.waitUntilInflated();
        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        openRenderer();
        // applyTheme again — stateMachine was null in initialize(), so first paint skipped it.
        if (renderer != null) {
            renderer.applyTheme();
        }
        ((RemoteSshKeyboard) canvas.keyboard).setTermSession(renderer.getTermSession());
        canvas.onConnectionSuccess();

        // Spawn SSH-Connect — user sees blank grid during 1-10s auth, then UpdateCallback paints.
        lifecycleLock.lock();
        try {
            if (connectStarted) {
                Log.w(TAG, "start: connect already started, ignoring duplicate");
                return;
            }
            connectStarted = true;
        } finally {
            lifecycleLock.unlock();
        }

        connectThread = new Thread(this::doConnect, "SSH-Connect");
        connectThread.setDaemon(false);
        connectThread.start();

        startHeartbeat();
    }

    private void startHeartbeat() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;
        if (canvas != null && canvas.handler != null) {
            canvas.handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
            Log.i(TAG, "startHeartbeat: 5 FPS heartbeat started");
        }
    }

    private void stopHeartbeat() {
        heartbeatStarted = false;
        if (canvas != null && canvas.handler != null) {
            canvas.handler.removeCallbacks(heartbeatRunnable);
        }
    }

    /** Background worker — drives the trilead handshake off the UI thread. */
    private void doConnect() {
        try {
            connectTransport(channel);
            shellReady = true;
            Log.i(TAG, "doConnect: shell channel attached, terminal should start showing output");
            sendStartupCommand();
            // Force one paint now so the (still empty, blue) grid gives
            // way to whatever the shell has already emitted since attach.
            postPaint();
        } catch (Throwable t) {
            Log.e(TAG, "doConnect: failed", t);
            if (canvas != null) {
                try {
                    canvas.handleUncaughtException(t);
                } catch (Throwable ignored) {
                    // canvas may already be tearing down; swallow.
                }
            }
        }
    }

    /**
     * Authenticate and open a shell on {@code ch}. Blocking — call from a
     * background thread. Shared by the initial connect and by reconnects.
     */
    private void connectTransport(SshShellChannel ch) throws Exception {
        Log.i(TAG, "connectTransport: connecting " + sshUser + "@" + sshHost + ":" + sshPort
                + " useSshPubKey=" + useSshPubKey + " hasPrivKey=" + !sshPrivKey.isEmpty());
        boolean authOk;
        // Pubkey first when present — passphrase-less key skips password round-trip.
        boolean canPubkey = useSshPubKey && !sshPrivKey.isEmpty();
        if (canPubkey) {
            try {
                authOk = sshTerminal.connectWithPubkey(sshUser, sshPrivKey, sshPubKey, sshPassPhrase);
            } catch (Exception pubkeyErr) {
                // Surface the pubkey error so user can fix passphrase in ConfigSSH.
                Log.w(TAG, "connectTransport: pubkey auth failed (" + pubkeyErr.getMessage()
                        + "), falling back to password");
                authOk = sshTerminal.connect(sshUser, sshPassword);
            }
        } else {
            authOk = sshTerminal.connect(sshUser, sshPassword);
        }
        if (!authOk) {
            throw new Exception("SSH authentication failed for " + sshUser + "@" + sshHost
                    + (canPubkey ? " (pubkey + password both rejected)" : " (password rejected)"));
        }
        Log.i(TAG, "connectTransport: auth OK, opening shell with xterm-256color PTY");
        // Open PTY at renderer's real cols/rows — 80x24 leaves TUIs stuck in the upper-left quadrant.
        int cols = 80, rows = 24;
        if (renderer != null) {
            int rc = renderer.getCurrentCols();
            int rr = renderer.getCurrentRows();
            if (rc > 0) cols = rc;
            if (rr > 0) rows = rr;
        }
        Log.i(TAG, "connectTransport: opening PTY at " + cols + "x" + rows);
        Session sshSession = sshTerminal.openShell(cols, rows);
        // Pumps forward bytes; UpdateCallback paints.
        ch.attach(sshSession);
    }

    /** Type startup command into shell. CR not LF (matches Enter key).
     *  Posted to main: writing from SSH-Connect thread kills writePump with "Write end dead".
     *
     *  <p>Not sent right away: the remote shell needs a moment to start
     *  (rc files, readline setup). Input typed before the shell is ready
     *  gets discarded - bash/readline call tcsetattr(TCSAFLUSH), which
     *  throws away whatever was typed too early. So wait until the shell
     *  has printed something and then gone quiet (its prompt settled),
     *  and only then type the command. */
    private void sendStartupCommand() {
        if (sshStartupCommand == null || renderer == null || canvas == null) return;
        final String cmd = sshStartupCommand.trim();
        if (cmd.isEmpty()) return;
        Log.i(TAG, "doConnect: waiting for the shell to be ready before sending startup command");
        final long deadline = android.os.SystemClock.uptimeMillis() + STARTUP_CMD_TIMEOUT_MS;
        canvas.handler.post(new Runnable() {
            @Override
            public void run() {
                if (renderer == null || canvas == null || canvas.handler == null) {
                    return;
                }
                boolean gotOutput = renderer.getBytesRead() > 0;
                if (gotOutput && renderer.isShellIdle(STARTUP_CMD_IDLE_MS)) {
                    Log.i(TAG, "shell is up and idle, sending startup command");
                    renderer.writeString(cmd + "\r");
                    return;
                }
                if (android.os.SystemClock.uptimeMillis() >= deadline) {
                    Log.w(TAG, "shell not idle after " + STARTUP_CMD_TIMEOUT_MS
                            + "ms, sending startup command anyway");
                    renderer.writeString(cmd + "\r");
                    return;
                }
                canvas.handler.postDelayed(this, STARTUP_CMD_POLL_MS);
            }
        });
    }

    /** Wire the exit/lost callbacks of a channel. */
    private void wireChannel(SshShellChannel ch) {
        // The remote shell exited on its own (user typed exit / Ctrl+D).
        ch.setOnDisconnect(() -> {
            Log.i(TAG, "shell exited normally, closing the session");
            teardown(canvas);
            if (canvas != null && canvas.handler != null && canvas.activity != null) {
                canvas.handler.post(canvas.activity::disconnectAndClose);
            }
        });
        // The transport died (network loss) — reconnect instead of exiting.
        ch.setOnTransportLost(this::onTransportLost);
    }

    /**
     * The SSH transport died (network loss, NAT timeout, server reboot).
     * Rebuild the session instead of dropping the user back to the
     * connection list.
     */
    private void onTransportLost() {
        if (userDisconnected || reconnecting) {
            return;
        }
        Log.w(TAG, "onTransportLost: SSH transport lost, reconnecting");
        reconnecting = true;
        reconnectAttempt = 0;
        if (canvas != null && canvas.handler != null) {
            canvas.handler.post(() -> {
                toast(R.string.ssh_reconnect_lost);
                scheduleReconnect();
            });
        }
    }

    /** Schedule the next reconnect attempt. Main thread. */
    private void scheduleReconnect() {
        if (userDisconnected) {
            return;
        }
        if (reconnectAttempt >= RECONNECT_MAX_ATTEMPTS) {
            Log.w(TAG, "scheduleReconnect: giving up after " + reconnectAttempt + " attempts");
            reconnecting = false;
            toast(R.string.ssh_reconnect_failed);
            teardown(canvas);
            if (canvas != null && canvas.handler != null && canvas.activity != null) {
                canvas.handler.post(canvas.activity::disconnectAndClose);
            }
            return;
        }
        long delay = RECONNECT_BACKOFF_MS[Math.min(reconnectAttempt, RECONNECT_BACKOFF_MS.length - 1)];
        reconnectAttempt++;
        Log.i(TAG, "scheduleReconnect: attempt " + reconnectAttempt + " in " + delay + "ms");
        if (canvas != null && canvas.handler != null) {
            canvas.handler.postDelayed(this::attemptReconnect, delay);
        }
    }

    /** One reconnect attempt — the network part runs on a background thread. */
    private void attemptReconnect() {
        if (userDisconnected) {
            return;
        }
        Thread t = new Thread(() -> {
            SshShellChannel newChannel = null;
            try {
                // Drop the dead transport, keep the terminal (and its scrollback).
                closeTransport();
                newChannel = new SshShellChannel();
                wireChannel(newChannel);
                connectTransport(newChannel);
            } catch (Throwable e) {
                Log.w(TAG, "attemptReconnect: failed: " + e);
                try {
                    if (newChannel != null) {
                        newChannel.close();
                    }
                } catch (Throwable ignored) {
                }
                if (canvas != null && canvas.handler != null) {
                    canvas.handler.post(this::scheduleReconnect);
                }
                return;
            }
            channel = newChannel;
            shellReady = true;
            reconnectAttempt = 0;
            final SshShellChannel ch = newChannel;
            if (canvas != null && canvas.handler != null) {
                canvas.handler.post(() -> {
                    reconnecting = false;
                    if (renderer != null) {
                        renderer.rebind(ch);
                    }
                    toast(R.string.ssh_reconnect_ok);
                    // The remote shell is a new one — the startup command has to run again.
                    sendStartupCommand();
                    postPaint();
                });
            }
        }, "SSH-Reconnect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Close the SSH transport (channel + session) but keep the terminal
     * grid and the canvas alive.
     */
    private void closeTransport() {
        if (renderer != null) {
            // Typing into a closed pipe would throw inside libvterm.
            renderer.suspendOutput();
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "closeTransport: channel.close failed", t);
        }
        try {
            if (sshTerminal != null) {
                sshTerminal.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "closeTransport: sshTerminal.close failed", t);
        }
    }

    private void toast(int resId) {
        try {
            if (canvas != null && canvas.getContext() != null) {
                Toast.makeText(canvas.getContext(), resId, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void postPaint() {
        if (canvas == null || Looper.myLooper() != Looper.getMainLooper()) {
            // Already on main? Just paint. Otherwise hop.
            if (canvas != null) {
                canvas.handler.post(this::paintAndRedraw);
            }
            return;
        }
        paintAndRedraw();
    }

    /** Tear down the renderer, channel, and SSH connection. */
    public void teardown(RemoteCanvas canvas) {
        lifecycleLock.lock();
        try {
            Log.i(TAG, "teardown: closing SSH");
            // Whatever brought us here, the session is over — no reconnects.
            userDisconnected = true;
            stopHeartbeat();
            stopPaintThread();
            if (connectThread != null && connectThread.isAlive()) {
                connectThread.interrupt();
            }
            // Channel BEFORE renderer — TermSession reader wakes from EOF.
            try {
                if (channel != null) channel.close();
            } catch (Throwable t) {
                Log.w(TAG, "teardown: channel.close failed", t);
            }
            closeRenderer();
            try {
                if (sshTerminal != null) sshTerminal.close();
            } catch (Throwable t) {
                Log.w(TAG, "teardown: sshTerminal.close failed", t);
            }
            connectStarted = false;
            shellReady = false;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** Cursor Y in mbitmap pixels — feeds the IME push-up pan formula. */
    public int getCursorPixelY() {
        return renderer != null ? renderer.getCursorPixelY() : 0;
    }

    /** Cell height in pixels — the SSH pan leaves half a cell of slack. */
    public int getCellHeight() {
        return renderer != null ? renderer.getCellHeight() : 1;
    }

    /** Re-apply AppCompat day/night palette + reseed mbitmap. Safe pre-start. */
    public void applyTheme() {
        if (canvas != null && ctx != null) {
            canvas.setSurfaceBackgroundColor(
                    ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bg, ctx.getTheme()));
        }
        if (renderer == null) return;
        renderer.applyTheme();
        // Reseed so user sees the flip immediately, not on the next shell byte.
        if (canvas != null && canvas.bitmapData instanceof DoubleBufferBitmapData) {
            DoubleBufferBitmapData dbb = (DoubleBufferBitmapData) canvas.bitmapData;
            renderer.seedBackground(dbb);
            canvas.reDraw(0, 0, dbb.getWidth(), dbb.getHeight());
        }
    }

    /** IME visibility changed — RDP pan formula in RemoteCanvasActivity handles it. */
    @Override
    public void onSoftKeyboardChanged(boolean isShow, int availableHeight) {
        // Intentionally empty.
    }

    @Override
    public void onDisplayRectChanged(Display display) {
        if (canvas.rfbconn == null) {
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        Rect newRect = new Rect();
        display.getRectSize(newRect);

        Rect oldRect = canvas.getDisplayRect();

        if (oldRect.equals(newRect)) {
            return;
        }

        canvas.setDisplayRect(newRect);
        canvas.setDisplayDensity(metrics.density);

        Log.i(TAG, "displayRect changed "
                + oldRect.width() + "x" + oldRect.height()
                + " -> " + newRect.width() + "x" + newRect.height()
                + ", resizing SSH framebuffer (keeping SSH session alive)");

        resizeSSHFramebuffer();
    }

    /** Open TermSession at current mbitmap size — this IS the first-frame trigger. */
    private void openRenderer() {
        if (!(canvas.bitmapData instanceof DoubleBufferBitmapData)) {
            Log.w(TAG, "openRenderer: bitmapData is not DoubleBufferBitmapData");
            return;
        }
        DoubleBufferBitmapData dbb = (DoubleBufferBitmapData) canvas.bitmapData;

        int w = dbb.getWidth();
        int h = dbb.getHeight();
        renderer.seedBackground(dbb);
        renderer.open(w, h, sshUpdateRunnable);
        // setScrollback survives fold/unfold (idempotent across re-opens).
        if (canvas.pointer instanceof RemoteSshPointer) {
            ((RemoteSshPointer) canvas.pointer)
                    .setScrollback(renderer.getTermSession(), this::postPaintToBackground);
            ((RemoteSshPointer) canvas.pointer).setRenderer(renderer);
        }
        ensurePaintThread();
        postPaintToBackground();
    }

    private void ensurePaintThread() {
        if (paintThread != null && paintThread.isAlive()) return;
        paintThread = new HandlerThread("SSH-Paint", android.os.Process.THREAD_PRIORITY_DISPLAY);
        paintThread.start();
        paintHandler = new Handler(paintThread.getLooper());
        paintScheduled.set(false);
    }

    private void stopPaintThread() {
        if (paintHandler != null) {
            paintHandler.removeCallbacksAndMessages(null);
        }
        if (paintThread != null) {
            paintThread.quitSafely();
            paintThread = null;
        }
        paintHandler = null;
    }

    /** Off-main paint — rapid keystrokes coalesce to the latest state. */
    private void postPaintToBackground() {
        if (paintHandler == null) return;
        // Never cancel an already-scheduled paint. The old removeCallbacks +
        // repost on every request let a fast scroll stream starve the paint
        // thread down to ~10-20 fps: each new touch event cancelled the
        // pending frame before the looper could run it. The runnable always
        // renders the latest state, so one outstanding request is enough.
        if (!paintScheduled.compareAndSet(false, true)) return;
        long delay = MIN_PAINT_INTERVAL_MS - (System.currentTimeMillis() - lastPaintAt);
        if (delay > 0) {
            paintHandler.postDelayed(paintRunnable, delay);
        } else {
            paintHandler.post(paintRunnable);
        }
    }

    /** Heartbeat entry point — same paint path, off the main thread. */
    private void paintAndRedraw() {
        postPaintToBackground();
    }

    private final Runnable paintRunnable = new Runnable() {
        @Override
        public void run() {
            paintScheduled.set(false);
            if (renderer == null) return;
            if (canvas == null) return;
            if (!(canvas.bitmapData instanceof DoubleBufferBitmapData)) return;
            if (canvas.rfbconn == null) return;
            // Defer mid-DEC-2026 paints; the ESU triggers another. Bounded so a dead TUI can't freeze us.
            SshTermStateMachine sm = renderer.getTermSession();
            if (sm != null && sm.isSyncOutput()
                    && System.currentTimeMillis() - lastPaintAt < SYNC_OUTPUT_MAX_DEFER_MS) {
                if (paintHandler != null) {
                    paintScheduled.set(true);
                    paintHandler.postDelayed(this, MIN_PAINT_INTERVAL_MS);
                }
                return;
            }
            lastPaintAt = System.currentTimeMillis();
            try {
                long t0 = System.nanoTime();
                boolean fastPath = renderer.renderInto((DoubleBufferBitmapData) canvas.bitmapData);
                if (App.debugLog) {
                    long paintMs = (System.nanoTime() - t0) / 1_000_000L;
                    Log.d(TAG, "paint took " + paintMs + "ms fast=" + fastPath);
                }
                // reDraw inside try — a teardown race killing the paint thread freezes the terminal.
                canvas.reDraw(new DrawTask(0, 0,
                        canvas.rfbconn.framebufferWidth(),
                        canvas.rfbconn.framebufferHeight(), true));
            } catch (Throwable t) {
                Log.w(TAG, "paint failed", t);
            }
        }
    };

    private void closeRenderer() {
        if (renderer == null) return;
        try {
            renderer.close();
        } catch (Throwable t) {
            Log.w(TAG, "renderer.close failed", t);
        }
        renderer = null;
    }

    private int computeFbW() {
        float w = canvas.displayRect != null ? canvas.displayRect.width() : 0;
        if (w <= 0) {
            w = ctx.getResources().getDisplayMetrics().widthPixels;
        }
        return Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
    }

    private int computeFbH() {
        float h = canvas.displayRect != null ? canvas.displayRect.height() : 0;
        if (h <= 0) {
            h = ctx.getResources().getDisplayMetrics().heightPixels;
        }
        return Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));
    }

    /** Resize-only path: keeps SSH connection + threads alive, swaps mbitmap only. */
    private void resizeSSHFramebuffer() {
        lifecycleLock.lock();
        try {
            int w = canvas.displayRect.width();
            int h = Math.max(1, canvas.displayRect.height());
            int fbW = Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
            int fbH = Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));

            stopPaintThread();

            // New SshCommunicator needed because framebufferWidth/Height are final.
            SshCommunicator sshComm = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
            sshComm.setSshTerminalConnection(sshTerminal);
            canvas.rfbconn = sshComm;

            ((RemoteSshPointer) canvas.pointer).setProtocomm(canvas.rfbconn);
            ((RemoteSshKeyboard) canvas.keyboard).setRfb(canvas.rfbconn);

            // Seed BG so the first paint isn't a black flash from a transparent bitmap.
            canvas.reallocateDrawable(w, h);
            if (renderer != null && canvas.bitmapData instanceof DoubleBufferBitmapData) {
                renderer.requestGridGrow();
                renderer.seedBackground((DoubleBufferBitmapData) canvas.bitmapData);
            }

            ensurePaintThread();
            postPaintToBackground();

            canvas.bitmapData.frameBufferSizeChanged();
            canvas.reDraw(0, 0, w, h);
        } catch (Exception e) {
            Log.e(TAG, "resizeSSHFramebuffer failed", e);
        } finally {
            lifecycleLock.unlock();
        }

        // Re-attach scaler AFTER unlock — resets absoluteYPosition so stale pan doesn't carry over.
        if (sshScaler != null) {
            sshScaler.attachTo(canvas);
        }
    }
}
