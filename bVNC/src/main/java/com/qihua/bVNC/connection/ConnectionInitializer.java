package com.qihua.bVNC.connection;

import android.content.Context;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.Connection;

/**
 * Strategy for one remote-desktop protocol's lifecycle. Owns everything
 * protocol-specific that RemoteCanvas used to inline: building the
 * communicator, pointer, keyboard, decoder, and starting the network.
 *
 * Lifecycle:
 *   initialize()  -- phase 1, on UI thread before cThread starts.
 *                    Build communicators/pointer/keyboard/decoder.
 *   start()       -- phase 2, on the cThread. Open the socket, do the
 *                    handshake, kick off network reads.
 *
 * Adding a 6th protocol = one new class + one factory entry.
 *
 * Per-protocol lifecycle hooks (surface, teardown, resize, rendering mode)
 * are default no-ops on this class; only the initializers that need them
 * override. RemoteCanvas calls them unconditionally instead of type-checking
 * for a concrete initializer.
 */
public abstract class ConnectionInitializer {
    /** Which protocol this initializer drives. */
    public abstract ProtocolType getType();

    /** True if this initializer handles the given connection. */
    public abstract boolean supports(Connection conn, Context ctx);

    /** Phase 1: build communicators, pointer, keyboard, decoder. Mutates canvas. */
    public abstract void initialize(RemoteCanvas canvas) throws Exception;

    /** Phase 2: actually start the network. Mutates canvas; runs in cThread. */
    public abstract void start(RemoteCanvas canvas) throws Exception;

    public abstract void onDisplayRectChanged(Display display);

    /**
     * Soft keyboard visibility changed. Default is a no-op — RDP / VNC /
     * SPICE / NVStream already have protocol-agnostic image-pan handling
     * in {@code RemoteCanvasActivity}. SSH overrides this to grow the
     * mbitmap by the IME height on IME-up (creating a transparent
     * backup region above the terminal rows) and shrink back on
     * IME-down — see {@code SshConnectionInitializer.onSoftKeyboardChanged}.
     *
     * @param isShow         true if the IME just became visible
     * @param availableHeight the bottom of
     *                        {@code getWindowVisibleDisplayFrame()} in
     *                        pixels — the post-IME window height in
     *                        window coordinates
     */
    public void onSoftKeyboardChanged(boolean isShow, int availableHeight) {
    }

    /**
     * True when the protocol renders video straight into the SurfaceView
     * instead of going through the bitmap/DrawWorker path. Disables zoom/pan
     * and the local cursor repaint, and makes RemoteCanvas expect the
     * initializer to handle letterboxing itself.
     */
    public boolean usesDirectVideoRendering() {
        return false;
    }

    // Surface lifecycle. Called for the canvas that owns this initializer.
    public void onSurfaceCreated(SurfaceHolder holder) {
    }

    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    public void onSurfaceDestroyed(SurfaceHolder holder) {
    }

    /** Called after the bitmap/drawable has been (re)allocated. */
    public void onDrawableReallocated() {
    }

    /** >= 0 overrides RemoteCanvas.getMinimumScale(); negative means "no override". */
    public float getMinimumScaleOverride() {
        return -1.f;
    }

    /** Return true if the captured-pointer event was consumed by the protocol. */
    public boolean onCapturedPointerEvent(MotionEvent event) {
        return false;
    }

    /** A frame actually reached the display (direct-render mode). */
    public void onVideoFrameRendered() {
    }

    /** Arm a fallback that closes the connection progress dialog. */
    public void scheduleConnectionProgressTimeout() {
    }

    /** Before rfbconn closes; the last point where the surface is still alive. */
    public void onBeforeDisconnect() {
    }

    /** Teardown; called from RemoteCanvas.onDestroy(). Must be idempotent. */
    public void onDestroy() {
    }

    /** True when this initializer writes the connection thumbnail itself. */
    public boolean writesOwnScreenshot() {
        return false;
    }
}
