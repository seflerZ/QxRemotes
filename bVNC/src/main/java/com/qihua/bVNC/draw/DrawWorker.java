package com.qihua.bVNC.draw;

import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.DrawTask;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Paints DrawTasks onto the canvas's SurfaceHolder from a dedicated thread,
 * paced by the display's vsync signal.
 *
 * Pacing rules:
 *   - at most one draw per vsync; only the newest pending frame is kept
 *   - pointer-only refreshes are capped to ~60 fps (every other vsync on a
 *     120 Hz panel); image frames are never dropped
 *
 * Drawing on data arrival instead of on vsync lets the post phase drift around
 * the vsync boundary. Consecutive frames then land in different presentation
 * slots (16.7 ms / 33.3 ms alternating), which reads as micro stutter - most
 * visible while the whole picture pans.
 *
 * Lifecycle: constructor starts the thread; call stop() to terminate cleanly.
 */
public class DrawWorker {
    private static final String TAG = "DrawWorker";
    private static final long MIN_POINTER_FRAME_INTERVAL_NANOS = 13_000_000L;

    private final RemoteCanvas canvas;
    private final HandlerThread thread;
    private final Handler handler;
    /** The newest frame waiting to be drawn. */
    private final AtomicReference<DrawTask> pending = new AtomicReference<>();
    /** True while a vsync callback is owed to us. */
    private final AtomicBoolean frameScheduled = new AtomicBoolean(false);
    private final boolean showFps;

    private Choreographer choreographer;
    private volatile boolean running = true;
    private volatile long lastDraw;
    private long lastFrameNanos;

    public DrawWorker(RemoteCanvas canvas) {
        this.canvas = canvas;
        this.showFps = Utils.querySharedPreferenceBoolean(canvas.getContext(),
                Constants.enableDebugInfo, false);

        this.thread = new HandlerThread("DrawWorker", Process.THREAD_PRIORITY_DISPLAY);
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public void count() {
        if (canvas.fpsCounter != null) {
            canvas.fpsCounter.count();
        }
    }

    /** Keep only the newest frame - anything older is already stale. */
    public void addTask(DrawTask task) {
        pending.set(task);
        scheduleFrame();
    }

    /** Stop the worker thread. Idempotent. */
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long getLastDraw() {
        return lastDraw;
    }

    public boolean isShowFps() {
        return canvas.fpsCounter != null;
    }

    private void scheduleFrame() {
        if (frameScheduled.compareAndSet(false, true)) {
            handler.post(this::postFrameCallback);
        }
    }

    private void postFrameCallback() {
        if (!running) {
            frameScheduled.set(false);
            return;
        }
        if (choreographer == null) {
            // Choreographer is per-Looper, so it must be fetched on the worker thread.
            choreographer = Choreographer.getInstance();
        }
        choreographer.postFrameCallback(frameCallback);
    }

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        if (!running) {
            return;
        }
        DrawTask task = pending.getAndSet(null);
        if (task == null) {
            finishFrame();
            return;
        }

        if (!task.isCount() && lastFrameNanos != 0
                && frameTimeNanos - lastFrameNanos < MIN_POINTER_FRAME_INTERVAL_NANOS) {
            pending.compareAndSet(null, task);
            postNextFrame();
            return;
        }

        draw(task);
        lastFrameNanos = frameTimeNanos;

        if (pending.get() != null) {
            postNextFrame();
        } else {
            finishFrame();
        }
    };

    private void postNextFrame() {
        choreographer.postFrameCallback(frameCallback);
    }

    /** Releases the "a vsync callback is owed" flag without losing a late task. */
    private void finishFrame() {
        frameScheduled.set(false);
        if (pending.get() != null) {
            scheduleFrame();
        }
    }

    private void draw(DrawTask task) {
        Canvas glCanvas = null;
        SurfaceHolder holder = canvas.surfaceHolder;
        try {
            if (canvas.fpsCounter != null) {
                if (task.isCount()) {
                    canvas.fpsCounter.count();
                }
                // Give the vsync wait some headroom before counting a late frame
                // as dropped; at 60 Hz a frame can legitimately wait ~17 ms.
                if (task.isCount() && System.currentTimeMillis() - task.getInTimeMs() > 25) {
                    canvas.fpsCounter.finish(task.getInTimeMs());
                    canvas.fpsCounter.frameDrop();
                }
            }

            if (holder == null || canvas.scaler == null || canvas.bitmapData == null) {
                return;
            }

            glCanvas = holder.lockHardwareCanvas();
            if (glCanvas == null) {
                return;
            }
            glCanvas.setMatrix(canvas.scaler.getMatrix());
            glCanvas.translate(-canvas.absoluteXPosition, -canvas.absoluteYPosition);
            glCanvas.drawColor(canvas.surfaceBackgroundColor, PorterDuff.Mode.SRC);

            canvas.bitmapData.drawable.draw(glCanvas);

            if (canvas.fpsCounter != null) {
                canvas.fpsCounter.finish(task.getInTimeMs());
                if (showFps) {
                    canvas.fpsCounter.drawFps(glCanvas);
                    canvas.fpsCounter.drawDebugMsg(glCanvas, task.getDebugMsg());
                }
            }

            lastDraw = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "draw failed", e);
        } finally {
            if (glCanvas != null) {
                try {
                    holder.unlockCanvasAndPost(glCanvas);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
