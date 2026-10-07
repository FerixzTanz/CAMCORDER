package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link DriveSession}：坐上主驾（P 挡）或换出 P 挡开录；人下车（主驾座位空 30 秒、熄屏时立刻、或锁车布防）停录；
 * 坐在车里不停。
 */
public class DriveSessionTest {

    private final DriveSession session = new DriveSession();
    private long now = 1_000_000L;

    private DriveSession.Input input(String gear, Boolean seated, Integer sentry, boolean recording) {
        DriveSession.Input in = new DriveSession.Input();
        in.nowElapsedMs = now;
        in.gear = gear;
        in.driverSeated = seated;
        in.sentry = sentry;
        in.recording = recording;
        in.autoStart = true;
        in.autoStop = true;
        return in;
    }

    private DriveSession.Action step(String gear, Boolean seated, Integer sentry, boolean recording) {
        return session.update(input(gear, seated, sentry, recording));
    }

    private DriveSession.Action stepDark(String gear, Boolean seated, boolean recording) {
        DriveSession.Input in = input(gear, seated, 0, recording);
        in.screenOff = true;
        return session.update(in);
    }

    // ================================================================= 上车就录

    @Test
    public void gettingIntoTheDriverSeatStarts() {
        assertEquals(DriveSession.Action.NONE, step("P", false, 0, false));
        assertEquals(DriveSession.Action.START, step("P", true, 0, false));
    }

    @Test
    public void alreadySeatedWhenTheAppStartsStarts() {
        assertEquals(DriveSession.Action.START, step("P", true, 0, false));
    }

    @Test
    public void seatedBeforeTheGearIsReadableStarts() {
        assertEquals(DriveSession.Action.START, step(null, true, 0, false));
    }

    @Test
    public void gettingInWhileRecordingDoesNothing() {
        step("P", false, 0, true);
        assertEquals(DriveSession.Action.NONE, step("P", true, 0, true));
    }

    @Test
    public void userStopWhileSittingStaysStopped() {
        step("P", true, 0, true);
        DriveSession.Input in = input("P", true, 0, false);
        in.stoppedByUser = true;
        assertEquals(DriveSession.Action.NONE, session.update(in));
        DriveSession.Input drive = input("D", true, 0, false);
        drive.stoppedByUser = true;
        assertEquals("手动停过，挂挡也不开", DriveSession.Action.NONE, session.update(drive));
    }

    @Test
    public void seatBlipWhileDrivingIsNotGettingIn() {
        // 开着车录像因为别的原因停了（比如盘满）：用力踩踏板座椅读成没人又有人，不算上车，不重开
        step("D", true, 0, true);
        step("D", false, 0, false);
        assertEquals(DriveSession.Action.NONE, step("D", true, 0, false));
    }

    @Test
    public void autoStartOffDoesNotStartOnGettingIn() {
        step("P", false, 0, false);
        DriveSession.Input in = input("P", true, 0, false);
        in.autoStart = false;
        assertEquals(DriveSession.Action.NONE, session.update(in));
    }

    // ================================================================= 开走（补上）

    @Test
    public void shiftingOutOfParkStartsIfNotRecording() {
        step("P", true, 0, true);
        assertEquals(DriveSession.Action.START, step("D", true, 0, false));
    }

    @Test
    public void shiftingWhileRecordingDoesNothing() {
        step("P", true, 0, true);
        assertEquals(DriveSession.Action.NONE, step("D", true, 0, true));
    }

    @Test
    public void firstGearAlreadyOutOfParkStarts() {
        // 应用起来时已经在开车（起来晚了 / 开着车重启了）
        assertEquals(DriveSession.Action.START, step("D", true, 0, false));
    }

    @Test
    public void firstGearAlreadyOutOfParkRespectsUserStop() {
        DriveSession.Input in = input("D", true, 0, false);
        in.stoppedByUser = true;
        assertEquals(DriveSession.Action.NONE, session.update(in));
    }

    @Test
    public void lateGearSignalCounts() {
        assertEquals(DriveSession.Action.NONE, step(null, null, 0, false));
        assertEquals(DriveSession.Action.START, step("D", null, 0, false));
    }

    @Test
    public void stayingInDriveDoesNotAskAgain() {
        assertEquals(DriveSession.Action.START, step("D", true, 0, false));
        // 开录没成（比如没 U 盘）：之后每份读数都不再要
        assertEquals(DriveSession.Action.NONE, step("D", true, 0, false));
        now += 60_000L;
        assertEquals(DriveSession.Action.NONE, step("D", true, 0, false));
    }

    // ================================================================= 坐着不停

    @Test
    public void sittingInParkNeverStops() {
        step("P", true, 0, true);
        now += 3_600_000L;
        assertEquals(DriveSession.Action.NONE, step("P", true, 0, true));
        assertFalse(session.driverAway());
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
    public void sentryArmedWhileSittingDoesNotStop() {
        step("P", true, 1, true);
        assertEquals(DriveSession.Action.NONE, step("P", true, DriveSession.SENTRY_ARMED, true));
    }

    @Test
    public void screenOffWhileSittingDoesNotStop() {
        step("P", true, 0, true);
        assertEquals(DriveSession.Action.NONE, stepDark("P", true, true));
        now += 600_000L;
        assertEquals(DriveSession.Action.NONE, stepDark("P", true, true));
    }

    @Test
    public void screenOffWhileDrivingDoesNotStop() {
        step("D", true, 0, true);
        assertEquals(DriveSession.Action.NONE, stepDark("D", false, true));
    }

    // ================================================================= 下车就停

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
    public void screenOffInParkWithEmptySeatStopsWithoutWaiting() {
        step("P", true, 0, true);
        assertEquals(DriveSession.Action.STOP, stepDark("P", false, true));
    }

    @Test
    public void sentryArmedWithEmptySeatStopsAtOnce() {
        step("P", true, 1, true);
        assertEquals(DriveSession.Action.STOP, step("P", false, DriveSession.SENTRY_ARMED, true));
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
    public void comingBackStartsAgainWhenYouSitDown() {
        step("P", true, 0, true);
        step("P", false, 0, true);
        now += DriveSession.LEAVE_AFTER_MS;
        assertEquals(DriveSession.Action.STOP, step("P", false, 0, true));
        assertTrue(session.driverAway());
        now += 600_000L;
        assertEquals("坐回来就开录", DriveSession.Action.START, step("P", true, 0, false));
        assertFalse(session.driverAway());
        assertEquals(DriveSession.Action.NONE, step("D", true, 0, true));
    }

    @Test
    public void leavingAgainAfterComingBackStopsAgain() {
        step("P", false, 0, false);
        now += DriveSession.LEAVE_AFTER_MS;
        step("P", false, 0, false);
        step("P", true, 0, true);
        step("P", false, 0, true);
        now += DriveSession.LEAVE_AFTER_MS;
        assertEquals(DriveSession.Action.STOP, step("P", false, 0, true));
    }

    @Test
    public void unreadableGearDecidesNothingAboutLeaving() {
        assertEquals(DriveSession.Action.NONE, step(null, false, DriveSession.SENTRY_ARMED, true));
    }

    @Test
    public void autoStopOffOnlyEndsTheTrip() {
        step("P", false, 0, true);
        now += DriveSession.LEAVE_AFTER_MS;
        DriveSession.Input in = input("P", false, 0, true);
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

    // ================================================================= 和录像其余部分的接口

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
