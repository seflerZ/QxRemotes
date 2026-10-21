package com.qihua.bVNC.draw;

import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.util.Log;
import android.view.SurfaceHolder;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.DrawTask;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pulls DrawTasks off a queue and paints them onto the canvas's
 * SurfaceHolder on a dedicated thread.
 *
 * Throttling rules:
 *   - only the newest pending frame is kept (older ones are dropped)
 *   - run skips drawing if the previous draw was < 13 ms ago
 *   - frames older than 16 ms count as drops (when isCount() is true)
 *
 * The old implementation used an unbounded queue and coalesced against the
 * *head* of the queue, so frames piled up and were drawn in clumps - that is
 * what the "micro stutter" was. Keeping only the newest frame makes the
 * pipeline self-throttling: if we fall behind, the stale frames are simply
 * replaced by the next one that arrives.
 *
 * Lifecycle: constructor starts the thread; call stop() to terminate
 * cleanly.
 */
public class DrawWorker implements Runnable {
    private static final String TAG = "DrawWorker";

    private final RemoteCanvas canvas;
    private final Thread thread;
    /** The newest frame waiting to be drawn. */
    private final AtomicReference<DrawTask> pending = new AtomicReference<>();
    /** Wakes up the draw thread when a new frame shows up. */
    private final Semaphore signal = new Semaphore(0);
    private final boolean showFps;

    private volatile boolean running = true;
    private volatile long lastDraw;

    public DrawWorker(RemoteCanvas canvas) {
        this.canvas = canvas;
        this.showFps = Utils.querySharedPreferenceBoolean(canvas.getContext(),
                Constants.enableDebugInfo, false);

        this.thread = new Thread(this, "DrawWorker");
        this.thread.start();
    }

    public void count() {
        if (canvas.fpsCounter != null) {
            canvas.fpsCounter.count();
        }
    }

    /** Keep only the newest frame - anything older is already stale. */
    public void addTask(DrawTask task) {
        pending.set(task);
        signal.release();
    }

    /** Stop the worker thread. Idempotent. */
    public void stop() {
        running = false;
        signal.release();
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

    @Override
    public void run() {
        // Display priority, not URGENT_AUDIO: this thread used to run at -16 and
        // outranked the audio decode threads (native pthreads at nice 0), so a
        // full-frame blit could starve audio and cause audible dropouts.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);

        while (running) {
            Canvas glCanvas = null;
            SurfaceHolder holder = canvas.surfaceHolder;
            try {
                signal.acquire();
                // Merge the wakeups of several frames into one draw
                signal.drainPermits();

                DrawTask task = pending.getAndSet(null);
                if (task == null || !running) {
                    continue;
                }

                if (isShowFps() && task.isCount()) {
                    canvas.fpsCounter.count();
                }

                // prevent the pointer refresh event goes too fast
                if (task.getInTimeMs() - lastDraw < 13) {
                    continue;
                }

                // when isCount is true, the update comes from a real image update
                if (System.currentTimeMillis() - task.getInTimeMs() > 16 && task.isCount()) {
                    // drop frame, lagging
                    canvas.fpsCounter.finish(task.getInTimeMs());
                    canvas.fpsCounter.frameDrop();
                }

                if (holder == null || canvas.scaler == null || canvas.bitmapData == null) {
                    continue;
                }

                glCanvas = holder.lockHardwareCanvas();
                if (glCanvas == null) {
                    continue;
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
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
}
