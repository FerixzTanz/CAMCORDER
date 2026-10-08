package com.kooo.evcam.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link ForegroundLifetime}：停录之后，窗口还占着相机的话前台服务不能停。 */
public class ForegroundLifetimeTest {

    /** 2026-10-08：行驶中关掉录像，侧视弹窗还要相机 —— 服务一停，它就没了相机。 */
    @Test
    public void stoppingARecordingDoesNotTakeTheCameraFromASideViewThatStillHoldsIt() {
        assertTrue(ForegroundLifetime.staysUpAfterStop(false, false, true));
    }

    @Test
    public void keepAliveStillKeepsItUp() {
        assertTrue(ForegroundLifetime.staysUpAfterStop(false, true, false));
        assertTrue(ForegroundLifetime.staysUpAfterStop(false, true, true));
    }

    /** 没人要：和以前一样，停录就停。 */
    @Test
    public void stopsWhenNobodyNeedsIt() {
        assertFalse(ForegroundLifetime.staysUpAfterStop(false, false, false));
    }

    /** 用户点了退出：什么都留不住 —— 这是以前「退不掉」的那条路。 */
    @Test
    public void anExitAlwaysStopsIt() {
        assertFalse(ForegroundLifetime.staysUpAfterStop(true, true, true));
        assertFalse(ForegroundLifetime.staysUpAfterStop(true, false, true));
        assertFalse(ForegroundLifetime.staysUpAfterStop(true, true, false));
    }
}
