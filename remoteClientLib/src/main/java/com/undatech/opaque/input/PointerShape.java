package com.undatech.opaque.input;

import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 远端光标形状分类。RDP 服务器每次更换光标形状都会下发完整位图
 * (MS-RDPBCGR Pointer Cache Update),客户端据此判断指针是否落在可输入文本上。
 * <p>
 * 判据只认 I-beam 的形态特征(细高竖杆 + 水平居中的热点 + 贯穿高度的连续竖线),
 * 十字准星/斜向缩放/手型/三角箭头一律排除。
 */
public final class PointerShape {

    private static final String TAG = "PointerShape";

    /** 形状未知(系统默认指针 / 位图无法解析) */
    public static final int UNKNOWN = 0;
    /** 文本输入光标(I-beam) */
    public static final int TEXT = 1;
    /** 其它形状(箭头 / 手型 / 十字 / 缩放箭头 ...) */
    public static final int OTHER = 2;

    /** I-beam 的高宽比上限:bw/bh <= 0.75。十字准星/斜向缩放接近 1:1,直接排除。 */
    private static final int MAX_W_H_RATIO_NUM = 3;
    private static final int MAX_W_H_RATIO_DEN = 4;

    private PointerShape() {
    }

    // 形状位图 → 分类结果。同一形状只算一次,顺便拿到一个稳定的形状指纹用于日志。
    private static final int CACHE_MAX = 64;
    private static final Map<Integer, int[]> cache =
            new LinkedHashMap<Integer, int[]>(CACHE_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /**
     * @param argb     ARGB_8888,大端字节序(A,R,G,B),与 Bitmap.Config.ARGB_8888 一致
     * @param width    位图宽
     * @param height   位图高
     * @param hotspotX 光标热点在位图内的 x 坐标
     * @param hotspotY 光标热点在位图内的 y 坐标
     */
    public static int classify(byte[] argb, int width, int height, int hotspotX, int hotspotY) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height * 4) {
            return UNKNOWN;
        }

        int hash = fingerprint(argb, width, height, hotspotX, hotspotY);
        synchronized (cache) {
            int[] cached = cache.get(hash);
            if (cached != null) {
                return cached[0];
            }
        }

        int result = classifySlow(argb, width, height, hotspotX, hotspotY, hash);

        synchronized (cache) {
            cache.put(hash, new int[]{result});
        }
        return result;
    }

    private static int classifySlow(byte[] argb, int width, int height,
                                    int hotspotX, int hotspotY, int hash) {
        int minX = width, minY = height, maxX = -1, maxY = -1;
        int[] rowWidths = new int[height];
        int filledRows = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width * 4;
            int n = 0;
            for (int x = 0; x < width; x++) {
                if ((argb[row + x * 4] & 0xFF) == 0) continue;   // alpha=0 → 透明
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                n++;
            }
            rowWidths[y] = n;
            if (n > 0) filledRows++;
        }
        if (maxX < 0) return UNKNOWN;

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;

        // 中段(上下各留 20%)的宽度统计:I-beam 的竖杆在这里宽度基本恒定
        int lo = minY + bh / 5;
        int hi = maxY - bh / 5;
        int midMin = Integer.MAX_VALUE, midMax = 0, midRows = 0;
        for (int y = lo; y <= hi; y++) {
            int w = rowWidths[y];
            if (w == 0) continue;
            midMin = Math.min(midMin, w);
            midMax = Math.max(midMax, w);
            midRows++;
        }
        int edge = Math.max(rowWidths[minY], rowWidths[maxY]);

        Log.d(TAG, "classify: fp=" + Integer.toHexString(hash)
                + " bbox=" + bw + "x" + bh
                + " hotspot=" + hotspotX + "," + hotspotY
                + " mid=" + midMin + ".." + midMax
                + " edge=" + edge + " filled=" + filledRows + "/" + bh);

        // 太矮的不可能是 I-beam
        if (bh < 8) return OTHER;

        // 必须是明显高瘦的竖条。十字准星/斜向缩放/手型的 bbox 接近 1:1,
        // 这里直接排除 —— 旧判据只要求 bw*10 <= bh*18(0.75 之上都放过),
        // 结果十字准星(1:1,每行宽度一致、热点居中)会被判成 I-beam。
        if (bw * MAX_W_H_RATIO_DEN > bh * MAX_W_H_RATIO_NUM) {
            Log.d(TAG, "classify: 非高瘦(bw/bh=" + (bw * 1.0f / bh) + "),判为 OTHER");
            return OTHER;
        }

        // 热点落在字形水平中部(箭头的热点在左上角,手型在指尖)
        if (Math.abs(hotspotX - (minX + bw / 2)) > Math.max(1, bw / 4)) return OTHER;

        // 中段必须有连续竖杆:宽度不能是三角形渐变(上下箭头、忙碌光标)
        if (midRows == 0) return OTHER;
        if (midMax - midMin > midMax / 2) {
            Log.d(TAG, "classify: 中段宽度起伏大(" + midMin + ".." + midMax + "),判为 OTHER");
            return OTHER;
        }

        // 端部不能比竖杆还窄(I-beam 的两端有横杠或至少与竖杆同宽)
        if (edge < midMin) return OTHER;

        // 竖杆要贯穿大部分高度,不能只有一小段
        if (filledRows * 5 < bh * 4) return OTHER;

        return TEXT;
    }

    /** 形状指纹:位图内容 + 尺寸 + 热点。同一个形状 → 同一个指纹。 */
    private static int fingerprint(byte[] argb, int width, int height, int hotspotX, int hotspotY) {
        int h = width * 31 + height * 17 + hotspotX * 7 + hotspotY;
        // 全部 alpha 字节都算进去:步长采样会撞指纹,而缓存是 static 永不失效,
        // 一次碰撞就会永久把形状认错(比如把箭头当成 I-beam)。
        for (int i = 3; i < argb.length; i += 4) {
            h = h * 31 + (argb[i] & 0xFF);
        }
        return h;
    }
}
