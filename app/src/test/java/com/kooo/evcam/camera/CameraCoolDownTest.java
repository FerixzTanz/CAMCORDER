package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;

import org.junit.Test;

/** {@link CameraCoolDown}：一两次 8 秒，两分钟内第三次起 15 秒，窗口外的不算。 */
public class CameraCoolDownTest {

    @Test
    public void firstTwoErrorsUseTheNormalDelay() {
        ArrayDeque<Long> times = new ArrayDeque<>();
        assertEquals(CameraCoolDown.NORMAL_DELAY_MS, CameraCoolDown.delayAfterError(times, 1_000L));
        assertEquals(CameraCoolDown.NORMAL_DELAY_MS, CameraCoolDown.delayAfterError(times, 21_000L));
    }

    @Test
    public void thirdErrorWithinTwoMinutesCoolsDown() {
        ArrayDeque<Long> times = new ArrayDeque<>();
        CameraCoolDown.delayAfterError(times, 1_000L);
        CameraCoolDown.delayAfterError(times, 21_000L);
        assertEquals(SingleCamera.COOL_DOWN_MS, CameraCoolDown.delayAfterError(times, 41_000L));
        assertEquals(SingleCamera.COOL_DOWN_MS, CameraCoolDown.delayAfterError(times, 70_000L));
    }

    @Test
    public void oldErrorsDropOutOfTheWindow() {
        ArrayDeque<Long> times = new ArrayDeque<>();
        CameraCoolDown.delayAfterError(times, 1_000L);
        CameraCoolDown.delayAfterError(times, 2_000L);
        // 三分钟后再出错：前两次已经不在两分钟窗口里
        assertEquals(CameraCoolDown.NORMAL_DELAY_MS, CameraCoolDown.delayAfterError(times, 182_000L));
        assertEquals(1, times.size());
    }
}
