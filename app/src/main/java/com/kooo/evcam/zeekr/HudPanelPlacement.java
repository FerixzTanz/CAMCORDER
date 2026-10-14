package com.kooo.evcam.zeekr;

/**
 * HUD 上侧视画面的位置和大小。
 *
 * <p>高度是 HUD 高度的百分比，宽度 = 高度 × 形状（宽 ÷ 高 ×100），最多一整个 HUD 宽；
 * 左边位置和上下位置都是「剩余空间里的百分比」，0 贴边、100 贴对面那一边。
 * 默认值（高度 74%、正方形、左边 6%、居中）是 1440×480 上 355×355 px、左边留 64 px 的样子。</p>
 *
 * <p>纯 Java，不碰 Android，见 {@code HudPanelPlacementTest}。</p>
 */
public final class HudPanelPlacement {

    /** 再小就看不清了（像素）。 */
    public static final int MIN_SIZE_PX = 48;

    private HudPanelPlacement() {
    }

    /** 窗口在 HUD 里的位置（像素）。 */
    public static final class Rect {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        Rect(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    public static Rect compute(int hudWidth, int hudHeight, int sizePercent, int shapePercent,
                               int leftPercent, int verticalPercent) {
        int height = Math.round(hudHeight * sizePercent / 100f);
        height = Math.max(Math.min(MIN_SIZE_PX, hudHeight), Math.min(height, hudHeight));
        int width = Math.round(height * shapePercent / 100f);
        width = Math.max(Math.min(MIN_SIZE_PX, hudWidth), Math.min(width, hudWidth));
        int left = Math.round(Math.max(0, hudWidth - width) * clampPercent(leftPercent) / 100f);
        int top = Math.round(Math.max(0, hudHeight - height) * clampPercent(verticalPercent) / 100f);
        return new Rect(left, top, width, height);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
