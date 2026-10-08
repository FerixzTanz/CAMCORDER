package com.kooo.evcam.camera;

import java.util.Deque;

/**
 * 相机连着出 ERROR_CAMERA_DEVICE 时等多久再重开（CAMCORDER 2026-10-07）。
 *
 * <p>一两次照原来的 8 秒；两分钟内第三次起放开 {@link SingleCamera#COOL_DOWN_MS}。
 * 实车两次卡死都是 8 秒一轮地重开、每轮又出错，一直好不了；退出应用、停了十几秒再开就好了。
 * 纯 Java，见 {@code CameraCoolDownTest}。</p>
 */
public final class CameraCoolDown {

    static final long NORMAL_DELAY_MS = 8_000L;
    /** 连着冷却时，一轮比一轮放开得久，最久放开这么久。 */
    static final long MAX_COOL_DOWN_MS = 120_000L;

    private CameraCoolDown() {
    }

    /**
     * 连着冷却的第 {@code round} 轮（从 1 数）该放开多久：15、30、60、120 秒，封顶。
     *
     * <p>一直没稳定出过画面，再勤地重开也只是不停地打扰已经卡住的相机服务
     * （zeekr-platform-notes §3.1.1「不要一直捶」）。2026-10-08 的现场是每 22 秒一轮，
     * 五分钟里十几轮，应用重启后又从头来 —— 因为设备「打开成功」就把轮数清零，而卡住时设备总是能打开的。
     * 现在只有稳稳地出了画面才清零（{@code SingleCamera}）。</p>
     */
    public static long coolDownForRound(int round) {
        int doublings = Math.min(Math.max(round, 1) - 1, 3);
        return Math.min(SingleCamera.COOL_DOWN_MS << doublings, MAX_COOL_DOWN_MS);
    }

    /**
     * 记下这一次出错，返回该等多久。
     *
     * @param errorTimes 之前出错的时刻（开机起算），会被修改：去掉窗口外的、加上这一次
     * @param nowMs      这一次出错的时刻
     */
    public static long delayAfterError(Deque<Long> errorTimes, long nowMs) {
        while (!errorTimes.isEmpty() && nowMs - errorTimes.peekFirst() > SingleCamera.COOL_DOWN_WINDOW_MS) {
            errorTimes.pollFirst();
        }
        errorTimes.addLast(nowMs);
        return errorTimes.size() >= SingleCamera.COOL_DOWN_AFTER_ERRORS ? SingleCamera.COOL_DOWN_MS : NORMAL_DELAY_MS;
    }
}
