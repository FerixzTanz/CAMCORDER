package com.kooo.evcam.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 什么时候可以自己开始录制。
 *
 * <p>每一条都对应一次真实的误开：回放看完切回来就开录、切个日夜模式就开录、
 * 按了停止之后过 30 秒又自己开起来。</p>
 */
public class RecordingIntentTest {

    private final RecordingIntent intent = new RecordingIntent();

    /** 开关关着，什么都不该发生。 */
    @Test
    public void nothingHappensWhenTheSettingIsOff() {
        assertFalse(intent.shouldAutoStart(false));
        intent.noteRecordingStarted();
        assertFalse(intent.shouldRestore(false));
    }

    @Test
    public void autoStartsOncePerLaunch() {
        assertTrue(intent.shouldAutoStart(true));
        intent.noteAutoStarted();
        assertFalse("一趟只自动开一次", intent.shouldAutoStart(true));
    }

    /**
     * 主界面重建（切日夜模式、换语言、关掉再打开）不是新的一趟。
     *
     * <p>以前「已经开过」这个标记是界面的字段，界面一换就归零，于是又开一次。</p>
     */
    @Test
    public void rebuildingTheScreenIsNotANewLaunch() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        // 界面重建：换成新的 Activity，但 RecordingIntent 是进程级的，还是这一份
        assertFalse(intent.shouldAutoStart(true));
    }

    /** 用户按了停止之后，这一趟里没有任何一条路可以再自动开起来。 */
    @Test
    public void nothingAutoStartsAfterTheUserPressedStop() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        intent.noteUserStopped();

        assertFalse("重建界面也不许再自动开", intent.shouldAutoStart(true));
        assertFalse("定时检查也不许接回去", intent.shouldRestore(true));
    }

    /** 用户又自己按了开始，「停过」就作废了 —— 之后意外停了还是该接回去。 */
    @Test
    public void startingAgainByHandClearsTheStopRecord() {
        intent.noteUserStopped();
        intent.noteUserStarted();
        intent.noteRecordingStarted();
        assertTrue(intent.shouldRestore(true));
    }

    /**
     * 从来没录起来过就别自己开。
     *
     * <p>「打开视频回放再切回主界面」就落在这里：这一趟一帧都没录过，
     * 没有任何东西需要「恢复」。</p>
     */
    @Test
    public void doesNotRestoreSomethingThatNeverRan() {
        assertFalse(intent.shouldRestore(true));
        intent.noteAutoStarted();
        assertFalse("自动开过但没录起来（比如没插 U 盘），也不该反复重试",
                intent.shouldRestore(true));
    }

    @Test
    public void restoresRecordingThatStoppedOnItsOwn() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        assertTrue(intent.shouldRestore(true));
    }

    @Test
    public void resetStartsAFreshLaunch() {
        intent.noteAutoStarted();
        intent.noteUserStopped();
        intent.reset();
        assertTrue(intent.shouldAutoStart(true));
        assertFalse(intent.stoppedByUser());
    }

    /** 规格 1.2：进程被杀又拉回来，「这一趟」的选择还在 —— 新实例从同一份存储里读回来。 */
    @Test
    public void choicesSurviveANewInstanceThroughTheStore() {
        java.util.Map<String, Boolean> saved = new java.util.HashMap<>();
        RecordingIntent.Store store = new RecordingIntent.Store() {
            @Override
            public boolean get(String key, boolean fallback) {
                return saved.containsKey(key) ? saved.get(key) : fallback;
            }

            @Override
            public void put(String key, boolean value) {
                saved.put(key, value);
            }
        };
        RecordingIntent first = new RecordingIntent();
        first.attach(store);
        first.noteRecordingStarted();
        first.noteUserStopped();

        RecordingIntent second = new RecordingIntent();
        second.attach(store);
        assertTrue(second.stoppedByUser());
        assertFalse(second.shouldRestore(true));
        assertFalse(second.shouldAutoStart(true));
    }
}
