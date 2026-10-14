package com.kooo.evcam.zeekr;

import java.util.List;

/**
 * 车机上哪一块屏是 HUD。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>不是主屏，真实尺寸 1440×480：7X 的 HUD（诊断报告里是 {@code HDMI Screen 1440x480}，id 3）。</li>
 *   <li>没有这个尺寸的屏：退到 id 3，前提是它不是主屏、也不是 2560×536 那块（仪表盘）。
 *       宁可找不到、退回主屏弹窗，也不往仪表盘上画。</li>
 * </ul>
 *
 * <p>纯 Java，不碰 Android，见 {@code HudDisplayLocatorTest}。</p>
 */
public final class HudDisplayLocator {

    /** 找不到。 */
    public static final int NONE = -1;

    public static final int DEFAULT_DISPLAY_ID = 0;
    public static final int HUD_WIDTH = 1440;
    public static final int HUD_HEIGHT = 480;
    /** 按尺寸找不到时退到这一块（7X 上 HUD 一直是 3）。 */
    public static final int FALLBACK_DISPLAY_ID = 3;
    /** 仪表盘的尺寸：退到 id 3 时不能是它。 */
    private static final int CLUSTER_WIDTH = 2560;
    private static final int CLUSTER_HEIGHT = 536;

    private HudDisplayLocator() {
    }

    /** 一块屏：id 和真实尺寸（像素）。 */
    public static final class Candidate {
        public final int id;
        public final int width;
        public final int height;

        public Candidate(int id, int width, int height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * @return HUD 那块屏的 id，找不到是 {@link #NONE}
     */
    public static int find(List<Candidate> displays) {
        int bySize = findBySize(displays);
        if (bySize != NONE) {
            return bySize;
        }
        for (Candidate c : displays) {
            if (c.id == FALLBACK_DISPLAY_ID && !isCluster(c)) {
                return c.id;
            }
        }
        return NONE;
    }

    /** 只按尺寸找（诊断报告用它说明是按尺寸找到的还是退到 id 3 的）。 */
    public static int findBySize(List<Candidate> displays) {
        for (Candidate c : displays) {
            if (c.id != DEFAULT_DISPLAY_ID && c.width == HUD_WIDTH && c.height == HUD_HEIGHT) {
                return c.id;
            }
        }
        return NONE;
    }

    private static boolean isCluster(Candidate c) {
        return c.width == CLUSTER_WIDTH && c.height == CLUSTER_HEIGHT;
    }
}
