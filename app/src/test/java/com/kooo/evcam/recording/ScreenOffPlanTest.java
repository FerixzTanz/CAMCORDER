package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 熄屏后这段录像会怎样：录制键上的小字和录制协调器照的是同一张表（lifecycle-spec §2.4）。
 */
public class ScreenOffPlanTest {

    private static final Integer[] ANY_SENTRY = {0, 1, 2, null};

    /** 熄屏持续录制开着：车机睡不睡看哨兵模式。 */
    @Test
    public void keepRecordingFollowsSentryMode() {
        assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(true, false, false, 1));
        assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(true, false, false, 2));
        assertEquals("哨兵模式没开：车机睡着时停住，醒来接着录",
                ScreenOffPlan.PAUSE, ScreenOffPlan.of(true, false, false, 0));
        assertEquals(ScreenOffPlan.NEEDS_SENTRY, ScreenOffPlan.of(true, false, false, null));
    }

    /** 熄屏持续录制开着时不停录，也就没有「亮屏接回」这回事：启动自动录制开不开一样。 */
    @Test
    public void autoRecordDoesNotMatterWhileKeepRecordingIsOn() {
        for (Integer sentry : ANY_SENTRY) {
            assertEquals(ScreenOffPlan.of(true, false, false, sentry), ScreenOffPlan.of(true, false, true, sentry));
        }
    }

    /** 开发者的熄屏录制拿唤醒锁拉住车机：哨兵模式开没开都接着录。 */
    @Test
    public void aWakeLockKeepsRecordingWhateverSentryModeSays() {
        for (Integer sentry : ANY_SENTRY) {
            assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(true, true, false, sentry));
        }
    }

    /** 熄屏持续录制关着：哨兵模式开没开都停；亮屏后只在启动自动录制开着时接回。 */
    @Test
    public void withKeepRecordingOffOnlyAutoRecordDecides() {
        for (Integer sentry : ANY_SENTRY) {
            assertEquals(ScreenOffPlan.PAUSE, ScreenOffPlan.of(false, false, true, sentry));
            assertEquals(ScreenOffPlan.STOP, ScreenOffPlan.of(false, false, false, sentry));
        }
    }
}
