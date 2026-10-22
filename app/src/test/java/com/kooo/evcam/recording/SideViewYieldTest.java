package com.kooo.evcam.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 侧视给开录让路，但最多十秒。 */
public class SideViewYieldTest {

    @Test
    public void noRecordingPendingNoWait() {
        assertFalse(SideViewYield.shouldWait(false, 0));
        assertFalse(SideViewYield.shouldWait(false, 60_000));
    }

    /** 开录正常两秒就成：这几秒让路。 */
    @Test
    public void waitsWhileRecordingIsStarting() {
        assertTrue(SideViewYield.shouldWait(true, 0));
        assertTrue(SideViewYield.shouldWait(true, 2_000));
        assertTrue(SideViewYield.shouldWait(true, SideViewYield.GRACE_MS - 1));
    }

    /** 2026-10-22：开录等了四分钟，侧视一次都没接上 —— 让满十秒就自己接。 */
    @Test
    public void stopsWaitingAfterTheGrace() {
        assertFalse(SideViewYield.shouldWait(true, SideViewYield.GRACE_MS));
        assertFalse(SideViewYield.shouldWait(true, 245_000));
    }
}
