package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link FeedSteadiness}：健康的 30 fps 算稳，爬着的 0.8 fps、没几帧、停了都不算。 */
public class FeedSteadinessTest {

    private static void feed(FeedSteadiness s, long fromMs, long untilMs, long everyMs) {
        for (long t = fromMs; t <= untilMs; t += everyMs) {
            s.onFrame(t);
        }
    }

    @Test
    public void aHealthyThirtyFpsFeedIsSteadyAfterAFewFrames() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 1_000L, 1_100L, 33L);   // 4 帧：还不够
        assertFalse(s.steady(1_100L));
        feed(s, 1_132L, 1_200L, 33L);
        assertTrue(s.steady(1_200L));
    }

    /** 2026-10-08 导出：0.8 fps，一帧都有、但不稳。旧的「两秒内有一帧」在这里答「有」。 */
    @Test
    public void aCrawlingFeedIsNotSteadyEvenThoughAFrameJustArrived() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 0L, 60_000L, 1_250L);   // 0.8 fps
        long now = 60_000L;
        assertTrue("最近一帧刚到", now - 60_000L < 2_000L);
        assertFalse(s.steady(now));
    }

    @Test
    public void aFeedThatStopsStopsBeingSteadyAfterTheWindow() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 0L, 1_000L, 33L);       // 最后一帧 990，最近六帧里最老的是 825
        assertTrue(s.steady(1_000L));
        assertTrue(s.steady(825L + FeedSteadiness.WINDOW_MS - 1));
        assertFalse(s.steady(825L + FeedSteadiness.WINDOW_MS));
    }

    @Test
    public void aSlowButAliveFeedAboveTheThresholdCounts() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 0L, 10_000L, 300L);     // ≈3.3 fps：六帧攒在 1.5 秒里
        assertTrue(s.steady(10_000L));
    }

    @Test
    public void resetForgetsTheOldFrames() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 0L, 1_000L, 33L);
        assertTrue(s.steady(1_000L));
        s.reset();
        assertFalse(s.steady(1_000L));
        feed(s, 1_010L, 1_200L, 33L);
        assertTrue(s.steady(1_200L));
    }

    @Test
    public void noFramesIsNotSteady() {
        assertFalse(new FeedSteadiness().steady(5_000L));
    }

    /** 稳上十秒才算好了：持续出画面的相机到点就算。 */
    @Test
    public void aFeedSteadyForTenSecondsCountsAsRecovered() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 0L, 9_000L, 33L);
        assertFalse("才稳了九秒", s.recovered(9_000L));
        feed(s, 9_020L, 10_500L, 33L);
        assertTrue(s.recovered(10_500L));
    }

    /**
     * 开了又断的相机每次都稳上一两秒：每断一次就重新算，永远不到十秒 ——
     * 它的冷却轮数、重连次数就不会被清掉。
     */
    @Test
    public void aFlappingFeedNeverCountsAsRecovered() {
        FeedSteadiness s = new FeedSteadiness();
        long t = 0L;
        for (int flap = 0; flap < 20; flap++) {
            feed(s, t, t + 1_500L, 33L);        // 稳上一秒半
            assertFalse(s.recovered(t + 1_500L));
            t += 1_500L + 5_000L;               // 断五秒，窗口里的帧老掉
            assertEquals(0L, s.steadyForMs(t));
        }
    }

    /** 爬着的流（0.8 fps）一直不稳，也就一直不算好了。 */
    @Test
    public void aCrawlingFeedIsNeverRecovered() {
        FeedSteadiness s = new FeedSteadiness();
        feed(s, 0L, 120_000L, 1_250L);
        assertFalse(s.recovered(120_000L));
        assertEquals(0L, s.steadyForMs(120_000L));
    }
}
