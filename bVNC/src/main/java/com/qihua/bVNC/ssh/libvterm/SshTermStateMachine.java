package com.qihua.bVNC.ssh.libvterm;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Java-side handle to a libvterm state machine. Holds a native
 * {@code VTerm*} + {@code VTermScreen*} pointer pair, exposes
 * methods that the renderer + keyboard use to feed input and read
 * the current grid.
 *
 * <p>Threading model: libvterm itself is not thread-safe. All
 * calls into native (write / setSize / getCell / etc.) must
 * happen on a single thread — we use the SSH-Paint HandlerThread
 * for paint-driven reads, and the SSH-Connect background thread
 * for input writes. Both touch different methods on different
 * schedules, but if you ever need them on the same thread, use
 * a mutex.
 *
 * <p>Damage tracking: libvterm's
 * {@code VTermScreenCallbacks.damage} fires every time a cell
 * becomes dirty. We record the dirty row in a fixed-size ring
 * buffer (the native handle has 256 slots). Java polls via
 * {@link #pollDirty()} / {@link #takeDirtyRows(int[])}.
 */
public final class SshTermStateMachine {
    private static final String TAG = "SshTermStateMachine";

    static {
        try {
            System.loadLibrary("vterm");
            System.loadLibrary("vterm_jni");
            Log.i(TAG, "libvterm + vterm_jni loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native lib load failed: " + e.getMessage());
            throw e;
        }
    }

    private long nativeHandle;
    private OutputStream outputSink;

    // --- Scrollback view state ---
    // scrollOffsetRows: how many lines back from the live bottom the
    //   viewport is parked. 0 = live (normal). Written by the input
    //   thread (scrollByPixels/scrollByLines/scrollToBottom), read by
    //   the SSH-Paint thread in VTermCanvasRenderer.render.
    // scrollFracPx: sub-row pixel remainder ∈ [0, charHeight) for smooth
    //   touchpad scrolling — the renderer shifts content down by this
    //   many pixels and draws one extra row so the transition is
    //   pixel-smooth instead of snapping row-by-row.
    // scrollAccumPx: input-thread-only accumulator feeding the two above.
    private volatile int scrollOffsetRows = 0;
    private volatile float scrollFracPx = 0f;
    private float scrollAccumPx = 0f;
    private volatile int charHeightForScroll = 1;

    /**
     * Incremented on every {@link #write}. The renderer uses it to tell a
     * pure viewport scroll (content unchanged) from a real content update,
     * which enables the fast bitmap-shift scroll path.
     */
    private volatile long contentSeq = 0;

    public long getContentSeq() {
        return contentSeq;
    }

    public SshTermStateMachine(int cols, int rows) {
        if (cols < 1 || rows < 1) {
            throw new IllegalArgumentException("cols/rows must be >= 1, got " + cols + "x" + rows);
        }
        nativeHandle = nativeCreate(cols, rows);
        if (nativeHandle == 0) {
            throw new OutOfMemoryError("vterm_create failed for " + cols + "x" + rows);
        }
    }

    public void setSize(int cols, int rows) {
        if (cols < 1 || rows < 1) {
            throw new IllegalArgumentException("cols/rows must be >= 1, got " + cols + "x" + rows);
        }
        synchronized (this) {
            if (nativeHandle == 0) return;
            nativeSetSize(nativeHandle, cols, rows);
        }
    }

    /**
     * Push the AppCompat day/night theme's default fg/bg to libvterm
     * so {@link #nativeGetCell} returns the right ARGB when a cell
     * uses the terminal's default colour. The values are stored as
     * globals in vterm_jni.c and apply to all subsequent
     * nativeGetCell calls (no per-handle state). The caller
     * (SshTerminalRenderer.applyTheme) re-invokes this whenever
     * the activity's night mode flips.
     */
    public void setDefaultColors(int fgArgb, int bgArgb) {
        if (nativeHandle == 0) return;
        nativeSetDefaultColors(nativeHandle, fgArgb, bgArgb);
    }

    /**
     * Push the AppCompat day/night theme's 16-colour ANSI palette to
     * libvterm so indexed cells (\e[31m red etc.) render in the new
     * theme's red/green/etc. instead of libvterm's hardcoded
     * Solarized fallback. Length must be exactly 16 — order is
     * black, red, green, yellow, blue, magenta, cyan, white,
     * brightBlack, brightRed, ..., brightWhite.
     */
    public void setPalette(int[] palette) {
        if (palette == null || palette.length < 16) return;
        nativeSetPalette(palette);
    }

    public int getCols() {
        synchronized (this) {
            if (nativeHandle == 0) return 0;
            return nativeGetCols(nativeHandle);
        }
    }

    public int getRows() {
        synchronized (this) {
            if (nativeHandle == 0) return 0;
            return nativeGetRows(nativeHandle);
        }
    }

    /** Feed SSH bytes into the terminal state machine. */
    public void write(byte[] data, int offset, int len) {
        if (len <= 0) return;
        byte[] out;
        synchronized (this) {
            if (nativeHandle == 0) return;
            nativeWrite(nativeHandle, data, offset, len);
            contentSeq++;
            // Drain any output libvterm generated in response (e.g. replies to
            // terminal queries) and forward it to the SSH channel's stdin.
            out = nativeDrainOutput(nativeHandle);
        }
        if (out != null && out.length > 0 && outputSink != null) {
            try {
                outputSink.write(out);
                outputSink.flush();
            } catch (IOException e) {
                Log.w(TAG, "write: failed to forward terminal output", e);
            }
        }
    }

    /**
     * Set the stream where terminal input bytes (generated by libvterm
     * from codepoints) are written. This is the SSH channel's stdin.
     */
    public void setOutputStream(OutputStream out) {
        this.outputSink = out;
    }

    /** Feed a single Unicode codepoint as if the user typed it (no mods). */
    public void writeInput(int codepoint) {
        writeInput(codepoint, MOD_NONE);
    }

    /**
     * Feed a Unicode codepoint with modifier state. libvterm applies CTRL
     * via {@code c &= 0x1f} (Ctrl+R → 0x12, Ctrl+C → 0x03) and ALT as an
     * ESC prefix. Use this for Ctrl+/Alt+letter combos — Android's
     * {@code getUnicodeChar()} does NOT map Ctrl+letter to control chars,
     * so the caller must strip CTRL/ALT from the meta used to fetch the
     * codepoint and pass them here instead.
     */
    public void writeInput(int codepoint, int mods) {
        byte[] out;
        synchronized (this) {
            if (nativeHandle == 0) return;
            out = nativeWriteInput(nativeHandle, codepoint, mods);
        }
        if (out != null && out.length > 0 && outputSink != null) {
            try {
                outputSink.write(out);
                outputSink.flush();
            } catch (IOException e) {
                Log.w(TAG, "writeInput: failed to write to SSH stdin", e);
            }
        }
    }

    /**
     * Feed a special key (arrows, Home/End, PageUp/Down, Insert/Delete,
     * F-keys, keypad). Goes through libvterm's {@code vterm_keyboard_key}
     * so the byte sequence matches the terminal's current cursor-key mode
     * (application vs normal) — required for zsh's mode-dependent key
     * bindings (e.g. right-arrow "accept suggestion") to fire. Use the
     * {@code KEY_*} / {@code MOD_*} constants below.
     */
    public void writeKey(int vtermKey, int mods) {
        byte[] out;
        synchronized (this) {
            if (nativeHandle == 0) return;
            out = nativeWriteKey(nativeHandle, vtermKey, mods);
        }
        if (out != null && out.length > 0 && outputSink != null) {
            try {
                outputSink.write(out);
                outputSink.flush();
            } catch (IOException e) {
                Log.w(TAG, "writeKey: failed to write to SSH stdin", e);
            }
        }
    }

    // --- VTermKey constants (mirror vterm_keycodes.h VTermKey enum) ---
    public static final int KEY_NONE      = 0;
    public static final int KEY_ENTER     = 1;
    public static final int KEY_TAB       = 2;
    public static final int KEY_BACKSPACE = 3;
    public static final int KEY_ESCAPE    = 4;
    public static final int KEY_UP        = 5;
    public static final int KEY_DOWN      = 6;
    public static final int KEY_LEFT      = 7;
    public static final int KEY_RIGHT     = 8;
    public static final int KEY_INS       = 9;
    public static final int KEY_DEL       = 10;
    public static final int KEY_HOME      = 11;
    public static final int KEY_END       = 12;
    public static final int KEY_PAGEUP    = 13;
    public static final int KEY_PAGEDOWN  = 14;
    public static final int KEY_FUNCTION_0 = 256;  // F1 = KEY_FUNCTION(1)

    /** F-key index (1..12) → VTERM_KEY_FUNCTION(n). */
    public static int keyFunction(int n) { return KEY_FUNCTION_0 + n; }

    // --- VTermModifier bitmask (mirror vterm_keycodes.h) ---
    public static final int MOD_NONE  = 0x00;
    public static final int MOD_SHIFT = 0x01;
    public static final int MOD_ALT   = 0x02;
    public static final int MOD_CTRL  = 0x04;

    /** True if any row is dirty since the last {@link #takeDirtyRows}. */
    public boolean pollDirty() {
        synchronized (this) {
            if (nativeHandle == 0) return false;
            return nativePollDirty(nativeHandle);
        }
    }

    /**
     * True while the app is inside a DEC 2026 synchronized update
     * (CSI ? 2026 h .. l). Painting mid-update shows the half-erased
     * middle of a TUI frame, which reads as jitter.
     */
    public boolean isSyncOutput() {
        synchronized (this) {
            if (nativeHandle == 0) return false;
            return nativeIsSyncOutput(nativeHandle);
        }
    }

    /**
     * True iff the remote app has turned on DECSET 1000 (click reporting).
     * Used by the input path to route wheel/click through {@link #sendMouse}
     * when the app asked for SGR/X10/RXVT mouse, and fall back to the
     * local scrollback offset otherwise.
     */
    public boolean isMouseModeEnabled() {
        synchronized (this) {
            if (nativeHandle == 0) return false;
            return nativeIsMouseEnabled(nativeHandle);
        }
    }

    /**
     * Forget the modes the previous remote program had asked for (mouse
     * reporting, synchronized output, hidden cursor). Called when the SSH
     * transport is replaced: the new remote is a different program that
     * has not asked for anything yet. Without this, mouse events keep
     * being encoded as mouse escape sequences and get printed as garbage
     * by the new shell, and the cursor stays invisible because the new
     * shell never sends ?25h.
     *
     * <p>Also parks the viewport back at the live bottom: while the view
     * is scrolled into the history the cursor is (by design) not painted,
     * so a stale scroll offset looks exactly like "the cursor vanished".
     */
    public void resetRemoteModes() {
        synchronized (this) {
            if (nativeHandle == 0) return;
            nativeResetRemoteModes(nativeHandle);
            scrollOffsetRows = 0;
            scrollAccumPx = 0f;
        }
    }

    /**
     * Forward a mouse event into libvterm. libvterm will emit the
     * terminal-protocol byte sequence (\e[<code;col+1;row+1M for SGR,
     * etc.) into the output buffer, which we drain and write to the SSH
     * channel's stdin — same pipeline as {@link #writeInput}.
     *
     * <p>{@code button}: 1=left, 2=middle, 3=right, 4=wheel-up,
     * 5=wheel-down. {@code row}/{@code col} are 0-based, matching the
     * values {@code RemoteSshPointer.pxToCell} returns.
     *
     * <p>Caller should guard with {@link #isMouseModeEnabled()} — the
     * native side does the same gate internally, but checking in Java
     * avoids the synchronized entry on every scroll tick.
     */
    public void sendMouse(int button, int row, int col, boolean pressed, int mods) {
        byte[] out;
        synchronized (this) {
            if (nativeHandle == 0) return;
            out = nativeSendMouseButton(nativeHandle, button, pressed, row, col, mods);
        }
        if (out != null && out.length > 0 && outputSink != null) {
            try {
                outputSink.write(out);
                outputSink.flush();
            } catch (IOException e) {
                Log.w(TAG, "sendMouse: failed to forward to SSH stdin", e);
            }
        }
    }

    /** Drain all dirty rows into {@code outRows}. Returns the number
     * of rows written. After this call {@link #pollDirty()} returns
     * false until libvterm reports new damage.
     */
    public int takeDirtyRows(int[] outRows) {
        synchronized (this) {
            if (nativeHandle == 0) return 0;
            return nativeTakeDirtyRows(nativeHandle, outRows);
        }
    }

    /** Read the cell at (row, col). */
    public TermCell getCell(int row, int col) {
        synchronized (this) {
            if (nativeHandle == 0) return null;
            return nativeGetCell(nativeHandle, row, col);
        }
    }

    // --- Scrollback view ---
    // The scrollback ring lives in native (vterm_jni.c, fed by libvterm's
    // sb_pushline callback). Java only holds the *view* onto it: how far
    // back the viewport is parked (scrollOffsetRows) plus a sub-row pixel
    // remainder for smooth scrolling (scrollFracPx). The renderer reads
    // these two to decide which virtual rows to paint and how to offset
    // them; RemoteSshPointer mutates them via scrollByPixels/scrollByLines.

    /**
     * Cell height in pixels — needed by {@link #scrollByPixels} to turn a
     * pixel delta into whole-row commits + a sub-row remainder. Set once
     * from {@link SshTerminalRenderer#open} (the renderer measures
     * charHeight from the font).
     */
    public void setCellHeight(int charHeight) {
        this.charHeightForScroll = Math.max(1, charHeight);
    }

    /**
     * Scroll the viewport by a signed pixel delta. Positive = toward
     * older history (scrollOffsetRows grows); negative = toward the live
     * bottom (shrinks). Whole rows are committed immediately to
     * {@link #scrollOffsetRows}; the sub-row remainder is kept in
     * {@link #scrollFracPx} so the renderer can paint a pixel-smooth
     * transition. Clamped to [0, scrollbackCount]; at either extreme the
     * remainder is zeroed so there's no half-row hang at the edges.
     *
     * <p>Called from the input thread (RemoteSshPointer.scrollUp/Down).
     * The volatile offset/frac fields are read lock-free by the paint
     * thread; the synchronized block guards the accumulator + the native
     * getScrollbackCount used for clamping.
     */
    public void scrollByPixels(float delta) {
        synchronized (this) {
            if (nativeHandle == 0) return;
            int ch = charHeightForScroll;
            // Cap per-event delta to ±2 rows. The touchpad/inertia `speed`
            // (RDP 255-complement, clamped [-255,255]) yields huge magnitudes
            // for fast flicks — |newY| up to 255, ×TOUCH_SCROLL_SCALE → a
            // single inertia tick can carry 700+ px and jump the viewport a
            // dozen rows (one tap of scrollDown snaps straight back to live).
            // Capping to ≤2 rows/event keeps scrolling controllable; gentle
            // swipes (delta < cap) are unaffected and keep their sub-row frac
            // for smoothness.
            float cap = 2f * ch;
            if (delta > cap) delta = cap;
            else if (delta < -cap) delta = -cap;
            int oldOffset = scrollOffsetRows;
            scrollAccumPx += delta;
            while (scrollAccumPx >= ch) {
                scrollAccumPx -= ch;
                scrollOffsetRows++;
            }
            while (scrollAccumPx < 0) {
                scrollAccumPx += ch;
                scrollOffsetRows--;
            }
            int max = nativeGetScrollbackCount(nativeHandle);
            if (scrollOffsetRows > max) {
                scrollOffsetRows = max;
                scrollAccumPx = 0f;
            } else if (scrollOffsetRows < 0) {
                scrollOffsetRows = 0;
                scrollAccumPx = 0f;
            }
            // When offset reaches an edge FROM THE OPPOSITE DIRECTION, snap
            // the sub-row remainder to zero. If the user scrolled DOWN and
            // landed at offset==0, a residual frac>0 shifts the live grid
            // down by frac px, which leaves stale content exposed at the
            // bottom on the next frame (when frac drops to 0).
            // Conversely, scrolling UP to the very top should show the
            // oldest line with no partial-row offset.
            if (scrollOffsetRows == 0 && oldOffset > 0 && scrollAccumPx > 0) {
                scrollAccumPx = 0f;
            }
            if (scrollOffsetRows == max && max > 0 && oldOffset < max) {
                scrollAccumPx = 0f;
            }
            scrollFracPx = scrollAccumPx;  // ∈ [0, ch) after the loops + clamp
        }
    }

    /** Scroll by whole lines (positive = older, negative = newer). */
    public void scrollByLines(int lines) {
        if (lines == 0) return;
        scrollByPixels(lines * (float) charHeightForScroll);
    }

    /** Snap the viewport back to the live bottom (offset = 0). */
    public void scrollToBottom() {
        synchronized (this) {
            scrollOffsetRows = 0;
            scrollAccumPx = 0f;
            scrollFracPx = 0f;
        }
    }

    /** Lines scrolled back from the live bottom (0 = live). */
    public int getScrollOffset() {
        return scrollOffsetRows;
    }

    /** Sub-row pixel remainder ∈ [0, charHeight) for smooth scrolling. */
    public float getScrollFracPx() {
        return scrollFracPx;
    }

    /** Number of history lines currently captured in the scrollback ring. */
    public int getScrollbackCount() {
        synchronized (this) {
            if (nativeHandle == 0) return 0;
            return nativeGetScrollbackCount(nativeHandle);
        }
    }

    /**
     * Read one cell from scrollback line {@code line} (0 = oldest),
     * column {@code col}. Returns null if out of range (renderer skips).
     */
    public TermCell getScrollbackCell(int line, int col) {
        synchronized (this) {
            if (nativeHandle == 0) return null;
            return nativeGetScrollbackCell(nativeHandle, line, col);
        }
    }

    /**
     * Bulk row fetch for the paint hot path: fills {@code out} with
     * {@link #CELL_STRIDE} ints per cell — codepoint, width, fg, bg,
     * attrs — and returns how many cells were written. One JNI crossing
     * per row instead of one per cell, and no per-cell Java allocation.
     */
    public int fillScreenRow(int row, int[] out) {
        synchronized (this) {
            if (nativeHandle == 0) return 0;
            return nativeFillScreenRow(nativeHandle, row, out);
        }
    }

    /** Scrollback twin of {@link #fillScreenRow}. */
    public int fillScrollbackRow(int line, int[] out) {
        synchronized (this) {
            if (nativeHandle == 0) return 0;
            return nativeFillScrollbackRow(nativeHandle, line, out);
        }
    }

    /** Ints per cell in the bulk row buffers. Mirrors CELL_STRIDE in vterm_jni.c. */
    public static final int CELL_STRIDE = 5;

    /** Read cursor position + visibility. */
    public CursorInfo getCursor() {
        synchronized (this) {
            if (nativeHandle == 0) return null;
            return nativeGetCursor(nativeHandle);
        }
    }

    public void destroy() {
        synchronized (this) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (nativeHandle != 0) {
                Log.w(TAG, "finalize: native handle leaked, destroying");
                destroy();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * One grid cell. Fields:
     * <ul>
     *   <li>{@code codepoint} — 0 means empty cell, otherwise the
     *       Unicode codepoint painted there.
     *   <li>{@code width} — 0/1/2. libvterm's reported width; 2 means
     *       a double-width CJK character (occupies two columns).
     *   <li>{@code fg} / {@code bg} — packed 0xRRGGBB (alpha
     *       implicit 0xFF). Default fg/bg are Solarized base0/base03
     *       if the cell has no explicit color.
     *   <li>{@code attrs} — bitmask: bit 0 = bold, bit 1 = italic,
     *       bit 2 = underline, bit 3 = reverse, bit 4 = blink.
     * </ul>
     */
    public static final class TermCell {
        public int codepoint;
        public int width;
        public int fg;
        public int bg;
        public int attrs;
    }

    public static final class CursorInfo {
        public int row;
        public int col;
        public boolean visible;
    }

    /**
     * Atomic snapshot of the scroll view state: offset (lines from live
     * bottom), sub-row pixel remainder, and total scrollback line count.
     * The renderer reads all three under one {@code synchronized} block
     * so it never sees a mismatched offset+frac+sbCount triple.
     */
    public static final class ScrollViewState {
        public final int offset;
        public final float frac;
        public final int sbCount;
        public ScrollViewState(int offset, float frac, int sbCount) {
            this.offset = offset;
            this.frac = frac;
            this.sbCount = sbCount;
        }
    }

    /** Read offset, frac, and sbCount atomically for the renderer. */
    public ScrollViewState getScrollViewState() {
        synchronized (this) {
            return new ScrollViewState(scrollOffsetRows, scrollFracPx,
                    nativeGetScrollbackCount(nativeHandle));
        }
    }

    private static native long  nativeCreate(int cols, int rows);
    private static native void  nativeSetSize(long h, int cols, int rows);
    private static native int   nativeGetCols(long h);
    private static native int   nativeGetRows(long h);
    private static native void  nativeWrite(long h, byte[] data, int off, int len);
    private static native byte[] nativeWriteInput(long h, int codepoint, int mods);
    private static native byte[] nativeWriteKey(long h, int key, int mods);
    private static native byte[] nativeDrainOutput(long h);
    private static native boolean nativeIsSyncOutput(long h);
    private static native boolean nativeIsMouseEnabled(long h);
    private static native void nativeResetRemoteModes(long h);
    private static native byte[] nativeSendMouseButton(long h, int button, boolean pressed, int row, int col, int mods);

    private static native boolean nativePollDirty(long h);
    private static native int   nativeTakeDirtyRows(long h, int[] outRows);
    private static native TermCell  nativeGetCell(long h, int row, int col);
    private static native int       nativeGetScrollbackCount(long h);
    private static native TermCell  nativeGetScrollbackCell(long h, int line, int col);
    private static native int       nativeFillScreenRow(long h, int row, int[] out);
    private static native int       nativeFillScrollbackRow(long h, int line, int[] out);
    private static native CursorInfo nativeGetCursor(long h);
    private static native void  nativeDestroy(long h);
    private static native void  nativeSetDefaultColors(long h, int fgArgb, int bgArgb);
    private static native void  nativeSetPalette(int[] palette);
}
