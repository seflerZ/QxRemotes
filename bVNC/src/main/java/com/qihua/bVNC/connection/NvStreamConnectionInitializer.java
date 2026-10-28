package com.qihua.bVNC.connection;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.input.RemoteNvStreamKeyboard;
import com.qihua.bVNC.input.RemoteNvStreamPointer;
import com.undatech.opaque.Connection;
import com.undatech.opaque.NvCommunicator;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * NVStream (Sunshine/Moonlight) lifecycle owner. Builds the
 * NvCommunicator, wires NVStream pointer/keyboard, builds the
 * PreferenceConfiguration (resolution, bitrate, FPS, HDR, audio, etc.),
 * and kicks off the network connect.
 *
 * The computer is looked up in the activity's ComputerDetails cache by
 * the SSH server UUID (reusing the SSH field for the machine's UUID is
 * a project convention).
 */
public class NvStreamConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "NvStreamConnectionInitializer";

    /* 码率基准:1920x1080 时的 kbps。实际按像素数等比折算。 */
    private static final int BITRATE_1080P_KBPS = 18000;
    private static final int BITRATE_1080P_KBPS_REMOTE = 10000;

    private final Connection conn;
    private final Context ctx;

    // Direct-rendering canvas state. The initializer owns the SurfaceView
    // lifecycle for NVStream: letterbox layout, frame-rate hint, first frame,
    // thumbnail and relative-mouse forwarding.
    private volatile RemoteCanvas canvas;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile int streamFps = 60;
    private volatile boolean firstFrameShown;
    private volatile Bitmap pendingScreenshot;
    private View.OnLayoutChangeListener parentLayoutListener;
    private TextView debugOverlay;
    private PreferenceConfiguration prefConfig;
    private SharedPreferences debugPrefs;
    private volatile boolean destroyed;

    // Toggling the pref while a session is live should show/hide the overlay
    // immediately instead of waiting for a reconnect.
    private final SharedPreferences.OnSharedPreferenceChangeListener debugPrefListener =
            (prefs, key) -> {
                if (key == null || !key.equals(Constants.enableDebugInfo) || canvas == null) {
                    return;
                }
                boolean enabled = prefs.getBoolean(key, false);
                if (prefConfig != null) {
                    prefConfig.enablePerfOverlay = enabled;
                }
                canvas.post(() -> {
                    if (enabled) {
                        createDebugOverlay();
                    } else {
                        removeDebugOverlay();
                    }
                });
            };

    private final Runnable debugOverlayTick = new Runnable() {
        @Override
        public void run() {
            if (debugOverlay == null || canvas == null) {
                return;
            }
            debugOverlay.setText(buildDebugText());
            canvas.handler.postDelayed(this, 500);
        }
    };

    public NvStreamConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.NVSTREAM;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_NVSTREAM;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "initialize: Initializing NvStream connection.");

        this.canvas = canvas;

        canvas.nvcomm = new NvCommunicator(canvas.activity, canvas, canvas.handler);
        canvas.rfbconn = canvas.nvcomm;

        canvas.pointer = new RemoteNvStreamPointer(canvas.nvcomm, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteNvStreamKeyboard(canvas.nvcomm, canvas, canvas.handler, App.debugLog);

        // in order to support fractional sensitivity, we use the integer divide 10 to make it a float.
        canvas.pointer.setSensitivity(Utils.querySharedPreferenceInt(ctx, Constants.touchpadCursorSpeed, 10) / 10);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Starting NvStream connection.");
        startWithSurface(canvas, canvas.surfaceHolder);
    }

    @Override
    public void onDisplayRectChanged(Display display) {
        // Rotation / fold / split screen: re-letterbox against the new size.
        // Called on the UI thread by RemoteCanvasActivity.
        applyLetterbox();
    }

    // ---------------------------------------------------------------- hooks

    @Override
    public boolean usesDirectVideoRendering() {
        return true;
    }

    @Override
    public boolean writesOwnScreenshot() {
        return true;
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {
        if (canvas == null) {
            return;
        }
        applyFrameRate(holder);
        if (canvas.nvcomm != null) {
            canvas.nvcomm.onSurfaceAvailable(holder);
        }
        canvas.post(this::applyLetterbox);
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        onSurfaceCreated(holder);
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {
        if (canvas != null && canvas.nvcomm != null) {
            // Decode into the offscreen surface until a new one arrives; the
            // stream itself stays connected.
            canvas.nvcomm.onSurfaceDestroyed();
        }
    }

    @Override
    public void onDrawableReallocated() {
        firstFrameShown = false;
    }

    @Override
    public float getMinimumScaleOverride() {
        if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0
                || videoWidth <= 0 || videoHeight <= 0) {
            return -1.f;
        }
        return Math.min((float) canvas.getWidth() / videoWidth,
                (float) canvas.getHeight() / videoHeight);
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (canvas == null || canvas.nvcomm == null
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        // Raw deltas let fullscreen games take the mouse and hide the host
        // cursor; absolute positions keep Windows showing it.
        int dx = Math.round(event.getX());
        int dy = Math.round(event.getY());
        if (dx != 0 || dy != 0) {
            canvas.nvcomm.sendRelativeMouseMove(dx, dy);
        }
        return true;
    }

    @Override
    public void onVideoFrameRendered() {
        if (!firstFrameShown) {
            firstFrameShown = true;
            if (canvas != null) {
                canvas.dismissConnectionProgress();
            }
        }
    }

    @Override
    public void scheduleConnectionProgressTimeout() {
        if (canvas == null || canvas.handler == null) {
            return;
        }
        canvas.handler.postDelayed(() -> {
            if (!firstFrameShown && canvas != null) {
                canvas.dismissConnectionProgress();
            }
        }, 5000);
    }

    @Override
    public synchronized void onBeforeDisconnect() {
        if (canvas == null || !canvas.maintainConnection) {
            return;
        }
        if (pendingScreenshot == null) {
            pendingScreenshot = captureFrame();
        }
    }

    @Override
    public synchronized void onDestroy() {
        destroyed = true;
        removeDebugOverlay();
        if (debugPrefs != null) {
            debugPrefs.unregisterOnSharedPreferenceChangeListener(debugPrefListener);
            debugPrefs = null;
        }
        if (canvas != null && parentLayoutListener != null
                && canvas.getParent() instanceof ViewGroup) {
            ((ViewGroup) canvas.getParent()).removeOnLayoutChangeListener(parentLayoutListener);
        }
        parentLayoutListener = null;

        Bitmap screenshot = pendingScreenshot;
        pendingScreenshot = null;
        if (screenshot != null) {
            if (canvas != null && canvas.connection != null) {
                String path = canvas.getContext().getFilesDir() + "/"
                        + canvas.connection.getScreenshotFilename();
                Utils.writeScreenshotToFile(screenshot, path, 720);
            }
            screenshot.recycle();
        }
    }

    // ------------------------------------------------------- implementation

    private void setVideoSize(int width, int height, int fps) {
        if (canvas == null) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        streamFps = fps > 0 ? fps : 60;

        // start() may run on a worker thread; everything touching views has to
        // happen on the UI thread.
        canvas.post(() -> {
            if (canvas.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) canvas.getParent();
                parent.setBackgroundColor(Color.BLACK);
                if (parentLayoutListener == null) {
                    parentLayoutListener = (v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) ->
                            canvas.post(this::applyLetterbox);
                    parent.addOnLayoutChangeListener(parentLayoutListener);
                }
            }
            applyLetterbox();
            createDebugOverlay();
        });
    }

    /**
     * Asks SurfaceFlinger to switch the panel to the stream's frame rate. On a
     * 120 Hz panel, 60 fps content otherwise gets quantised to 2/3 vsyncs per
     * frame and any release jitter shows up as micro stutter.
     */
    private void applyFrameRate(SurfaceHolder holder) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                holder.getSurface().setFrameRate(streamFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Resizes the SurfaceView to the letterbox rect for the stream aspect ratio.
     * Only the parent's size is used: deriving the stream size from the view
     * width would feed back into itself.
     */
    private void applyLetterbox() {
        if (canvas == null || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        if (!(canvas.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) canvas.getParent();
        int availableWidth = parent.getWidth();
        int availableHeight = parent.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }

        float scale = Math.min((float) availableWidth / videoWidth,
                (float) availableHeight / videoHeight);
        int newWidth = Math.max(1, Math.round(videoWidth * scale));
        int newHeight = Math.max(1, Math.round(videoHeight * scale));

        ViewGroup.LayoutParams lp = canvas.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(newWidth, newHeight, Gravity.CENTER);
        }
        if (lp.width == newWidth && lp.height == newHeight) {
            return;
        }
        lp.width = newWidth;
        lp.height = newHeight;
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
        }
        canvas.setLayoutParams(lp);
    }

    /**
     * Direct rendering bypasses DrawWorker, which used to be the only place the
     * on-screen debug text was painted. Overlay a TextView instead (the codec
     * owns the SurfaceView surface, so it cannot be drawn onto directly).
     */
    private void createDebugOverlay() {
        if (destroyed || debugOverlay != null || canvas == null) {
            return;
        }
        if (!Utils.querySharedPreferenceBoolean(ctx, Constants.enableDebugInfo, false)) {
            return;
        }
        if (!(canvas.getParent() instanceof FrameLayout)) {
            return;
        }
        FrameLayout parent = (FrameLayout) canvas.getParent();

        TextView view = new TextView(ctx);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(Color.WHITE);
        view.setShadowLayer(2, 1, 1, Color.BLACK);
        view.setBackgroundColor(0x66000000);
        view.setClickable(false);
        view.setFocusable(false);

        int pad = Math.round(6 * ctx.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = pad;
        lp.topMargin = pad;
        parent.addView(view, lp);

        debugOverlay = view;
        view.setText(buildDebugText());
        canvas.handler.postDelayed(debugOverlayTick, 500);
    }

    private void removeDebugOverlay() {
        if (canvas != null) {
            canvas.removeCallbacks(debugOverlayTick);
        }
        TextView view = debugOverlay;
        debugOverlay = null;
        if (view != null) {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(view);
            }
        }
    }

    private String buildDebugText() {
        StringBuilder sb = new StringBuilder();
        FpsCounter fps = canvas != null ? canvas.getFpsCounter() : null;
        if (fps != null) {
            sb.append("FPS ").append(fps.getLastFps())
                    .append("  MAX GAP ").append(fps.getWorstGapMs()).append("ms");
        }
        String msg = canvas != null && canvas.nvcomm != null ? canvas.nvcomm.getDebugMsg() : null;
        if (msg != null && !msg.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(msg);
        }
        return sb.toString();
    }

    /**
     * Copies the currently presented SurfaceView frame for the connection
     * thumbnail. Returns null when the surface is gone or the copy fails.
     * A timed-out PixelCopy is never recycled - it may still be writing.
     */
    private Bitmap captureFrame() {
        SurfaceHolder holder = canvas != null ? canvas.surfaceHolder : null;
        if (holder == null) {
            return null;
        }
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return null;
        }

        Rect frame = holder.getSurfaceFrame();
        int width = frame != null && frame.width() > 0 ? frame.width() : canvas.getWidth();
        int height = frame != null && frame.height() > 0 ? frame.height() : canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap bitmap;
        try {
            int targetWidth = Math.min(width, 720);
            int targetHeight = Math.max(1, Math.round((float) height * targetWidth / width));
            bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }

        HandlerThread thread = new HandlerThread("nv-screenshot");
        thread.start();
        CountDownLatch latch = new CountDownLatch(1);
        final int[] result = {PixelCopy.ERROR_UNKNOWN};
        try {
            PixelCopy.request(surface, bitmap,
                    r -> {
                        result[0] = r;
                        latch.countDown();
                    },
                    new Handler(thread.getLooper()));
            if (!latch.await(300, TimeUnit.MILLISECONDS)) {
                // Timed out: the copy may still be writing, do not recycle.
                return null;
            }
            if (result[0] != PixelCopy.SUCCESS) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        } catch (Throwable t) {
            return null;
        } finally {
            thread.quitSafely();
        }
    }

    private void startWithSurface(RemoteCanvas canvas, SurfaceHolder surfaceHolder) throws Exception {
        // We reuse the SSH server as the UUID of the computer
        String uuid = canvas.connection.getSshServer();
        ComputerDetails computerDetails = canvas.activity.getComputerDetail(uuid);
        if (computerDetails == null) {
            throw new IllegalStateException("computer not found, UUID: " + uuid);
        }

        String appName = canvas.connection.getUserName();
        int appId = Integer.parseInt(canvas.connection.getPassword());

        int remoteWidth = canvas.getRemoteWidth(canvas.displayRect.width(), canvas.displayRect.height());
        int remoteHeight = canvas.getRemoteHeight(canvas.displayRect.width(), canvas.displayRect.height());

        // defined here now, can be configured in later versions
        PreferenceConfiguration prefConfig = new PreferenceConfiguration();
        prefConfig.absoluteMouseMode = true;
        prefConfig.enableAudioFx = false;
        prefConfig.fps = 60;
        prefConfig.enableSops = true;
        prefConfig.bindAllUsb = true;
        prefConfig.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        // 用 Choreographer 按 vsync 匀速出帧。原来的 MIN_LATENCY 是"解出来就放",
        // 解码抖动 1:1 变成画面抖动(微卡帧)。代价是多一帧延迟。
        prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
        prefConfig.multiController = false;
        prefConfig.disableWarnings = true;
        prefConfig.enablePip = false;
        prefConfig.width = remoteWidth;
        prefConfig.height = remoteHeight;
        prefConfig.enableHdr = false;
        prefConfig.bitrate = bitrateForResolution(BITRATE_1080P_KBPS, remoteWidth, remoteHeight,
                prefConfig.fps);
        prefConfig.disableWarnings = true;
        prefConfig.incomingFrameQueueSize = 3;
        prefConfig.videoFormat = PreferenceConfiguration.FormatOption.AUTO;
        prefConfig.enableLatencyToast = false;
        prefConfig.enablePerfOverlay = Utils.querySharedPreferenceBoolean(canvas.activity, Constants.enableDebugInfo, false);
        this.prefConfig = prefConfig;
        debugPrefs = ctx.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
        debugPrefs.registerOnSharedPreferenceChangeListener(debugPrefListener);
//        prefConfig.videoFormat = PreferenceConfiguration.FormatOption.FORCE_H264;

        // reduce bitrate if on cellular connection
        if (!computerDetails.activeAddress.address.equals(computerDetails.localAddress.address)) {
            prefConfig.fps = 60;
            prefConfig.bitrate = bitrateForResolution(BITRATE_1080P_KBPS_REMOTE, remoteWidth,
                    remoteHeight, prefConfig.fps);
            prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
            prefConfig.incomingFrameQueueSize = 3;

            canvas.activity.runOnUiThread(() -> Toast.makeText(canvas.activity.getApplicationContext()
                    , R.string.cellular_connection_warning, Toast.LENGTH_SHORT).show());
        }

        Log.i(TAG, "stream " + remoteWidth + "x" + remoteHeight + " @" + prefConfig.fps
                + "fps -> bitrate " + prefConfig.bitrate + " kbps");

        // Letterbox the SurfaceView to the stream aspect before the decoder starts;
        // the codec renders straight into it now (no DrawWorker/PixelCopy).
        setVideoSize(remoteWidth, remoteHeight, prefConfig.fps);

        canvas.nvcomm.setConnectionParameters(computerDetails.activeAddress.address,
                computerDetails.activeAddress.port,
                computerDetails.httpsPort, remoteWidth, remoteHeight,
                canvas.activity.getUniqueId(), appName,
                appId, computerDetails.serverCert,
                prefConfig);

        canvas.nvcomm.connect(surfaceHolder);

        canvas.controller = new ControllerHandler(canvas.activity, canvas.nvcomm.getConnection(),
                canvas.activity, canvas.nvcomm.getPrefConfig());
    }

    /* 按分辨率折算码率,基准锚在 1920x1080。用像素数而不是宽度 —— 旧写法
       18000 * (remoteWidth / 1920) 是整数除法,宽度 <1920 时直接得 0。 */
    private static int bitrateForResolution(int baseKbps, int width, int height, int fps) {
        long bitrate = baseKbps * ((long) width * height) / (1920L * 1080L);

        if (fps > 60) {
            bitrate = (long) (bitrate * Math.sqrt(fps / 60.0)); /* 高帧率码率不线性涨 */
        } else if (fps > 0) {
            bitrate = bitrate * fps / 60;
        }

        return (int) Math.max(2000, Math.min(bitrate, 100000));
    }
}
