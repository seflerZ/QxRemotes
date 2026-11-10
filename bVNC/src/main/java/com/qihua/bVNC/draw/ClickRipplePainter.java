package com.qihua.bVNC.draw;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;

import java.util.ArrayList;

/**
 * 点击水波纹。画在 SurfaceView 自己的画布上(用与位图相同的坐标系),
 * 不用窗口层浮层 —— 浮层盖在 SurfaceView 上会打洞,导致整屏闪烁。
 */
public class ClickRipplePainter {

    private static final long DURATION_MS = 400L;
    private static final float MAX_RADIUS_DP = 26f;
    private static final float STROKE_DP = 1.8f;
    private static final int WHITE_ALPHA = 150;
    private static final int SHADOW_ALPHA = 60;
    /** 同一位置的重复触发去重窗口 */
    private static final long DEDUPE_MS = 110L;

    private static final class Ripple {
        final float x, y;
        final long startMs;

        Ripple(float x, float y, long startMs) {
            this.x = x;
            this.y = y;
            this.startMs = startMs;
        }
    }

    private final ArrayList<Ripple> ripples = new ArrayList<>(4);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float maxRadius;
    private final float strokeWidthPx;

    public ClickRipplePainter(float density) {
        maxRadius = MAX_RADIUS_DP * density;
        strokeWidthPx = STROKE_DP * density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0xFFFFFFFF);
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setColor(0xFF000000);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFFFFFFF);
    }

    /** framebuffer 坐标。任意线程调用。 */
    public synchronized void add(float x, float y) {
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        Ripple last = ripples.isEmpty() ? null : ripples.get(ripples.size() - 1);
        if (last != null && now - last.startMs < DEDUPE_MS
                && Math.abs(last.x - x) < maxRadius && Math.abs(last.y - y) < maxRadius) {
            return;
        }
        ripples.add(new Ripple(x, y, now));
        if (ripples.size() > 8) {
            ripples.remove(0);
        }
    }

    public synchronized boolean isEmpty() {
        prune(SystemClock.uptimeMillis());
        return ripples.isEmpty();
    }

    public synchronized void clear() {
        ripples.clear();
    }

    /**
     * 画在与位图相同的坐标系里(canvas 的 CTM 已经含缩放平移)。
     * scale 是 CTM 的缩放系数,除掉它让波纹在屏幕上大小恒定。
     */
    public synchronized void draw(Canvas canvas, float scale) {
        if (scale <= 0 || Float.isNaN(scale)) {
            scale = 1f;
        }
        long now = SystemClock.uptimeMillis();
        float r = maxRadius / scale;
        for (Ripple ripple : ripples) {
            float t = (now - ripple.startMs) / (float) DURATION_MS;
            if (t < 0f || t > 1f) continue;
            float ease = 1f - (float) Math.pow(1f - t, 2.2);
            float radius = r * (0.22f + 0.78f * ease);
            float fade = (1f - t) * (1f - t);

            shadowPaint.setStrokeWidth(strokeWidthPx * 2.2f / scale);
            shadowPaint.setAlpha((int) (SHADOW_ALPHA * fade));
            canvas.drawCircle(ripple.x, ripple.y, radius, shadowPaint);
            ringPaint.setStrokeWidth(strokeWidthPx / scale);
            ringPaint.setAlpha((int) (WHITE_ALPHA * fade));
            canvas.drawCircle(ripple.x, ripple.y, radius, ringPaint);
            dotPaint.setAlpha((int) (WHITE_ALPHA * fade * 0.8f));
            canvas.drawCircle(ripple.x, ripple.y, r * 0.16f * (1f - ease * 0.6f), dotPaint);
        }
        prune(now);
    }

    private void prune(long now) {
        ripples.removeIf(r -> now - r.startMs >= DURATION_MS);
    }
}
