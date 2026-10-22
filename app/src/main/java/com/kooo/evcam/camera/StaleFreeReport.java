package com.kooo.evcam.camera;

/**
 * 相机服务报「空闲」的那一路，我们手里的设备还在出画面 —— 信谁？
 *
 * <p>两种真事都发生过：</p>
 * <ul>
 *   <li>2026-10-07（本项目实车）：相机服务报空闲 1.3 秒后最近一帧还算新鲜，就在死设备上重建会话、卡死。
 *       刚报的那几秒信相机服务。</li>
 *   <li>2026-10-10（dts88 实车 07:22）、2026-10-22（朋友的车 10:56）：车机交接相机 1 的同一毫秒，把我们
 *       正开着、正出画面的那一路也报成「空闲」，之后画面一直 30 fps。那一声是乱报；信画面。</li>
 * </ul>
 *
 * <p>规矩：「空闲」只在它刚报出来的 {@link #TRUST_MS} 内拦开录。死设备这几秒内就断帧，之后由稳帧判据
 * （{@link FeedSteadiness}）自己判；还稳稳出画面的就是活的。纯 Java，见 {@code StaleFreeReportTest}。</p>
 */
public final class StaleFreeReport {

    /** 「空闲」报出来之后信它多久。 */
    public static final long TRUST_MS = 2_000L;

    private StaleFreeReport() {
    }

    /**
     * 这一声「空闲」此刻还拦不拦开录。
     *
     * @param reportedFree 相机服务最近一次说的是「空闲」
     * @param reportAgeMs  那一声是多久以前的；不知道传 -1（照拦，和以前一样）
     */
    public static boolean blocks(boolean reportedFree, long reportAgeMs) {
        if (!reportedFree) {
            return false;
        }
        return reportAgeMs < 0 || reportAgeMs < TRUST_MS;
    }
}
