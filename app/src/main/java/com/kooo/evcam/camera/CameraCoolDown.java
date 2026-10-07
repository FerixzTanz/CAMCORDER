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

    private CameraCoolDown() {
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
