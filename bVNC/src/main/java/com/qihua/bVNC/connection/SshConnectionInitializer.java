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

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.DoubleBufferBitmapData;
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
    /** Guards teardown / rebuild against the in-flight connect thread. */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /** Off-main paint thread — renderInto blocks 10-30 ms, would flicker UI otherwise. */
    private HandlerThread paintThread;
    private Handler paintHandler;

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
        // the user staring at a dead canvas.
        channel.setOnDisconnect(() -> {
            teardown(canvas);
            if (canvas != null && canvas.handler != null && canvas.activity != null) {
                canvas.handler.post(canvas.activity::disconnectAndClose);
            }
        });
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
            Log.i(TAG, "doConnect: connecting " + sshUser + "@" + sshHost + ":" + sshPort
                    + " useSshPubKey=" + useSshPubKey + " hasPrivKey=" + !sshPrivKey.isEmpty());
            boolean authOk;
            // Pubkey first when present — passphrase-less key skips password round-trip.
            boolean canPubkey = useSshPubKey && !sshPrivKey.isEmpty();
            if (canPubkey) {
                try {
                    authOk = sshTerminal.connectWithPubkey(sshUser, sshPrivKey, sshPubKey, sshPassPhrase);
                } catch (Exception pubkeyErr) {
                    // Surface the pubkey error so user can fix passphrase in ConfigSSH.
                    Log.w(TAG, "doConnect: pubkey auth failed (" + pubkeyErr.getMessage()
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
            Log.i(TAG, "doConnect: auth OK, opening shell with xterm-256color PTY");
            // Open PTY at renderer's real cols/rows — 80x24 leaves TUIs stuck in the upper-left quadrant.
            int cols = 80, rows = 24;
            if (renderer != null) {
                int rc = renderer.getCurrentCols();
                int rr = renderer.getCurrentRows();
                if (rc > 0) cols = rc;
                if (rr > 0) rows = rr;
            }
            Log.i(TAG, "doConnect: opening PTY at " + cols + "x" + rows);
            Session sshSession = sshTerminal.openShell(cols, rows);
            // Pumps forward bytes; UpdateCallback paints.
            channel.attach(sshSession);
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

    /** Type startup command into shell. CR not LF (matches Enter key).
     *  Posted to main: writing from SSH-Connect thread kills writePump with "Write end dead". */
    private void sendStartupCommand() {
        if (sshStartupCommand == null || renderer == null || canvas == null) return;
        final String cmd = sshStartupCommand.trim();
        if (cmd.isEmpty()) return;
        Log.i(TAG, "doConnect: sending startup command");
        canvas.handler.post(() -> {
            if (renderer != null) renderer.writeString(cmd + "\r");
        });
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
        paintThread = new HandlerThread("SSH-Paint");
        paintThread.start();
        paintHandler = new Handler(paintThread.getLooper());
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
        // Drop any pending paint so the latest state is the only one rendered.
        paintHandler.removeCallbacks(paintRunnable);
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
            if (renderer == null) return;
            if (canvas == null) return;
            if (!(canvas.bitmapData instanceof DoubleBufferBitmapData)) return;
            if (canvas.rfbconn == null) return;
            // Defer mid-DEC-2026 paints; the ESU triggers another. Bounded so a dead TUI can't freeze us.
            SshTermStateMachine sm = renderer.getTermSession();
            if (sm != null && sm.isSyncOutput()
                    && System.currentTimeMillis() - lastPaintAt < SYNC_OUTPUT_MAX_DEFER_MS) {
                if (paintHandler != null) paintHandler.postDelayed(this, MIN_PAINT_INTERVAL_MS);
                return;
            }
            lastPaintAt = System.currentTimeMillis();
            try {
                renderer.renderInto((DoubleBufferBitmapData) canvas.bitmapData);
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
