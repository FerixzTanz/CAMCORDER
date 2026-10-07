package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link DriveSession}：换出 P 挡开录，人下车（主驾座位空 30 秒或锁车布防）停录，坐在车里不停。 */
public class DriveSessionTest {

    private final DriveSession session = new DriveSession();
    private long now = 1_000_000L;

    private DriveSession.Action step(String gear, Boolean seated, Integer sentry, boolean recording) {
        DriveSession.Input in = new DriveSession.Input();
        in.nowElapsedMs = now;
        in.gear = gear;
        in.driverSeated = seated;
        in.sentry = sentry;
        in.recording = recording;
        in.autoStart = true;
        in.autoStop = true;
        return session.update(in);
    }

    @Test
    public void shiftingOutOfParkStarts() {
        assertEquals(DriveSession.Action.NONE, step("P", true, 0, false));
        assertEquals(DriveSession.Action.START, step("D", true, 0, false));
    }

    @Test
    public void alreadyRecordingDoesNotStartAgain() {
        step("P", true, 0, true);
        assertEquals(DriveSession.Action.NONE, step("D", true, 0, true));
    }

    @Test
    public void userStopKeepsItStoppedOnTheNextShift() {
        step("P", true, 0, false);
        DriveSession.Input in = new DriveSession.Input();
        in.nowElapsedMs = now;
        in.gear = "D";
        in.driverSeated = true;
        in.autoStart = true;
        in.autoStop = true;
        in.stoppedByUser = true;
        assertEquals(DriveSession.Action.NONE, session.update(in));
    }

    @Test
    public void sittingInParkNeverStops() {
        step("P", true, 0, true);
        now += 3_600_000L;
        assertEquals(DriveSession.Action.NONE, step("P", true, 0, true));
        assertFalse(session.driverAway());
    }

    @Test
    public void emptySeatForThirtySecondsInParkStops() {
        step("P", true, 0, true);
        assertEquals(DriveSession.Action.NONE, step("P", false, 0, true));
        now += DriveSession.LEAVE_AFTER_MS - 1;
        assertEquals(DriveSession.Action.NONE, step("P", false, 0, true));
        now += 1;
        assertEquals(DriveSession.Action.STOP, step("P", false, 0, true));
        assertTrue(session.driverAway());
        // 停过一次就不再重复报
        now += 60_000L;
        assertEquals(DriveSession.Action.NONE, step("P", false, 0, false));
    }

    @Test
    public void briefEmptySeatFromPressingPedalsDoesNotStop() {
        step("D", true, 0, true);
        step("D", false, 0, true);
        now += 2_000L;
        step("D", true, 0, true);
        now += 60_000L;
        assertEquals(DriveSession.Action.NONE, step("P", true, 0, true));
    }

    @Test
    public void emptySeatWhileNotInParkDoesNotStop() {
        step("D", false, 0, true);
        now += 120_000L;
        assertEquals(DriveSession.Action.NONE, step("D", false, 0, true));
    }

    @Test
    public void sentryArmedWithEmptySeatStopsAtOnce() {
        step("P", true, 1, true);
        assertEquals(DriveSession.Action.STOP, step("P", false, DriveSession.SENTRY_ARMED, true));
    }

    @Test
    public void sentryArmedWhileSittingDoesNotStop() {
        step("P", true, 1, true);
        assertEquals(DriveSession.Action.NONE, step("P", true, DriveSession.SENTRY_ARMED, true));
    }

    @Test
    public void sleepCountsSoWakingUpStopsImmediately() {
        step("P", true, 0, true);
        step("P", false, 0, true);
        // 车机睡了两个小时，醒来第一份读数
        now += 2 * 3_600_000L;
        assertEquals(DriveSession.Action.STOP, step("P", false, 0, true));
    }

    @Test
    public void leavingWhenNotRecordingEndsTheTrip() {
        step("P", false, 0, false);
        now += DriveSession.LEAVE_AFTER_MS;
        assertEquals(DriveSession.Action.LEFT, step("P", false, 0, false));
    }

    @Test
    public void comingBackAndDrivingStartsAgain() {
        step("P", false, 0, true);
        now += DriveSession.LEAVE_AFTER_MS;
        assertEquals(DriveSession.Action.STOP, step("P", false, 0, true));
        now += 600_000L;
        assertEquals(DriveSession.Action.NONE, step("P", true, 0, false));
        assertFalse("坐回来就不算走了", session.driverAway());
        assertEquals(DriveSession.Action.START, step("D", true, 0, false));
    }

    @Test
    public void manualStartAfterComingBackStopsWhenLeavingAgain() {
        step("P", false, 0, false);
        now += DriveSession.LEAVE_AFTER_MS;
        step("P", false, 0, false);
        step("P", true, 0, true);
        step("P", false, 0, true);
        now += DriveSession.LEAVE_AFTER_MS;
        assertEquals(DriveSession.Action.STOP, step("P", false, 0, true));
    }

    @Test
    public void unreadableGearDecidesNothing() {
        assertEquals(DriveSession.Action.NONE, step(null, false, DriveSession.SENTRY_ARMED, true));
    }

    @Test
    public void autoStopOffOnlyEndsTheTrip() {
        step("P", false, 0, true);
        now += DriveSession.LEAVE_AFTER_MS;
        DriveSession.Input in = new DriveSession.Input();
        in.nowElapsedMs = now;
        in.gear = "P";
        in.driverSeated = false;
        in.recording = true;
        in.autoStart = true;
        in.autoStop = false;
        assertEquals(DriveSession.Action.LEFT, session.update(in));
    }

    @Test
    public void countdownTellsHowLongUntilLeft() {
        step("P", false, 0, true);
        now += 10_000L;
        assertEquals(DriveSession.LEAVE_AFTER_MS - 10_000L, session.msUntilLeft(now));
        step("P", true, 0, true);
        assertEquals(-1L, session.msUntilLeft(now));
    }

    @Test
    public void leftCarStopNeverResumesBySurround() {
        assertFalse(RecordingStops.resumesOnSurround(RecordingStops.Reason.LEFT_CAR));
    }

    @Test
    public void leavingStartsAFreshTripButKeepsLaunchAutoStartUsed() {
        RecordingIntent intent = new RecordingIntent();
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        intent.noteUserStopped();
        intent.noteLeftCar();
        assertFalse("手动停过的作废", intent.stoppedByUser());
        assertFalse("启动自动录制不再开", intent.shouldAutoStart(true));
        assertFalse("没录起来之前不接回", intent.shouldRestore(true));
    }
}
