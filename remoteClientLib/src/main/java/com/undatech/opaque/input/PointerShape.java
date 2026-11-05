package com.undatech.opaque.input;

import android.util.Log;

/**
 * 远端光标形状分类。RDP 服务器每次更换光标形状都会下发完整位图
 * (MS-RDPBCGR Pointer Cache Update),客户端据此判断指针是否落在可输入文本上。
 */
public final class PointerShape {
    private static final String TAG = "PointerShape";

    /** 形状未知(系统默认指针 / 位图无法解析) */
    public static final int UNKNOWN = 0;
    /** 文本输入光标(I-beam) */
    public static final int TEXT = 1;
    /** 其它形状(箭头 / 手型 / 十字 / 缩放箭头 ...) */
    public static final int OTHER = 2;

    private PointerShape() {
    }

    /**
     * @param argb     ARGB_8888,大端字节序(A,R,G,B),与 Bitmap.Config.ARGB_8888 一致
     * @param hotspotX 光标热点在位图内的 x 坐标
     * @param hotspotY 光标热点在位图内的 y 坐标
     */
    public static int classify(byte[] argb, int width, int height, int hotspotX, int hotspotY) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height * 4) {
            return UNKNOWN;
        }

        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width * 4;
            for (int x = 0; x < width; x++) {
                if ((argb[row + x * 4] & 0xFF) == 0) continue;   // alpha=0 → 透明
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return UNKNOWN;

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        int[] rowWidths = rowWidths(argb, width, height);

        int widest = 0;
        for (int y = minY; y <= maxY; y++) widest = Math.max(widest, rowWidths[y]);
        int edge = Math.max(rowWidths[minY], rowWidths[maxY]);

        Log.d(TAG, "classify: bbox=" + bw + "x" + bh + " hotspot=" + hotspotX + "," + hotspotY
                + " edge=" + edge + " widest=" + widest);

        // I-beam 是竖直细长的一条;箭头/手型/十字都接近方形
        if (bh < 8 || bw * 10 > bh * 18) return OTHER;

        // 热点落在字形水平中部(箭头的热点在左上角,手型在指尖)
        if (Math.abs(hotspotX - (minX + bw / 2)) > Math.max(1, bw / 4)) return OTHER;

        // 最宽的一行要贴着上下边缘:I-beam 的衬线在两端,箭头的最宽处在中部
        int margin = Math.max(1, bh / 4);
        boolean widestAtEdge = false;
        for (int y = minY; y <= maxY; y++) {
            if (rowWidths[y] < widest) continue;
            if (y <= minY + margin || y >= maxY - margin) widestAtEdge = true;
        }
        if (!widestAtEdge) return OTHER;

        // 竖向缩放箭头上下是箭头尖不是横杠,用首末行的宽度区分
        if (bw > 3 && edge * 10 < bw * 6) return OTHER;

        return TEXT;
    }

    private static int[] rowWidths(byte[] argb, int width, int height) {
        int[] widths = new int[height];
        for (int y = 0; y < height; y++) {
            int row = y * width * 4;
            int n = 0;
            for (int x = 0; x < width; x++) {
                if ((argb[row + x * 4] & 0xFF) != 0) n++;
            }
            widths[y] = n;
        }
        return widths;
    }
}
