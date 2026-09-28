package com.kooo.evcam.zeekr;

/**
 * 按键模式：窗口按两条对角线切成四块三角，点哪一块就切到哪一路。
 *
 * <pre>
 *   ┌───────────────┐
 *   │ \    前     / │
 *   │   \       /   │
 *   │ 左  ╳ ── ╳  右 │   （两条对角线交在中心）
 *   │   /       \   │
 *   │ /    后     \ │
 *   └───────────────┘
 * </pre>
 *
 * <h3>为什么不是四个按钮</h3>
 *
 * <p>以前是一组菱形摆放的「前 后 左 右」按钮：碰一下窗口才出现，5 秒后消失，
 * 按钮固定 dp，窗口还得留出装下它们的最小尺寸。开车时要先碰一下、等它出现、
 * 再找准那一小块 —— 三步。切成四块之后整个窗口都是按钮，位置就是方向，
 * 一下就够；也不用给窗口设下限。</p>
 *
 * <h3>按窗口的比例切，不按正方形切</h3>
 *
 * <p>对角线连的是窗口的四个角。窗口拉扁时上下两块变矮变宽、左右两块变窄 ——
 * 这正好：四块的分界永远是看得见的那两条对角线，不用去想一个看不见的正方形。</p>
 */
public final class LaneTapZones {

    private LaneTapZones() {
    }

    /**
     * 点在了哪一块。
     *
     * @return {@link LaneCycle#FRONT} / {@link LaneCycle#REAR} / {@link LaneCycle#LEFT} /
     *         {@link LaneCycle#RIGHT}；窗口还没有尺寸时返回 -1
     */
    public static int laneAt(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return -1;
        }
        // 归一化之后两条对角线就是 v = u 和 v = 1 - u
        float u = x / width;
        float v = y / height;
        boolean belowMain = v > u;          // 在左上—右下那条线的下方
        boolean belowAnti = v > 1f - u;     // 在右上—左下那条线的下方
        if (!belowMain && !belowAnti) {
            return LaneCycle.FRONT;
        }
        if (belowMain && belowAnti) {
            return LaneCycle.REAR;
        }
        return belowMain ? LaneCycle.LEFT : LaneCycle.RIGHT;
    }

    /**
     * 这一路那一块三角的三个顶点，用来画点中时的闪光。
     *
     * @return {@code {x0, y0, x1, y1, x2, y2}}，窗口自己的坐标系
     */
    public static float[] triangle(int lane, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f;
        switch (lane) {
            case LaneCycle.REAR:
                return new float[]{0f, height, width, height, cx, cy};
            case LaneCycle.LEFT:
                return new float[]{0f, 0f, 0f, height, cx, cy};
            case LaneCycle.RIGHT:
                return new float[]{width, 0f, width, height, cx, cy};
            default:
                return new float[]{0f, 0f, width, 0f, cx, cy};
        }
    }
}
