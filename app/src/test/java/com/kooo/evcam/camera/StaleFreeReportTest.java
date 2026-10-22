package com.kooo.evcam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 相机服务报「空闲」、设备还在出画面：刚报的那两秒信相机服务，之后信画面。 */
public class StaleFreeReportTest {

    @Test
    public void notFreeNeverBlocks() {
        assertFalse(StaleFreeReport.blocks(false, 0));
        assertFalse(StaleFreeReport.blocks(false, -1));
    }

    /** 2026-10-07：报空闲 1.3 秒后最近一帧还新鲜，设备其实死了 —— 这几秒要拦。 */
    @Test
    public void freshReportBlocks() {
        assertTrue(StaleFreeReport.blocks(true, 0));
        assertTrue(StaleFreeReport.blocks(true, 1_300));
        assertTrue(StaleFreeReport.blocks(true, StaleFreeReport.TRUST_MS - 1));
    }

    /** 2026-10-22：报空闲之后画面一直 30 fps，四分钟都在等 —— 过了信任期就不拦，交给稳帧判据。 */
    @Test
    public void staleReportDoesNotBlock() {
        assertFalse(StaleFreeReport.blocks(true, StaleFreeReport.TRUST_MS));
        assertFalse(StaleFreeReport.blocks(true, 245_000));
    }

    /** 不知道多久以前报的：照拦，和以前一样。 */
    @Test
    public void unknownAgeBlocks() {
        assertTrue(StaleFreeReport.blocks(true, -1));
    }
}
