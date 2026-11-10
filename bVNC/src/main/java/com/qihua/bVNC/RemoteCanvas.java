/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * Copyright (C) 2004 Horizon Wimba.  All Rights Reserved.
 * Copyright (C) 2001-2003 HorizonLive.com, Inc.  All Rights Reserved.
 * Copyright (C) 2001,2002 Constantin Kaplinsky.  All Rights Reserved.
 * Copyright (C) 2000 Tridia Corporation.  All Rights Reserved.
 * Copyright (C) 1999 AT&T Laboratories Cambridge.  All Rights Reserved.
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

//
// RemoteCanvas is a subclass of android.view.SurfaceView which draws a VNC
// desktop on it.
//

package com.qihua.bVNC;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.ClipboardManager;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.limelight.binding.input.ControllerHandler;
import com.qihua.android.bc.BCFactory;
import com.qihua.bVNC.communicator.RfbCommunicator;
import com.qihua.bVNC.connection.ConnectionInitializer;
import com.qihua.bVNC.connection.ConnectionInitializerFactory;
import com.qihua.bVNC.connection.ProtocolType;
import com.qihua.bVNC.connection.SpiceConnectionInitializer;
import com.qihua.bVNC.draw.ClickRipplePainter;
import com.qihua.bVNC.draw.DrawWorker;
import com.qihua.bVNC.dialogs.GetTextFragment;
import com.qihua.bVNC.input.InputHandler;
import com.qihua.bVNC.input.InputHandlerTouchpad;
import com.qihua.bVNC.input.RemoteCanvasHandler;
import com.qihua.bVNC.ssh.SSHConnection;
import com.qihua.bVNC.input.RemoteKeyboard;
import com.qihua.bVNC.input.RemotePointer;
import com.qihua.bVNC.input.RemoteSshKeyboard;
import com.qihua.bVNC.util.SmartResolutionUtils;
import com.undatech.opaque.Connection;
import com.undatech.opaque.DrawTask;
import com.undatech.opaque.MessageDialogs;
import com.undatech.opaque.NvCommunicator;
import com.undatech.opaque.RdpCommunicator;
import com.undatech.opaque.input.PointerShape;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.RemoteConnectable;
import com.undatech.opaque.SpiceCommunicator;
import com.undatech.opaque.Viewable;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;

public class RemoteCanvas extends SurfaceView implements Viewable
        , SurfaceHolder.Callback, GetTextFragment.OnFragmentDismissedListener {
    private final static String TAG = "RemoteCanvas";

    public SurfaceHolder surfaceHolder;

    public AbstractScaling scaler;

    // Variable indicating that we are currently scrolling in simulated touchpad mode.
    public boolean cursorBeingMoved = false;

    public Rect displayRect;
    public float displayDensity;

    // Connection parameters
    public Connection connection;
    public SSHConnection sshConnection = null;

    // The communicators for different protocols
    public RemoteConnectable rfbconn = null;
    public RfbCommunicator rfb = null;
    public SpiceCommunicator spicecomm = null;
    public RdpCommunicator rdpcomm = null;
    public NvCommunicator nvcomm = null;

    public boolean maintainConnection = true;
    // True once the connection has been successfully established at least once.
    // Used to distinguish "initial connect failed" from "reconnect after drop failed"
    // for error messages shown to the user.
    public boolean everConnected = false;
    public AbstractBitmapData bitmapData;
    // Progress dialog shown at connection time.
    public AlertDialog progressDialog;
    public boolean serverJustCutText = false;
    public Runnable setModes;
    public Runnable hideKeyboardAndExtraKeys;
    public boolean spiceUpdateReceived = false;
    /**
     * Handler for the dialogs that display the x509/RDP/SSH key signatures to the user.
     * Also shows the dialogs which show various connection failures.
     */
    public Handler handler;

    /**
     * The ConnectionInitializer that owns protocol-specific lifecycle
     * for the current connection. Set in initializeCanvas(); null until
     * then. Per-protocol heartbeat, surface hooks, and teardown are
     * delegated to it; the rest of RemoteCanvas is protocol-agnostic.
     */
    public ConnectionInitializer connInitializer;

    private DrawWorker drawWorker;

    /**
     * 远端光标形状(RDP 专有)。形状随服务器回包滞后一个 RTT,点击瞬间拿到的是
     * 旧形状,所以点击只是挂一个待决,等新形状到了再决定要不要弹软键盘。
     */
    private static final long POINTER_SHAPE_DECIDE_DELAY_MS = 250;
    /** 自动弹起后多久内没等到输入,就算这次是误触发 */
    private static final long AUTO_KEYBOARD_INPUT_WINDOW_MS = 10000;
    /** 误触发后的静默期 */
    private static final long AUTO_KEYBOARD_SILENCE_MS = 120000;
    /** 弹起后多久内形状变成"非文本"就认为是误触发并收回键盘 */
    private static final long AUTO_KEYBOARD_CONTRADICT_MS = 1000;

    private volatile int lastPointerShape = PointerShape.UNKNOWN;
    /** 形状更新到达时的指针位置(framebuffer 坐标)。形状描述的是这个位置,
     *  拿它去判定别处的点击就是误判。 */
    private volatile int shapePosX = -1, shapePosY = -1;
    /** 待决点击的位置(framebuffer 坐标) */
    private int tapPosX = -1, tapPosY = -1;
    /** 形状位置与点击位置允许的偏差(framebuffer 像素) */
    private static final int SHAPE_POS_TOLERANCE = 16;
    private boolean pointerShapePending = false;
    private boolean autoKeyboardAwaitingInput = false;
    private long autoKeyboardShownAt = 0;
    private int autoKeyboardShownX = 0, autoKeyboardShownY = 0;
    private long suppressAutoKeyboardUntil = 0;
    private final Runnable pointerShapeTimeout = () -> {
        pointerShapePending = false;
        decidePointerShape();
    };
    private final Runnable autoKeyboardWatchdog = this::checkAutoKeyboardFalseTrigger;

    private InputHandler inputHandler;

    /**
     * 宿主 Activity。外接屏模式下本 View 由 CanvasPresentation(getBaseContext()) inflate,
     * getContext() 追不回 Activity,而弹框/取 FragmentManager 都要它。
     */
    private android.content.Context hostContext;

    public void setHostContext(android.content.Context c) {
        hostContext = c;
    }

    private android.content.Context dialogContext() {
        return hostContext != null ? hostContext : getContext();
    }

    private boolean outDisplay = false;

    Database database;
    public Map<String, String> vmNameToId = new HashMap<String, String>();
    // RFB Decoder
    public Decoder decoder = null;
    // The remote pointer and keyboard
    public RemotePointer pointer;
    public RemoteKeyboard keyboard;
    private final ClickRipplePainter ripplePainter =
            new ClickRipplePainter(getResources().getDisplayMetrics().density);
    public ControllerHandler controller;
    public boolean useFull = false;
    boolean compact = false;
    // Used to set the contents of the clipboard.
    ClipboardManager clipboard;
    Timer clipboardMonitorTimer;
    ClipboardMonitor clipboardMonitor;
    /*
     * Position of the top left portion of the <i>visible</i> part of the screen, in
     * full-frame coordinates
     */
    public int absoluteXPosition = 0, absoluteYPosition = 0;

    /** Colour painted under the framebuffer. Panning up (soft keyboard
     *  push-up) exposes this strip at the bottom; transparent keeps the
     *  window's black behind it for RDP/VNC, SSH sets its terminal bg so
     *  the exposed band doesn't read as a black frame. */
    public volatile int surfaceBackgroundColor = 0x00000000;

    public void setSurfaceBackgroundColor(int color) {
        surfaceBackgroundColor = color;
    }

    /*
     * How much to shift coordinates over when converting from full to view coordinates.
     */
    float shiftX = 0, shiftY = 0;

    /*
     * This variable holds the height of the visible rectangle of the screen. It is used to keep track
     * of how much of the screen is hidden by the soft keyboard if any.
     */
    int visibleHeight = -1;

    /*
     * Protocol identity. The boolean flags used to live here as
     * fields, but they were a four-way `isXxx = ...` ladder kept in
     * sync with the dispatch in initializeCanvas(). Now that each
     * protocol's lifecycle is owned by a ConnectionInitializer
     * strategy, the source of truth is `currentInitializer`; the
     * accessors below delegate to `instanceof`.
     */

    public boolean isRunning = false;

    public boolean sshTunneled = false;
    boolean userPanned = false;
    String vvFileName;
    /**
     * This runnable displays a message on the screen.
     */
    CharSequence screenMessage;
    /**
     * Shows a non-fatal error message.
     *
     * @param error
     */
    Runnable showDialogMessage = new Runnable() {
        public void run() {
            Utils.showErrorMessage(getContext(), String.valueOf(screenMessage));
        }
    };

    // Internal bitmap data
    private int capacity;
    public FpsCounter fpsCounter;

    private Runnable showMessage = new Runnable() {
        public void run() {
            Toast.makeText(getContext(), screenMessage, Toast.LENGTH_SHORT).show();
        }
    };

    private boolean touchpad = false;
    public RemoteCanvasActivity activity;

    // --- Idle clock on the phone-side touchpad (external display mode) ---
    private static final long IDLE_CLOCK_TIMEOUT_MS = 60 * 1000L;
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private boolean clockShowing = false;
    private boolean clockDismissGesture = false;
    private int surfaceWidth, surfaceHeight;
    private static final long CLOCK_FRAME_MS = 1000L;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (!clockShowing) return;
            drawClock();
            idleHandler.postDelayed(this, CLOCK_FRAME_MS);
        }
    };

    private final Runnable showIdleClock = new Runnable() {
        @Override
        public void run() {
            if (outDisplay || !touchpad || !isAttachedToWindow()) return;
            clockShowing = true;
            drawClock();
            idleHandler.postDelayed(clockTick, CLOCK_FRAME_MS);
        }
    };

    /**
     * Constructor used by the inflation apparatus
     *
     * @param context
     */
    public RemoteCanvas(final Context context, AttributeSet attrs) {
        super(context, attrs);

        fpsCounter = new FpsCounter();

        // 👇 关键：让 MIUI 知道这个 View 是“有意图”接收触摸的
        setClickable(true);           // 必须
        setFocusable(true);           // 推荐
        setFocusableInTouchMode(true); // 推荐（尤其在嵌套场景）

        clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);

        if (!isTouchpad()) {
            drawWorker = new DrawWorker(this);
        }

        surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);

        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.connection_progress, null);

        // we do not initialte the progress dialog if we are in external canvas
        // we will accept it from the touchpad canvas
        if (!outDisplay) {
            // 配置ProgressBar和TextView
            ProgressBar progressBar = dialogView.findViewById(R.id.progressBar);
            TextView messageView = dialogView.findViewById(R.id.message);
            messageView.setText(R.string.info_progress_dialog_establishing);

            progressDialog = new AlertDialog.Builder(getContext())
                    .setTitle(R.string.info_progress_dialog_connecting)
                    .setView(dialogView)
                    .setCancelable(true)
                    .setOnCancelListener(dialog -> {
                        // 外接屏模式下 touchpad 这个 canvas 从没被 initializeCanvas 过,handler 为 null
                        if (handler == null) {
                            return;
                        }
                        handler.post(() ->
                                Utils.showFatalErrorMessage(getContext(),
                                        getContext().getString(R.string.info_progress_dialog_aborted)));
                    })
                    .create();

            progressDialog.setCanceledOnTouchOutside(false);
        }
    }

    public void startPointerCapture() {
        // Capture requires the view to hold focus.
        requestFocus();
        requestPointerCapture();
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (connInitializer != null && connInitializer.onCapturedPointerEvent(event)) {
            return true;
        }
        return inputHandler.onPointerEvent(event);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        /* 内容按 ~60fps 产出,但面板可能跑 120Hz。不声明的话 SurfaceFlinger 只能按面板
           最高刷新率自由上屏,和 16.667ms 的 vsync 网格错相位 —— 每帧差 0.08ms,约每
           3 秒漂出一帧,表现就是"微跳"。Moonlight 那条早就这么声明了(见
           MediaCodecDecoderRenderer 的 setFrameRate 调用)。API 30+ 才有这个方法。 */
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                holder.getSurface().setFrameRate(60,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (Throwable ignored) {
            }
        }

        if (connInitializer != null) {
            connInitializer.onSurfaceCreated(holder);
        }

        if (!outDisplay && touchpad) {
            Rect frame = holder.getSurfaceFrame();
            surfaceWidth = frame != null && frame.width() > 0 ? frame.width() : getWidth();
            surfaceHeight = frame != null && frame.height() > 0 ? frame.height() : getHeight();
            if (clockShowing) {
                drawClock();
            } else {
                drawTouchpadHint();
            }
            noteUserInteraction();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (connInitializer != null) {
            connInitializer.onSurfaceChanged(holder, format, width, height);
        }
        if (!outDisplay && touchpad) {
            surfaceWidth = width;
            surfaceHeight = height;
            if (clockShowing) {
                drawClock(width, height);
            } else {
                drawTouchpadHint(width, height);
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!outDisplay && touchpad
                && !event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            if (clockShowing) {
                hideIdleClock();
                clockDismissGesture = true;
                return true;
            }
            if (clockDismissGesture) {
                // Swallow the rest of the tap that dismissed the clock so it
                // doesn't land as a remote click.
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    clockDismissGesture = false;
                }
                return true;
            }
            noteUserInteraction();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Hardware keys (external keyboard, volume/back) must not wake or
        // dismiss the idle clock; only phone-screen touches count.
        return super.dispatchKeyEvent(event);
    }

    /**
     * Catch {@code ACTION_MULTIPLE} events at the view level.
     *
     * The activity uses {@code View.OnKeyListener} to forward key events to
     * the input handler, but {@code OnKeyListener.onKey} is only invoked
     * for {@code ACTION_DOWN}. For {@code ACTION_MULTIPLE} (the IME's
     * "here's a chunk of unicode text" flavor on some IMEs) the default
     * {@code View.onKeyMultiple} returns {@code false} and the event
     * bubbles up the tree unhandled — it never reaches
     * {@link RemoteSshKeyboard#processLocalKeyEvent}.
     *
     * For SSH we forward {@code ACTION_MULTIPLE} directly to the keyboard
     * so the existing {@code processLocalKeyEvent} ACTION_MULTIPLE branch
     * can run. Other protocols ignore this and fall through to the default
     * behavior.
     */
    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE
                && keyboard != null
                && getProtocolType() == ProtocolType.SSH) {
            return keyboard.keyEvent(keyCode, event);
        }
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        isRunning = false;
        idleHandler.removeCallbacks(showIdleClock);
        idleHandler.removeCallbacks(clockTick);
        clockShowing = false;

        if (connInitializer != null) {
            connInitializer.onSurfaceDestroyed(holder);
        }
    }

    void init(final Connection settings, final Handler handler, final Runnable setModes, final Runnable hideKeyboardAndExtraKeys, final String vvFileName) {
        this.connection = settings;
        this.handler = handler;
        this.setModes = setModes;
        this.hideKeyboardAndExtraKeys = hideKeyboardAndExtraKeys;
        this.vvFileName = vvFileName;

        checkNetworkConnectivity();
        initializeClipboardMonitor();

        // SPICE / Opaque lifecycle now lives in SpiceConnectionInitializer.
        // init() is only ever called from the Opaque flavor, so the
        // factory always returns SpiceConnectionInitializer here.
        connInitializer = ConnectionInitializerFactory.create(settings, getContext());
        SpiceConnectionInitializer spice = (SpiceConnectionInitializer) connInitializer;
        spice.vvFileName = vvFileName;
        try {
            spice.initialize(this);
            spice.start(this);
        } catch (Throwable e) {
            handleUncaughtException(e);
        }
    }

    /**
     * Checks whether the device has networking and quits with an error if it doesn't.
     */
    private void checkNetworkConnectivity() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isAvailable() || !activeNetwork.isConnected()) {
            disconnectAndShowMessage(R.string.error_not_connected_to_network, R.string.error_dialog_title);
        }
    }

    public void disconnectAndShowMessage(final int messageId, final int titleId) {
        closeConnection();
        handler.post(new Runnable() {
            public void run() {
                MessageDialogs.displayMessageAndFinish(getContext(), messageId, titleId);
            }
        });
    }

    public void disconnectAndShowMessage(final int messageId, final int titleId, final String textToAppend) {
        closeConnection();
        handler.post(new Runnable() {
            public void run() {
                MessageDialogs.displayMessageAndFinish(getContext(), messageId, titleId, textToAppend);
            }
        });
    }

    public void disconnectWithoutMessage() {
        closeConnection();
//        Utils.justFinish(getContext());
    }

    public void saveZoomFactor(float zoomFactor) {
        connection.setLastZoomFactor(zoomFactor);
        connection.saveAndWriteRecent(false, getContext());
    }

    /**
     * Initializes the clipboard monitor.
     */
    private void initializeClipboardMonitor() {
        clipboardMonitor = new ClipboardMonitor(getContext(), this);
        if (clipboardMonitor != null) {
            clipboardMonitorTimer = new Timer();
            if (clipboardMonitorTimer != null) {
                clipboardMonitorTimer.schedule(clipboardMonitor, 0, 500);
            }
        }
    }

    /**
     * Reinitialize Canvas
     */
    public void reinitializeCanvas() {
        Log.i(TAG, "Reinitializing remote canvas");
        initializeCanvas(this.connection, this.setModes, this.hideKeyboardAndExtraKeys);
        handler.post(this.hideKeyboardAndExtraKeys);
    }

    /**
     * Reinitialize Opaque
     */
    public void reinitializeOpaque() {
        Log.i(TAG, "Reinitializing remote canvas opaque");
        init(this.connection, this.handler, this.setModes, this.hideKeyboardAndExtraKeys, this.vvFileName);
        handler.post(this.hideKeyboardAndExtraKeys);
    }

    public void showProgessDialog() {
        progressDialog.show();
    }

    public void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    /**
     * Create a view showing a remote desktop connection
     *
     * @param conn     Connection settings
     * @param setModes Callback to run on UI thread after connection is set up
     */
    public RemotePointer initializeCanvas(Connection conn, final Runnable setModes, final Runnable hideKeyboardAndExtraKeys) {
        maintainConnection = true;
        this.setModes = setModes;
        this.hideKeyboardAndExtraKeys = hideKeyboardAndExtraKeys;
        connection = conn;
        sshTunneled = (connection.getConnectionType() == Constants.CONN_TYPE_SSH);
        handler = new RemoteCanvasHandler(dialogContext(), this, connection);

        // Per-protocol lifecycle now lives in ConnectionInitializer strategies.
        // The factory picks one based on the connection type (and SPICE
        // app flavor). Protocol identity is exposed via the isXxx()
        // accessors below, which delegate to `currentInitializer`.
        if (connInitializer != null) {
            // Replacing the strategy (reconnect/reinitialize): let the old one
            // release listeners and pending state first.
            connInitializer.onDestroy();
        }
        connInitializer = ConnectionInitializerFactory.create(conn, getContext());
        if (connInitializer == null) {
            // No strategy for this connection. The non-Opaque entry
            // points (VNC/RDP/NVStream/SSH) all have their own
            // initializers; reaching this branch means the factory
            // was unable to recognise the connection.
            try {
                throw new Exception("unknown connection type");
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        } else {
            try {
                connInitializer.initialize(this);
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        }

        clipboardMonitor = new ClipboardMonitor(getContext(), this);
        clipboardMonitorTimer = new Timer();
        try {
            clipboardMonitorTimer.schedule(clipboardMonitor, 0, 500);
        } catch (NullPointerException ignored) {
        }

        return pointer;
    }

    public void startConnection() {
        if (connInitializer == null) {
            // No strategy matched. With every protocol's initializer in
            // place the factory always returns one; this branch is a
            // defensive net for unknown connection types.
            try {
                throw new Exception("unknown connection type");
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        } else {
            try {
                connInitializer.start(this);
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        }
    }

    public void handleUncaughtException(Throwable e) {
        if (maintainConnection) {
            Log.e(TAG, e.toString());
//            e.printStackTrace();
            // Ensure we dismiss the progress dialog before we finish
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            if (e instanceof OutOfMemoryError) {
                disposeDrawable();
                showFatalMessageAndQuit(getContext().getString(R.string.error_out_of_memory));
            } else {
                String error = getContext().getString(R.string.error_connection_failed);
                if (e.getMessage() != null) {
                    if (e.getMessage().indexOf("SSH") < 0 &&
                            (e.getMessage().indexOf("authentication") > -1 ||
                                    e.getMessage().indexOf("Unknown security result") > -1 ||
                                    e.getMessage().indexOf("password check failed") > -1)
                    ) {
                        error = getContext().getString(R.string.error_vnc_authentication);
                    }
                    error = error + "<br>" + e.getLocalizedMessage();
                }
                showFatalMessageAndQuit(error);
            }
        }
    }

    /**
     * Retreives the requested remote width.
     */
    @Override
    public int getDesiredWidth() {
        int w = getRemoteWidth(displayRect.width(), displayRect.height());
        if (!connection.isRequestingNewDisplayResolution() &&
                connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM) {
            w = connection.getRdpWidth();
        }
        Log.d(TAG, "Width requested: " + w);
        return w;
    }

    /**
     * Retreives the requested remote height.
     */
    @Override
    public int getDesiredHeight() {
        int h = getRemoteHeight(displayRect.width(), displayRect.height());
        if (!connection.isRequestingNewDisplayResolution() &&
                connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM) {
            h = connection.getRdpHeight();
        }
        Log.d(TAG, "Height requested: " + h);
        return h;
    }


    /**
     * Retreives the requested remote width.
     */
    public int getRemoteWidth(int viewWidth, int viewHeight) {
        int remoteWidth = 0;
        int reqWidth = connection.getRdpWidth();
        int reqHeight = connection.getRdpHeight();
        if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM &&
                reqWidth >= 2 && reqHeight >= 2) {
            remoteWidth = reqWidth;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_NATIVE) {
            remoteWidth = viewWidth;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_FULL_HD) {
            remoteWidth = 1920;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_2K) {
            remoteWidth = 2560;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_SMART) {
            // 在外接显示器模式下，使用外接显示器的分辨率
            int smartWidth = calculateSmartResolutionWidth();
            remoteWidth = smartWidth;
        } else {
            remoteWidth = 1920;
        }

        return remoteWidth;
    }

    /**
     * Retreives the requested remote height.
     */
    public int getRemoteHeight(int viewWidth, int viewHeight) {
        int remoteHeight = 0;
        int reqWidth = connection.getRdpWidth();
        int reqHeight = connection.getRdpHeight();
        if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM &&
                reqWidth >= 2 && reqHeight >= 2) {
            remoteHeight = reqHeight;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_NATIVE) {
            remoteHeight = viewHeight;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_FULL_HD) {
            remoteHeight = 1080;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_2K) {
            remoteHeight = 1440;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_SMART) {
            // 在外接显示器模式下，使用外接显示器的分辨率
            int smartHeight = calculateSmartResolutionHeight();
            remoteHeight = smartHeight;
        } else {
            remoteHeight = 1080;
        }

        return remoteHeight;
    }

    /**
     * 计算智能分辨率的宽度
     * 在外接显示器模式下，使用外接显示器的分辨率
     */
    private int calculateSmartResolutionWidth() {
        if (outDisplay) {
            // 外接显示器模式：使用displayRect的尺寸
            return SmartResolutionUtils.calculateSmartResolution(displayRect.width(), displayRect.height())[0];
        } else {
            return SmartResolutionUtils.calculateSmartResolution(getContext())[0];
        }
    }

    /**
     * 计算智能分辨率的高度
     * 在外接显示器模式下，使用外接显示器的分辨率
     */
    private int calculateSmartResolutionHeight() {
        if (outDisplay) {
            // 外接显示器模式：使用displayRect的尺寸
            return SmartResolutionUtils.calculateSmartResolution(displayRect.width(), displayRect.height())[1];
        } else {
            return SmartResolutionUtils.calculateSmartResolution(getContext())[1];
        }
    }

    void showMessage(final String error) {
        Log.d(TAG, "showMessage");
        screenMessage = error;
        handler.removeCallbacks(showDialogMessage);
        handler.post(showDialogMessage);
    }

    /**
     * Closes the connection and shows a fatal message which ends the activity.
     *
     * @param error
     */
    public void showFatalMessageAndQuit(final String error) {
        closeConnection();
        handler.post(new Runnable() {
            public void run() {
                Utils.showFatalErrorMessage(dialogContext(), error);
            }
        });
    }

    public void showToastAndReconnect(final String warn) {
        reConnect();
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(getContext(), warn, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * If necessary, initializes an SSH tunnel and returns local forwarded port, or
     * if SSH tunneling is not needed, returns the given port.
     *
     * @return
     */
    public int getRemoteProtocolPort(int port) throws Exception {
        int result = 0;

        if (sshTunneled) {
            sshConnection = new SSHConnection(connection, getContext(), handler);
            int newPort = sshConnection.initializeSSHTunnel();
            if (newPort > 0)
                port = newPort;
            result = sshConnection.createLocalPortForward(port);
        } else {
            if (getProtocolType() == ProtocolType.VNC && port <= 20) {
                result = Constants.DEFAULT_VNC_PORT + port;
            } else {
                result = port;
            }
        }
        return result;
    }

    /**
     * Returns localhost if using SSH tunnel or saved VNC address.
     *
     * @return
     */
    public String getAddress() {
        if (sshTunneled) {
            return new String("127.0.0.1");
        } else
            return connection.getAddress();
    }

    /**
     * Initializes the drawable and bitmap into which the remote desktop is drawn.
     *
     * @param dx
     * @param dy
     * @throws IOException
     */
    @Override
    public void reallocateDrawable(int dx, int dy) {
        Log.i(TAG, "Desktop name is " + rfbconn.desktopName());
        Log.i(TAG, "Desktop size is " + rfbconn.framebufferWidth() + " x " + rfbconn.framebufferHeight());

        int fbsize = rfbconn.framebufferWidth() * rfbconn.framebufferHeight();

        capacity = BCFactory.getInstance().getBCActivityManager().getMemoryClass(Utils.getActivityManager(getContext()));

        if (connection.getForceFull() == BitmapImplHint.AUTO) {
            if (fbsize * CompactBitmapData.CAPACITY_MULTIPLIER <= capacity * 1024 * 1024) {
                useFull = true;
                compact = true;
            } else if (fbsize * FullBufferBitmapData.CAPACITY_MULTIPLIER <= capacity * 1024 * 1024) {
                useFull = true;
            } else {
                useFull = false;
            }
        } else {
            useFull = (connection.getForceFull() == BitmapImplHint.FULL);
        }

        ProtocolType protocol = getProtocolType();
        // Direct-rendering protocols paint straight into this SurfaceView; the
        // bitmap path would otherwise paint a stale copy over the decoded frames.
        if (usesDirectVideoRendering() && drawWorker != null) {
            drawWorker.stop();
            drawWorker = null;
        }
        boolean isPushlessProtocol = protocol == ProtocolType.RDP
                || protocol == ProtocolType.NVSTREAM
                || protocol == ProtocolType.SSH;
        boolean isUltraCompactProtocol = isPushlessProtocol || protocol == ProtocolType.SPICE;
        if (protocol == ProtocolType.SSH) {
            // SSH paints whole frames from its own thread, so the
            // double-buffered bitmap class — front/back swap, no torn
            // frames — fits cleanly. Other pushless protocols (RDP,
            // NVSTREAM, SPICE) keep their incremental-update model via
            // UltraCompactBitmapData for now.
            int w = Math.max(1, rfbconn.framebufferWidth());
            int h = Math.max(1, rfbconn.framebufferHeight());
            bitmapData = new DoubleBufferBitmapData(rfbconn, this, w, h, Bitmap.Config.ARGB_8888);
            Log.i(TAG, "Using DoubleBufferBitmapData (SSH).");
        } else if (protocol == ProtocolType.RDP) {
            // RDP 的整帧是 native 侧写进来的,写的时候不能和绘制线程抢同一块
            // 像素内存(以前靠 synchronized(bitmap) 顶着,绘制线程优先级更高,
            // 反而把解码线程压住了 → 帧延迟和撕裂)。改成双缓冲:写 back,画 front。
            int w = Math.max(1, rfbconn.framebufferWidth());
            int h = Math.max(1, rfbconn.framebufferHeight());
            bitmapData = new DoubleBufferBitmapData(rfbconn, this, w, h, Bitmap.Config.ARGB_8888);
            Log.i(TAG, "Using DoubleBufferBitmapData (RDP).");
        } else if (isPushlessProtocol) {
            bitmapData = new UltraCompactBitmapData(rfbconn, this, isUltraCompactProtocol);
            Log.i(TAG, "Using UltraCompactBufferBitmapData.");
        } else if (!useFull) {
            bitmapData = new LargeBitmapData(rfbconn, this, dx, dy, capacity);
            Log.i(TAG, "Using LargeBitmapData.");
        } else {
            try {
                // TODO: Remove this if Android 4.2 receives a fix for a bug which causes it to stop drawing
                // the bitmap in CompactBitmapData when under load (say playing a video over VNC).
                if (!compact) {
                    bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);
                    Log.i(TAG, "Using FullBufferBitmapData.");
                } else {
                    bitmapData = new CompactBitmapData(rfbconn, this, protocol == ProtocolType.SPICE);
                    Log.i(TAG, "Using CompactBufferBitmapData.");
                }
            } catch (Throwable e) { // If despite our efforts we fail to allocate memory, use LBBM.
                disposeDrawable();

                useFull = false;
                bitmapData = new LargeBitmapData(rfbconn, this, dx, dy, capacity);
                Log.i(TAG, "Using LargeBitmapData.");
            }
        }

        try {
            if (needsLocalCursor()) {
                initializeSoftCursor();
            }

            if (connInitializer != null) {
                connInitializer.onDrawableReallocated();
            }

            handler.post(setModes);
//            myDrawable.syncScroll();
            if (decoder != null) {
                decoder.setBitmapData(bitmapData);
            }

            isRunning = true;
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
    }

    /**
     * Determines if the app should show a local cursor or not
     */
    private boolean needsLocalCursor() {
        ProtocolType protocol = getProtocolType();
        boolean isRdpSpiceOrOpaque = protocol == ProtocolType.RDP || protocol == ProtocolType.SPICE;
        boolean localCursorNotForceDisabled =
                connection.getUseLocalCursor() != Constants.CURSOR_FORCE_DISABLE;
        boolean localCursorForceEnabled =
                connection.getUseLocalCursor() == Constants.CURSOR_FORCE_LOCAL;
        return (isRdpSpiceOrOpaque && localCursorNotForceDisabled) || localCursorForceEnabled;
    }

    /**
     * Disposes of the old drawable which holds the remote desktop data.
     */
    private void disposeDrawable() {
        if (bitmapData != null)
            bitmapData.dispose();
        bitmapData = null;
//        System.gc();
    }

    /**
     * The remote desktop's size has changed and this method
     * reinitializes local data structures to match.
     */
    public void updateFBSize() {
        try {
            bitmapData.frameBufferSizeChanged();
        } catch (Throwable e) {
            boolean useLBBM = false;

            // If we've run out of memory, try using another bitmapdata type.
            if (e instanceof OutOfMemoryError) {
                disposeDrawable();

                // If we were using CompactBitmapData, try FullBufferBitmapData.
                if (compact == true) {
                    compact = false;
                    try {
                        bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);
                    } catch (Throwable e2) {
                        useLBBM = true;
                    }
                } else
                    useLBBM = true;

                // Failing FullBufferBitmapData or if we weren't using CompactBitmapData, try LBBM.
                if (useLBBM) {
                    disposeDrawable();

                    useFull = false;
                    bitmapData = new LargeBitmapData(rfbconn, this, displayRect.width(), displayRect.height(), capacity);
                }
                if (decoder != null) {
                    decoder.setBitmapData(bitmapData);
                }
            }
        }

        handler.post(setModes);
        bitmapData.syncScroll();
    }

    /**
     * Displays a short toast message on the screen.
     *
     * @param message
     */
    public void displayShortToastMessage(final CharSequence message) {
        screenMessage = message;
        handler.removeCallbacks(showMessage);
        handler.post(showMessage);
    }

    /**
     * Displays a short toast message on the screen.
     *
     * @param messageID
     */
    public void displayShortToastMessage(final int messageID) {
        screenMessage = getResources().getText(messageID);
        handler.removeCallbacks(showMessage);
        handler.post(showMessage);
    }

    /**
     * Lets the drawable know that an update from the remote server has arrived.
     */
    public void doneWaiting() {
        bitmapData.doneWaiting();
    }

    /**
     * Indicates that RemoteCanvas's scroll position should be synchronized with the
     * drawable's scroll position (used only in LargeBitmapData)
     */
    public void syncScroll() {
        bitmapData.syncScroll();
    }

    /*
     * f(x,s) is a function that returns the coordinate in screen/scroll space corresponding
     * to the coordinate x in full-frame space with scaling s.
     *
     * This function returns the difference between f(x,s1) and f(x,s2)
     *
     * f(x,s) = (x - i/2) * s + ((i - w)/2)) * s
     *        = s (x - i/2 + i/2 + w/2)
     *        = s (x + w/2)
     *
     *
     * f(x,s) = (x - ((i - w)/2)) * s
     * @param oldscaling
     * @param scaling
     * @param imageDim
     * @param windowDim
     * @param offset
     * @return
     */

    /**
     * Requests a remote desktop update at the specified rectangle.
     */
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h, boolean incremental) throws IOException {
        bitmapData.prepareFullUpdateRequest(incremental);
        rfbconn.writeFramebufferUpdateRequest(x, y, w, h, incremental);
    }

    /**
     * Requests an update of the entire remote desktop.
     */
    public void writeFullUpdateRequest(boolean incremental) {
        bitmapData.prepareFullUpdateRequest(incremental);
        rfbconn.writeFramebufferUpdateRequest(bitmapData.getXoffset(), bitmapData.getYoffset(),
                bitmapData.bmWidth(), bitmapData.bmHeight(), incremental);
    }

    /**
     * Set the device clipboard text with the string parameter.
     */
    public void setClipboardText(String s) {
        if (s != null && s.length() > 0) {
            clipboard.setText(s);
        }
    }

    /**
     * Method that disconnects from the remote server.
     */
    public void closeConnection() {
        // 断开前先停掉输入侧的惯性尾巴,免得它继续往正在拆的连接里推事件。
        // 活动 handler 在 Activity 上(canvas.inputHandler 在外屏模式下恒为 null)。
        if (activity != null) {
            activity.stopActiveInputHandler();
        } else if (inputHandler != null) {
            inputHandler.cleanup();
        }

        // Last point where the surface is still alive (thumbnail, etc).
        if (connInitializer != null) {
            connInitializer.onBeforeDisconnect();
        }

        maintainConnection = false;

        if (keyboard != null) {
            // Tell the server to release any meta keys.
            keyboard.clearMetaState();
            keyboard.keyEvent(0, new KeyEvent(KeyEvent.ACTION_UP, 0));
        }

        // Close the rfb connection.
        if (rfbconn != null) {
            rfbconn.close();
        }

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // Close the SSH tunnel.
        if (sshConnection != null) {
            sshConnection.terminateSSHTunnel();
            sshConnection = null;
        }

        if (connection != null && (connInitializer == null || !connInitializer.writesOwnScreenshot())) {
            String path = getContext().getFilesDir() + "/" + connection.getScreenshotFilename();
            Log.d(TAG, "Saving screenshot to " + path);
            Utils.writeScreenshotToFile(bitmapData, path, 720);
        }

//        disposeDrawable();
        onDestroy();
    }

    public void reConnect() {
        rfbconn.reconnect();
    }

    /**
     * Cleans up resources after a disconnection.
     */
    public void onDestroy() {
        Log.v(TAG, "Cleaning up resources");

        removeCallbacksAndMessages();
        if (connInitializer != null) {
            connInitializer.onDestroy();
        }
        if (drawWorker != null) {
            drawWorker.stop();
            drawWorker = null;
        }
        if (clipboardMonitorTimer != null) {
            clipboardMonitorTimer.cancel();
            // Occasionally causes a NullPointerException
            //clipboardMonitorTimer.purge();
            clipboardMonitorTimer = null;
        }
        clipboardMonitor = null;
        clipboard = null;
        setModes = null;
        decoder = null;
        scaler = null;
        screenMessage = null;

        ripplePainter.clear();
        disposeDrawable();
    }

    public void removeCallbacksAndMessages() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Computes the X and Y offset for converting coordinates from full-frame coordinates to view coordinates.
     */
    public void computeShiftFromFullToView() {
        shiftX = (rfbconn.framebufferWidth() - displayRect.width()) / 2;
        shiftY = (rfbconn.framebufferHeight() - displayRect.height()) / 2;
    }

    /**
     * Change to Canvas's scroll position to match the absoluteXPosition
     */
    void resetScroll() {
//        float scale = getZoomFactor();
        //android.util.Log.d(TAG, "resetScroll: " + (absoluteXPosition - shiftX) * scale + ", "
        //                                        + (absoluteYPosition - shiftY) * scale);

        repaint(0, 0, getWidth(), getHeight());
//        scrollTo((int) ((absoluteXPosition) * scale),
//                (int) ((absoluteYPosition) * scale));
    }

    /**
     * Make sure mouse is visible on displayable part of screen
     */
    public boolean movePanToMakePointerVisible() {
        //Log.d(TAG, "movePanToMakePointerVisible");
        if (usesDirectVideoRendering()) return false;
        if (rfbconn == null)
            return false;

        // Do not pan when not scaled.
//        if (getZoomFactor() == getMinimumScale()) {
//            return;
//        }

        boolean panX = true;
        boolean panY = true;

        boolean panned = false;

        // We only pan if the current scaling is able to pan.
        if (scaler != null && !scaler.isAbleToPan())
            return false;

        // Coordinates in screen's resolution
        int x = (int) (pointer.getX());
        int y = (int) (pointer.getY());
        int wthresh = (int) (Constants.H_THRESH / getZoomFactor());
        int hthresh = (int) (Constants.W_THRESH / getZoomFactor());

        // Coordinates in picture's resolution
        int w = getVisibleDesktopWidth();
        int h = getVisibleDesktopHeight();
        int iw = getImageWidth();
        int ih = getImageHeight();

        // bWidth = black border width
        int bWidth = getBlackBorderWidth();

        // This is definitely 0 because we have the image top aligned
        int bHeight = 0;

        // newX and newY are in screen's resolution
        int newX = absoluteXPosition;
        int newY = absoluteYPosition;

        if ((x + bWidth > w + absoluteXPosition - wthresh) && (absoluteXPosition + w < bWidth + iw)) {
            newX += (x - (w + absoluteXPosition - wthresh - bWidth));

            // left padding x + image_width visible is larger than left black border + image_width
            // which means the right side has been reached, no more space to do right panning now
            if (newX + w > iw + bWidth) {
                newX = iw - w + bWidth;
            }
        } else if ((x + bWidth < absoluteXPosition + wthresh) && (x > 0)) {
            newX += (x - (absoluteXPosition + wthresh - bWidth));
            if (newX < bWidth) {
                newX = bWidth;
            }
        }

        if (panX && newX != absoluteXPosition) {
            absoluteXPosition = newX;
            panned = true;
        }

        // when the soft keyboard shown, the bottom space decreased, we must
        // cut off the height to make the bottom edge pan work properly.
        float keyboardHeight = activity.getKeyboardHeightInVisibleImage();

        // do not pan when there is space left on the screen
        if ((y + bHeight > h + absoluteYPosition - hthresh - keyboardHeight) && (absoluteYPosition + h < bHeight + ih + keyboardHeight)) {
            newY += ((y) - (h + absoluteYPosition - hthresh - keyboardHeight) - bHeight);
            if (newY + h > ih + bHeight + keyboardHeight) {
                newY = (int) (ih + bHeight + keyboardHeight - h);
            }
        } else if ((y + bHeight < absoluteYPosition + hthresh) && y > 0) {
            newY += (y - (absoluteYPosition + hthresh - bHeight));
            if (newY < bHeight) {
                newY = bHeight;
            }
        }

        if (panY && newY != absoluteYPosition) {
            absoluteYPosition = newY;
            panned = true;
        }

        if (panned) {
            //scrollBy(newX - absoluteXPosition, newY - absoluteYPosition);
            resetScroll();
        }

        return panned;
    }

    public int getTopMargin(double scale) {
        return (int) (Constants.TOP_MARGIN / scale);
    }

    public int getBottomMargin(double scale) {
        return (int) (Constants.BOTTOM_MARGIN / scale);
    }

    /**
     * Pan by a number of pixels (relative pan)
     *
     * @param dX
     * @param dY
     * @return True if the pan changed the view (did not move view out of bounds); false otherwise
     */
    public boolean relativePan(float dX, float dY) {
        if (usesDirectVideoRendering()) return false;
        // We only pan if the current scaling is able to pan.
        if (scaler != null && !scaler.isAbleToPan())
            return false;

//        double sX = dX;
//        double sY = dY;

        // Prevent panning right or below desktop image except for provision for on-screen
        // buttons and curved screens
//        if (absoluteXPosition + getVisibleDesktopWidth() + sX > getImageWidth())
//            sX = getImageWidth() - getVisibleDesktopWidth() - absoluteXPosition;
//        if (absoluteYPosition + getVisibleDesktopHeight() + sY > getImageHeight())
//            sY = getImageHeight() - getVisibleDesktopHeight() - absoluteYPosition;

//        absoluteXPosition += sX;
//        absoluteYPosition += sY;
        if (dX != 0.0 || dY != 0.0) {
            absolutePan((int) (absoluteXPosition + dX), (int) (absoluteYPosition + dY), false);
            return true;
        }
        return false;
    }

    public boolean relativePan(float dX, float dY, boolean force) {
        if (usesDirectVideoRendering()) return false;
        // We only pan if the current scaling is able to pan.
        if (scaler != null && !scaler.isAbleToPan())
            return false;

//        double sX = dX;
//        double sY = dY;

        // Prevent panning right or below desktop image except for provision for on-screen
        // buttons and curved screens
//        if (absoluteXPosition + getVisibleDesktopWidth() + sX > getImageWidth())
//            sX = getImageWidth() - getVisibleDesktopWidth() - absoluteXPosition;
//        if (absoluteYPosition + getVisibleDesktopHeight() + sY > getImageHeight())
//            sY = getImageHeight() - getVisibleDesktopHeight() - absoluteYPosition;

//        absoluteXPosition += sX;
//        absoluteYPosition += sY;
        if (dX != 0.0 || dY != 0.0) {
            absolutePan((int) (absoluteXPosition + dX), (int) (absoluteYPosition + dY), force);
            return true;
        }
        return false;
    }

    /**
     * Absolute pan. These coordinates are in image's resolution.
     *
     * @param x
     * @param y
     */
    public void absolutePan(int x, int y, boolean force) {
        //android.util.Log.d(TAG, "absolutePan: " + x + ", " + y);
        if (usesDirectVideoRendering()) return;

        if (scaler != null) {
            int vW = getVisibleDesktopWidth();
            int vH = getVisibleDesktopHeight();
            int w = getImageWidth();
            int h = getImageHeight();

            int bWidth = (int) ((getWidth() - w * getMinimumScale()) / 2);
            if (bWidth <= 0) {
                bWidth = 0;
            }

            if (!force) {
                if (x + vW > w + bWidth) x = w + bWidth - vW;
                if (y + vH > h) y = h - vH;
                if (x < 0) x = 0;
                if (y < 0) y = 0;
            }

            absoluteXPosition = x;
            absoluteYPosition = y;
            resetScroll();
        }
    }

    /* (non-Javadoc)
     * @see android.view.View#onScrollChanged(int, int, int, int)
     */
    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (bitmapData != null) {
            bitmapData.scrollChanged(absoluteXPosition, absoluteYPosition);
        }
    }

    @Override
    public Bitmap getBitmap() {
        if (bitmapData == null) {
            return null;
        }

        return bitmapData.mbitmap;
    }

    @Override
    public Bitmap getWriteBitmap() {
        if (bitmapData == null) {
            return null;
        }
        if (bitmapData instanceof DoubleBufferBitmapData) {
            return ((DoubleBufferBitmapData) bitmapData).getBackBitmap();
        }
        return bitmapData.mbitmap;
    }

    @Override
    public void publishFrame() {
        if (bitmapData instanceof DoubleBufferBitmapData) {
            ((DoubleBufferBitmapData) bitmapData).publishFrame();
        }
    }

    public void setActivity(RemoteCanvasActivity activity) {
        this.activity = activity;
    }

    public void showCursor() {
        bitmapData.setDrawCursor(true);
    }

    public void hideCursor() {
        bitmapData.setDrawCursor(false);
    }

    /**
     * Causes a redraw of the myDrawable to happen at the indicated coordinates.
     */
    public void reDraw(int x, int y, int w, int h) {
        reDraw(new DrawTask(x, y, w, h));
    }

    // 缩放/旋转后的重绘:只重画当前位图,不关闭进度对话框。reDraw 的 dismiss 是为首帧准备的
    // (连接成功、画面就绪),但 ZoomScaling 等在连接初始化阶段(setModes)就会调 reDraw,导致
    // NVStream 的进度框在 stage 开始前就被关掉,中间状态来不及显示。缩放重绘走本方法。
    public void repaint(int x, int y, int w, int h) {
        if (drawWorker == null) return;
        drawWorker.addTask(new DrawTask(x, y, w, h));
    }

    public void repaint(float x, float y, float w, float h) {
        repaint((int) x, (int) y, (int) w, (int) h);
    }

    public void reDraw(DrawTask drawTask) {
        // Direct-render protocols paint into the surface themselves; the progress
        // dialog is closed by the initializer when the first frame arrives.
        if (usesDirectVideoRendering()) {
            return;
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }

        //android.util.Log.i(TAG, "reDraw called: " + x +", " + y + " + " + w + "x" + h);
//        float scale = getZoomFactor();
//        float shiftedX = x - shiftX;
//        float shiftedY = y - shiftY;

        // drawWorker is null only after onDestroy (line ~1000) nulled it.
        // A paintRunnable can still be in flight on the SSH-Paint thread at
        // that point (teardown race) — without this guard reDraw NPEs, and
        // since paintRunnable runs on a HandlerThread the exception KILLS the
        // paint thread, after which nothing renders at all.
        if (drawWorker == null) return;
        drawWorker.addTask(drawTask);
    }

    /**
     * This is a float-accepting version of reDraw().
     * Causes a redraw of the myDrawable to happen at the indicated coordinates.
     */
    public void reDraw(float x, float y, float w, float h) {
        reDraw((int) x, (int) y, (int) w, (int) h);
    }

    public boolean usesDirectVideoRendering() {
        return connInitializer != null && connInitializer.usesDirectVideoRendering();
    }

    @Override
    public void onVideoFrameRendered() {
        if (fpsCounter != null) {
            fpsCounter.countRenderedFrame();
        }
        if (connInitializer != null) {
            connInitializer.onVideoFrameRendered();
        }
    }

    @Override
    public void scheduleConnectionProgressTimeout() {
        if (connInitializer != null) {
            connInitializer.scheduleConnectionProgressTimeout();
        }
    }

    /** External-display callers grab the thumbnail before the surface goes away. */
    public void captureScreenshotBeforeDisconnect() {
        if (connInitializer != null) {
            connInitializer.onBeforeDisconnect();
        }
    }

    /**
     * Invalidates (to redraw) the location of the remote pointer.
     */
    public void invalidateMousePosition() {
        if (bitmapData != null && !usesDirectVideoRendering()) {
            // add little offset for the cursor image
            bitmapData.moveCursorRect(pointer.getX() - pointer.getHotspotX(), pointer.getY() - pointer.getHotspotY());
            RectF r = bitmapData.getCursorRect();
            repaint(r.left, r.top, r.width(), r.height());
        }
    }

    /**
     * 点击反馈:在 framebuffer 坐标处画水波纹。坐标存 framebuffer 系,
     * 绘制时用当前矩阵换算,平移缩放期间波纹不会和画面对不上。
     */
    public void showClickRipple(int fbX, int fbY) {
        ripplePainter.add(fbX, fbY);
        if (handler != null) {
            handler.removeCallbacks(rippleTicker);
            handler.post(rippleTicker);
        }
    }

    /** 由 DrawWorker 在位图之后调用(此时画布矩阵就是位图那套变换)。 */
    public void drawClickRipples(Canvas canvas) {
        ripplePainter.draw(canvas, currentScale());
    }

    /** 当前缩放系数:用来让波纹在屏幕上大小恒定 */
    private float currentScale() {
        Matrix m = scaler != null ? scaler.getMatrix() : null;
        if (m == null) return 1f;
        float[] v = new float[9];
        m.getValues(v);
        float s = v[Matrix.MSCALE_X];
        return (s > 0 && !Float.isNaN(s)) ? s : 1f;
    }

    /** 波纹还在动的时候持续请求重绘,否则动画会停在第一帧。 */
    private final Runnable rippleTicker = new Runnable() {
        @Override
        public void run() {
            if (ripplePainter.isEmpty() || drawWorker == null) return;
            repaint(0, 0, Math.max(1, surfaceWidth), Math.max(1, surfaceHeight));
            // 30fps 足够:每次重绘都是整帧上传,别把带宽吃满
            handler.postDelayed(this, 33);
        }
    };

    @Override
    public void setMousePointerPosition(int x, int y) {
        softCursorMove(x, y);
    }

    @Override
    public void mouseMode(boolean relative) {
        if (relative && !connection.getInputMode().equals(InputHandlerTouchpad.ID)) {
            showMessage(getContext().getString(R.string.info_set_touchpad_input_mode));
        } else {
            this.pointer.setRelativeEvents(relative);
        }
    }

    @Override
    public boolean isAbleToPan() {
        return scaler.isAbleToPan();
    }

    @Override
    public void onConnectionSuccess() {
        everConnected = true;
        handler.post(() -> {
            TextView messageView = progressDialog.findViewById(R.id.message);
            messageView.setText(R.string.info_continue_connected);

            // there's a bug of some system if we don't send any key event after connection
            // that no further image will be transfered
            pointer.moveMouse(getImageHeight() / 2, getImageWidth() / 2, 0);
        });
    }

    @Override
    public void setConnectionStatus(String status) {
        handler.post(() -> {
            // 不用 isShowing() 守卫:show() 可能尚未生效,但 TextView 已存在,先写入文字,
            // 等对话框真正显示时就是最新阶段文案。setText 对未显示的对话框无害。
            if (progressDialog == null || status == null) {
                return;
            }
            TextView messageView = progressDialog.findViewById(R.id.message);
            if (messageView != null) {
                messageView.setText(status);
            }
        });
    }

    @Override
    public void dismissConnectionProgress() {
        handler.post(this::dismissProgressDialog);
    }

    /**
     * Moves soft cursor into a particular location.
     *
     * @param x
     * @param y
     */
    public synchronized void softCursorMove(int x, int y) {
        if (bitmapData.isNotInitSoftCursor() && connection.getUseLocalCursor() != Constants.CURSOR_FORCE_DISABLE) {
            initializeSoftCursor();
        }

        if (!cursorBeingMoved || pointer.isRelativeEvents()) {
            pointer.setX(x);
            pointer.setY(y);
            RectF prevR = new RectF(bitmapData.getCursorRect());
            // Move the cursor.
            bitmapData.moveCursorRect(x, y);
            // Show the cursor.
//            RectF r = myDrawable.getCursorRect();
//            reDraw(r.left, r.top, r.width(), r.height());
//            reDraw(prevR.left, prevR.top, prevR.width(), prevR.height());
        }
    }

    /**
     * Initializes the data structure which holds the remote pointer data.
     */
    void initializeSoftCursor() {
    }

    public void setSoftCursorPixels(int[] pixels, int width, int height, int xPos, int yPos) {
        bitmapData.setCursorRect(pointer.getX() - xPos, pointer.getY() - yPos, width, height, 0, 0);
        bitmapData.setSoftCursor(pixels);

        pointer.setHotspotX(xPos);
        pointer.setHotspotY(yPos);
    }

    public void setSoftCursorBitmap(Bitmap bitmap, int width, int height, int xPos, int yPos) {
        bitmapData.setCursorRect(pointer.getX() - xPos, pointer.getY() - yPos, width, height, 0, 0);
        bitmapData.setSoftCursor(bitmap);

        pointer.setHotspotX(xPos);
        pointer.setHotspotY(yPos);
    }

    @Override
    public void onPointerShapeChanged(int type) {
        lastPointerShape = type;
        // 记下这个形状对应的是哪个位置:服务端的形状随位置更新一起发,
        //客户端此刻的指针位置就是它描述的位置。
        if (pointer != null) {
            shapePosX = pointer.getX();
            shapePosY = pointer.getY();
        }
        long now = SystemClock.uptimeMillis();
        if (handler == null) return;
        handler.post(() -> {
            // 弹起之后形状又变成"非文本":刚才用的是还没到的旧形状,收回键盘
            if (type != PointerShape.TEXT && autoKeyboardShownAt > 0
                    && now - autoKeyboardShownAt <= AUTO_KEYBOARD_CONTRADICT_MS
                    && cursorStillAtShowPos()
                    && shapeMatchesTapPos()) {
                autoKeyboardShownAt = 0;
                Log.i(TAG, "形状随后变成非文本,收回自动弹起的键盘");
                if (activity != null) activity.hideKeyboard();
                markAutoKeyboardFalseTrigger("形状随后变成非文本");
            }
            if (!pointerShapePending) return;
            pointerShapePending = false;
            handler.removeCallbacks(pointerShapeTimeout);
            decidePointerShape();
        });
    }

    /**
     * 左键点击后调用:远端光标形状更新到达时,若指针落在 I-beam(文本框)上就弹软键盘。
     * 形状更新晚于点击一个 RTT,所以这里只挂待决,由形状到达或超时兜底。
     */
    public void onTapMaybeShowKeyboard() {
        if (getProtocolType() != ProtocolType.RDP) return;
        if (activity == null || handler == null) return;
        if (!activity.isAutoKeyboardOnTextTap()) return;
        if (activity.isImeSuppressedByHwKeyboard()) return;
        if (SystemClock.uptimeMillis() < suppressAutoKeyboardUntil) {
            Log.d(TAG, "误触发静默期内,不弹键盘");
            return;
        }

        // 记住点击位置:待决的形状必须描述这个位置,否则就是拿旧形状误判
        if (pointer != null) {
            tapPosX = pointer.getX();
            tapPosY = pointer.getY();
        }
        pointerShapePending = true;
        handler.removeCallbacks(pointerShapeTimeout);
        // 超时兜底:形状一直没到(或到的是别的位置)就放弃这次判定,
        // 同时清掉待决标记,免得几秒后形状姗姗来迟又拿旧点击位置去弹键盘。
        handler.postDelayed(pointerShapeTimeout, POINTER_SHAPE_DECIDE_DELAY_MS);
    }

    /** 当前形状是否描述的正是待决点击的那个位置。 */
    private boolean shapeMatchesTapPos() {
        if (shapePosX < 0 || tapPosX < 0) return false;
        return Math.abs(shapePosX - tapPosX) <= SHAPE_POS_TOLERANCE
                && Math.abs(shapePosY - tapPosY) <= SHAPE_POS_TOLERANCE;
    }

    private void decidePointerShape() {
        if (lastPointerShape != PointerShape.TEXT) return;
        if (!shapeMatchesTapPos()) {
            // 手上的形状描述的是别的位置(比如上一次停在输入框上),不能拿来判定这次点击
            Log.d(TAG, "形状位置(" + shapePosX + "," + shapePosY
                    + ")与点击位置(" + tapPosX + "," + tapPosY + ")不符,不弹键盘");
            return;
        }
        if (activity == null || activity.isSoftKeyboardUp()) return;
        activity.showKeyboard();
        autoKeyboardShownAt = SystemClock.uptimeMillis();
        if (pointer != null) {
            autoKeyboardShownX = pointer.getX();
            autoKeyboardShownY = pointer.getY();
        }
        autoKeyboardAwaitingInput = true;
        handler.removeCallbacks(autoKeyboardWatchdog);
        handler.postDelayed(autoKeyboardWatchdog, AUTO_KEYBOARD_INPUT_WINDOW_MS);
    }

    /** 弹起之后光标有没有再动过:动过说明是新操作,不是刚才那次误判 */
    private boolean cursorStillAtShowPos() {
        if (pointer == null) return true;
        return pointer.getX() == autoKeyboardShownX && pointer.getY() == autoKeyboardShownY;
    }

    /** 远端收到按键:说明这次自动弹起是对的,取消误触发判定 */
    public void noteRemoteInput() {
        if (!autoKeyboardAwaitingInput) return;
        autoKeyboardAwaitingInput = false;
        if (handler != null) handler.removeCallbacks(autoKeyboardWatchdog);
    }

    private void checkAutoKeyboardFalseTrigger() {
        if (!autoKeyboardAwaitingInput) return;
        markAutoKeyboardFalseTrigger("弹起后没有输入");
    }

    /** 误触发:静默一段时间,别再乱弹 */
    private void markAutoKeyboardFalseTrigger(String reason) {
        autoKeyboardAwaitingInput = false;
        autoKeyboardShownAt = 0;
        suppressAutoKeyboardUntil = SystemClock.uptimeMillis() + AUTO_KEYBOARD_SILENCE_MS;
        if (handler != null) handler.removeCallbacks(autoKeyboardWatchdog);
        Log.i(TAG, "误触发(" + reason + "),静默 " + (AUTO_KEYBOARD_SILENCE_MS / 1000) + " 秒");
    }

    public RemotePointer getPointer() {
        return pointer;
    }

    public RemoteKeyboard getKeyboard() {
        return keyboard;
    }

    public ControllerHandler getController() {
        return controller;
    }

    public float getZoomFactor() {
        if (scaler == null)
            return 1;
        return scaler.getZoomFactor();
    }

    public int getVisibleDesktopWidth() {
        return (int) ((double) getWidth() / getZoomFactor());
    }

    public int getVisibleDesktopHeight() {
        return (int) ((double) getHeight() / getZoomFactor());
    }

    public int getImageVisibleInScreenHeight() {
        return (int) ((getImageHeight() - getAbsY()) * getZoomFactor());
    }

    public void setVisibleDesktopHeight(int newHeight) {
        visibleHeight = newHeight;
    }

    public int getImageWidth() {
        return rfbconn.framebufferWidth();
    }

    public int getImageHeight() {
        return rfbconn.framebufferHeight();
    }

    public int getCenteredXOffset() {
        return (int) (rfbconn.framebufferWidth() * getMinimumScale() - getWidth()) / 2;
    }

    public int getBlackBorderWidth() {
        int bWidth = (int) ((getWidth() - getImageWidth() * getMinimumScale()) / 2);
        if (bWidth <= 0) {
            bWidth = 0;
        }

        return bWidth;
    }

    public int getCenteredYOffset() {
        return (int) (rfbconn.framebufferHeight() * getMinimumScale() - getHeight()) / 2;
    }

    public float getMinimumScale() {
        if (connInitializer != null) {
            float override = connInitializer.getMinimumScaleOverride();
            if (override >= 0) {
                return override;
            }
        }
        if (bitmapData != null) {
            return bitmapData.getMinimumScale();
        } else
            return 1.f;
    }

    public float getDisplayDensity() {
        return displayDensity;
    }

    public void setDisplayDensity(float displayDensity) {
        this.displayDensity = displayDensity;
    }

    public int getDisplayDpi() {
        return (int) (getResources().getDisplayMetrics().densityDpi);
    }

    public boolean isColorModel(COLORMODEL cm) {
        if (getProtocolType() == ProtocolType.VNC && decoder != null) {
            return (decoder.getColorModel() != null) && decoder.getColorModel().equals(cm);
        } else {
            return false;
        }
    }

    public void setColorModel(COLORMODEL cm) {
        if (getProtocolType() == ProtocolType.VNC && decoder != null) {
            decoder.setColorModel(cm);
        }
    }

    public boolean getMouseFollowPan() {
        return connection.getFollowPan();
    }

    public int getAbsX() {
        return absoluteXPosition;
    }

    public int getAbsY() {
        return absoluteYPosition;
    }

    /**
     * Used to wait until getWidth and getHeight return sane values.
     */
    public void waitUntilInflated() {
        synchronized (this) {
            while (displayRect.width() == 0 || displayRect.height() == 0) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        // no need to hide system cursor since the remote one is on the external display
        if (!touchpad) {
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
        }

        return super.onResolvePointerIcon(event, pointerIndex);
    }

    /**
     * Used to detect when the view is inflated to a sane size other than 0x0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Log.d(TAG, "onSizeChanged: " + w + "x" + h);
        if (w > 0 && h > 0) {
            synchronized (this) {
                this.notify(); // 唤醒等待布局的线程
            }
        }
    }

    /**
     * Function used to initialize an empty SSH HostKey for a new VNC over SSH connection.
     */
    public String retrievevvFileName() {
        return this.vvFileName;
    }

    @Override
    public void onTextObtained(String dialogId, String[] obtainedString, boolean dialogCancelled, boolean save) {
        if (dialogCancelled) {
            handler.sendEmptyMessage(RemoteClientLibConstants.DISCONNECT_NO_MESSAGE);
            return;
        }

        switch (dialogId) {
            case GetTextFragment.DIALOG_ID_GET_VNC_CREDENTIALS:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_VNC_USERNAME.");
                connection.setUserName(obtainedString[0]);
                connection.setPassword(obtainedString[1]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_VNC_PASSWORD:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_VNC_PASSWORD.");
                connection.setPassword(obtainedString[0]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_RDP_CREDENTIALS:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_VNC_PASSWORD.");
                connection.setUserName(obtainedString[0]);
                connection.setRdpDomain(obtainedString[1]);
                connection.setPassword(obtainedString[2]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_SPICE_PASSWORD:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_SPICE_PASSWORD.");
                connection.setPassword(obtainedString[0]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_OPAQUE_CREDENTIALS:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_OPAQUE_CREDENTIALS");
                connection.setUserName(obtainedString[0]);
                connection.setPassword(obtainedString[1]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_OPAQUE_PASSWORD:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_OPAQUE_PASSWORD");
                connection.setPassword(obtainedString[0]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                synchronized (spicecomm) {
                    spicecomm.notify();
                }
                break;
            case GetTextFragment.DIALOG_ID_GET_OPAQUE_OTP_CODE:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_OPAQUE_OTP_CODE");
                connection.setOtpCode(obtainedString[0]);
                synchronized (spicecomm) {
                    spicecomm.notify();
                }
                break;
            default:
                Log.e(TAG, "Unknown dialog type.");
                break;
        }
    }

    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    public void setOutDisplay(boolean outDisplay) {
        this.outDisplay = outDisplay;
    }

    public boolean isOutDisplay() {
        return this.outDisplay;
    }

    public boolean isTouchpad() {
        return touchpad;
    }

    public void setTouchpad(boolean touchpad) {
        this.touchpad = touchpad;
    }

    public void setScaler(AbstractScaling scaler) {
        this.scaler = scaler;
    }

    /*
     * In external display mode, dialog will display in the touchpad canvas, so we accept it here
     */
    public void setProgressDialog(AlertDialog progressDialog) {
        this.progressDialog = progressDialog;
    }

    public AlertDialog getProgressDialog() {
        return progressDialog;
    }

//    public float getZoomLevelFactor() {
//        return connection.getZoomLevel() / 100;
//    }

    public FpsCounter getFpsCounter() {
        return fpsCounter;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        // ★★★ 核心1：返回true，告诉系统「我这个View是文本编辑器」，具备输入能力
        return true;
    }

    /**
     * Provide an InputConnection so the soft keyboard can bind to this SurfaceView.
     *
     * <p>Without this, {@link InputMethodManager#showSoftInput} may refuse to show
     * the keyboard on a plain SurfaceView. We deliberately don't override
     * commitText / sendKeyEvent / deleteSurroundingText — returning the default
     * {@link BaseInputConnection} makes the IME fall back to
     * {@link InputMethodManager#dispatchKeyEventFromInputMethod}, which routes
     * each character through {@link View#dispatchKeyEvent} →
     * {@link RemoteCanvasActivity#onKey} → keyboard.keyEvent. That single path
     * handles voice input (Gboard/Tencent/Sogou), hardware keyboard, and SSH CJK
     * candidate delivery via {@code keyCode=0 + getCharacters()} (handled in
     * {@link com.qihua.bVNC.input.RemoteSshKeyboard#processLocalKeyEvent}).
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // URI variation + NO_SUGGESTIONS tells IMEs (Gboard / Sogou / Baidu /
        // Tencent / SwiftKey) to deliver voice input via raw key events through
        // InputMethodManager.dispatchKeyEventFromInputMethod — same path as a
        // hardware keyboard. Each KeyEvent walks the View tree and reaches the
        // OnKeyListener installed on this Canvas (RemoteCanvasActivity.onKey),
        // which forwards to inputHandler.onKeyDown/Up → keyboard.keyEvent →
        // protocol keyboard.processLocalKeyEvent.
        //
        // Returning a vanilla BaseInputConnection(this, /*fullEditor=*/ false)
        // — without overriding commitText / sendKeyEvent / deleteSurroundingText —
        // is what the pre-libvterm codebase shipped and what the user has
        // verified works for voice input on RDP/VNC/NVStream/SPICE. The SSH
        // terminal also works through the same View→OnKeyListener path because
        // RemoteSshKeyboard.processLocalKeyEvent already handles
        // keyCode=0 + getCharacters() (the IME's final candidate delivery,
        // which View dispatch forwards as ACTION_DOWN with empty keyCode).
        //
        // A previous refactor (commit 80eae8de, "ssh connection works now, with
        // libvterm") introduced fullEditor=true + overridden commitText/sendKey
        // returning true. That bypassed the OnKeyListener path entirely,
        // breaking voice input and any IME that doesn't pre-decide between
        // commitText and sendKeyEvent the way we hardcoded.
        outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        return new BaseInputConnection(this, false);
    }

    public void drawTouchpadHint() {
        // Use the surface's own size: displayRect is the panel's natural
        // (portrait) size and is wrong after rotation, which shifted the
        // hint off-centre horizontally.
        int w = surfaceWidth > 0 ? surfaceWidth : getWidth();
        int h = surfaceHeight > 0 ? surfaceHeight : getHeight();
        if (w <= 0 && displayRect != null) {
            w = displayRect.width();
            h = displayRect.height();
        }
        drawTouchpadHint(w, h);
    }

    public void drawTouchpadHint(int width, int height) {
        Paint paint = new Paint();

        Typeface font = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        paint.setTypeface(font);
        paint.setTextSize(64);
        paint.setTextAlign(Paint.Align.CENTER);

        paint.setColor(0x33ffffff);

        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockHardwareCanvas();
            if (canvas != null) {
                synchronized (surfaceHolder) {
                    String text = getContext().getString(R.string.use_as_touchpad);
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    float y = height / 2f - (fm.ascent + fm.descent) / 2f;

                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    canvas.drawText(text, width / 2f, y, paint);
                }
            }
        } finally {
            if (canvas != null) {
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }

    /** True for the phone-side touchpad view created in external display mode. */
    public boolean isPhoneTouchpad() {
        return !outDisplay && touchpad;
    }

    /** True while the idle clock is drawn on the phone touchpad. */
    public boolean isClockShowing() {
        return clockShowing;
    }

    /** (Re)arm the idle clock; called on every touch/key on the phone. */
    public void noteUserInteraction() {
        idleHandler.removeCallbacks(showIdleClock);
        if (!outDisplay && touchpad) {
            idleHandler.postDelayed(showIdleClock, IDLE_CLOCK_TIMEOUT_MS);
        }
    }

    /** Dismiss the clock and restore the touchpad hint. Returns true if it was showing. */
    public boolean hideIdleClock() {
        if (!clockShowing) return false;
        clockShowing = false;
        idleHandler.removeCallbacks(clockTick);
        drawTouchpadHint();
        noteUserInteraction();
        return true;
    }

    private void drawClock() {
        int w = surfaceWidth, h = surfaceHeight;
        if (w <= 0 && displayRect != null) {
            w = displayRect.width();
            h = displayRect.height();
        }
        if (w <= 0) {
            w = getWidth();
            h = getHeight();
        }
        drawClock(w, h);
    }

    /** Calendar-style idle clock: huge HH:mm with the date above it. */
    private void drawClock(int width, int height) {
        if (width <= 0 || height <= 0) return;
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockHardwareCanvas();
            if (canvas == null) return;
            synchronized (surfaceHolder) {
                drawClockBackground(canvas, width, height);

                Date now = new Date();
                Locale locale = Locale.getDefault();
                String time = android.text.format.DateFormat.getTimeFormat(getContext()).format(now);
                String date = new java.text.SimpleDateFormat(
                        android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMEd"),
                        locale).format(now);
                // CLDR glues the weekday onto the date ("10月20日周一"); keep a gap.
                String dow = new java.text.SimpleDateFormat("E", locale).format(now);
                if (date.length() > dow.length() && date.endsWith(dow)
                        && !Character.isWhitespace(date.charAt(date.length() - dow.length() - 1))) {
                    date = date.substring(0, date.length() - dow.length()) + " " + dow;
                }

                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                paint.setTextAlign(Paint.Align.CENTER);
                float cx = width / 2f;

                float dateSize = Math.min(width, height) * 0.065f;
                paint.setColor(0xff7a7d84);
                paint.setTextSize(dateSize);
                float dateWidth = paint.measureText(date);
                if (dateWidth > width * 0.9f) {
                    paint.setTextSize(dateSize * width * 0.9f / dateWidth);
                }
                canvas.drawText(date, cx, height * 0.33f, paint);

                paint.setColor(0xff98999c);
                float timeSize = Math.min(width, height) * 0.30f;
                paint.setTextSize(timeSize);
                float timeWidth = paint.measureText(time);
                if (timeWidth > width * 0.9f) {
                    timeSize *= width * 0.9f / timeWidth;
                    paint.setTextSize(timeSize);
                }
                Paint.FontMetrics fm = paint.getFontMetrics();
                float baseline = height * 0.57f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(time, cx, baseline, paint);
            }
        } finally {
            if (canvas != null) {
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }

    /** Plain dark background behind the clock. */
    private void drawClockBackground(Canvas canvas, int width, int height) {
        canvas.drawColor(0xff05060a);
    }

    public long getLastDrawMs() {
        return drawWorker != null ? drawWorker.getLastDraw() : 0L;
    }

    /**
     * Which protocol is currently driving this canvas, or null before
     * initializeCanvas() runs. Prefer this over per-protocol booleans.
     */
    public ProtocolType getProtocolType() {
        return connInitializer != null ? connInitializer.getType() : null;
    }

    public void setDisplayRect(Rect displayRect) {
        this.displayRect = displayRect;
    }

    public Rect getDisplayRect() {
        return this.displayRect;
    }
}
