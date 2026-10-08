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

    /** 连着冷却：15、30、60、120 秒，封顶；不再是每轮固定 15 秒。 */
    @Test
    public void eachCoolDownRoundLastsLongerUpToACap() {
        assertEquals(15_000L, CameraCoolDown.coolDownForRound(1));
        assertEquals(30_000L, CameraCoolDown.coolDownForRound(2));
        assertEquals(60_000L, CameraCoolDown.coolDownForRound(3));
        assertEquals(120_000L, CameraCoolDown.coolDownForRound(4));
        assertEquals("封顶，不再往上翻", 120_000L, CameraCoolDown.coolDownForRound(5));
        assertEquals(120_000L, CameraCoolDown.coolDownForRound(500));
        assertEquals("不会比第一轮还短", SingleCamera.COOL_DOWN_MS, CameraCoolDown.coolDownForRound(0));
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
