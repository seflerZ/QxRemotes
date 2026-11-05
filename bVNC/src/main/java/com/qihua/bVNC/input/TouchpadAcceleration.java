package com.qihua.bVNC.input;

/**
 * 触控板的"速度 → 倍率"计算,内部维护只增不减的累加位移。
 *
 * <p>{@link PointerAccelerationHelper} 靠 (本次值-上次值)/dt 测速,前提是喂进去的值
 * 单调累加。触控板的两条路径(拖光标 / 双指滚动)每拍都会把本拍位移清零,直接喂会测成
 * 【相邻两拍位移之差】(加加速度)而不是速度:匀速滑动加速永远上不去,手指减速时差值
 * 还会变号。把"累加 + 测速 + 归零"包在这里,免得各调用点漏掉归零造出巨大假位移。
 */
class TouchpadAcceleration {
    private final PointerAccelerationHelper helper = new PointerAccelerationHelper();
    private float accX, accY;

    /**
     * 累加本拍位移,返回按当前速度算出的倍率。
     *
     * @param dx/dy  本拍位移(方向跟 cumulated 一致)
     * @param base   基础倍率
     * @param factor 速度系数(倍率 = base + 速度(px/ms) × factor)
     * @param max    倍率上限
     */
    float scale(float dx, float dy, float base, float factor, float max) {
        accX += dx;
        accY += dy;
        return helper.calculateAccelerationMultiplier(
                System.currentTimeMillis(), accX, accY, base, factor, max);
    }

    /** 最近一次测得的 X 方向带符号速度(px/ms),方向跟累加的位移一致 */
    float signedSpeedX() {
        return helper.getLastSignedSpeedX();
    }

    /** 最近一次测得的 Y 方向带符号速度(px/ms),方向跟累加的位移一致 */
    float signedSpeedY() {
        return helper.getLastSignedSpeedY();
    }

    /** 新手势:累加器与加速器必须一起归零,否则差分会造出巨大的假位移 */
    void reset() {
        accX = 0;
        accY = 0;
        helper.reset();
    }
}
