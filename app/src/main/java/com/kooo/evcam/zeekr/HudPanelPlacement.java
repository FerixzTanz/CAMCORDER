package com.kooo.evcam.zeekr;

/**
 * HUD 上侧视画面的位置和大小。
 *
 * <p>高度是 HUD 高度的百分比，宽度 = 高度 × 形状（宽 ÷ 高 ×100），最多一整个 HUD 宽；
 * 左右位置是「离打灯那一边的距离」：左灯的画面离左边这么远，右灯的画面离右边这么远，
 * 都是剩余宽度里的百分比，0 贴边、50 居中、100 贴对面（用户 2026-10-14：「左灯左边、右灯右边」）；
 * 上下位置同样是剩余高度里的百分比，0 贴顶、100 贴底。
 * 默认值（高度 49%、宽 ÷ 高 194%、离边 11%、上下 21%）是 1440×480 上 456×235 px、离边留约 108 px 的样子
 * （项目拥有者 2026-10-15 的设置）。</p>
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

    /**
     * @param edgePercent 离打灯那一边多远（剩余宽度的百分比）
     * @param rightSide   右灯：从右边量；否则从左边量
     */
    public static Rect compute(int hudWidth, int hudHeight, int sizePercent, int shapePercent,
                               int edgePercent, int verticalPercent, boolean rightSide) {
        int height = Math.round(hudHeight * sizePercent / 100f);
        height = Math.max(Math.min(MIN_SIZE_PX, hudHeight), Math.min(height, hudHeight));
        int width = Math.round(height * shapePercent / 100f);
        width = Math.max(Math.min(MIN_SIZE_PX, hudWidth), Math.min(width, hudWidth));
        int fromEdge = Math.round(Math.max(0, hudWidth - width) * clampPercent(edgePercent) / 100f);
        int left = rightSide ? hudWidth - width - fromEdge : fromEdge;
        int top = Math.round(Math.max(0, hudHeight - height) * clampPercent(verticalPercent) / 100f);
        return new Rect(left, top, width, height);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
