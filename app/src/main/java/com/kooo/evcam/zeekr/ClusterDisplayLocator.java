package com.kooo.evcam.zeekr;

import java.util.List;

/**
 * 车机上哪一块屏是仪表盘（主驾前面那块长条屏）。
 *
 * <p>只按尺寸认：不是主屏，真实尺寸 2560×536（7X 的仪表盘，见 {@link HudDisplayLocator} 里排除的那一块）。
 * 找不到就是找不到，不猜 id：宁可测试说「没找到」，也不往别的屏上画。</p>
 *
 * <p>纯 Java，不碰 Android，见 {@code ClusterDisplayLocatorTest}。</p>
 */
public final class ClusterDisplayLocator {

    /** 找不到。 */
    public static final int NONE = -1;

    public static final int CLUSTER_WIDTH = 2560;
    public static final int CLUSTER_HEIGHT = 536;

    private ClusterDisplayLocator() {
    }

    /** @return 仪表盘那块屏的 id，找不到是 {@link #NONE} */
    public static int find(List<HudDisplayLocator.Candidate> displays) {
        for (HudDisplayLocator.Candidate c : displays) {
            if (c.id != HudDisplayLocator.DEFAULT_DISPLAY_ID
                    && c.width == CLUSTER_WIDTH && c.height == CLUSTER_HEIGHT) {
                return c.id;
            }
        }
        return NONE;
    }
}
