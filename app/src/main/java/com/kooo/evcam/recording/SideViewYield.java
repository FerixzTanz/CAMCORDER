package com.kooo.evcam.recording;

/**
 * 侧视弹窗、HUD 侧视给「录像正在开」让路，让多久。
 *
 * <p>让路是为了别在开录那两秒里再重建一次会话（CAMCORDER 2026-10-07）。开录正常两秒就成；
 * 录像要是一直「正在开」（2026-10-22 朋友的车：相机服务一句乱报的「空闲」让开录等了一整趟），
 * 侧视不能跟着空白一整趟 —— 让满 {@link #GRACE_MS} 就不让了，自己接相机。纯 Java，见 {@code SideViewYieldTest}。</p>
 */
public final class SideViewYield {

    /** 最多让路多久。 */
    public static final long GRACE_MS = 10_000L;

    private SideViewYield() {
    }

    /**
     * @param aboutToStart 录像在等开录（{@link RecordingCoordinator#isAboutToStart()}）
     * @param waitedMs     已经等了多久
     */
    public static boolean shouldWait(boolean aboutToStart, long waitedMs) {
        return aboutToStart && waitedMs < GRACE_MS;
    }
}
