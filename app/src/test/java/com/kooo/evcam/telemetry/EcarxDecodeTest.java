package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ECARX 的读数怎么变成快照里的值。占位值的规则是车上遍历看出来的（zeekr-shortcut-lab
 * findings §2.1），认错一个，信息条上就会把「没数据」画成「关」。
 */
public class EcarxDecodeTest {

    @Test
    public void integerPlaceholdersAreUnknown() {
        assertNull(EcarxSource.intOrNull(255));
        assertNull(EcarxSource.intOrNull(254));
        assertNull(EcarxSource.intOrNull(253));
        assertNull(EcarxSource.intOrNull(-1));
        assertNull(EcarxSource.intOrNull(-65535));
        assertEquals(Integer.valueOf(0), EcarxSource.intOrNull(0));
        assertEquals(Integer.valueOf(1), EcarxSource.intOrNull(1));
        assertEquals(Integer.valueOf(0x00200230), EcarxSource.intOrNull(0x00200230));
    }

    /** 没数据的浮点不是 0.0，是一个极小的非零数；真正的 0（停着的车速）要留下。 */
    @Test
    public void floatPlaceholdersAreUnknownButZeroIsZero() {
        assertNull(EcarxSource.floatOrNull(255f));
        assertNull(EcarxSource.floatOrNull(-65535f));
        assertNull(EcarxSource.floatOrNull(1e-9f));
        assertNull(EcarxSource.floatOrNull(Float.NaN));
        assertEquals(Float.valueOf(0f), EcarxSource.floatOrNull(0f));
        assertEquals(Float.valueOf(11053f), EcarxSource.floatOrNull(11053f));
        assertEquals(Float.valueOf(-0.011f), EcarxSource.floatOrNull(-0.011f));
    }

    @Test
    public void onOffKnowsOnlyZeroAndOne() {
        assertFalse(EcarxSource.onOff(0));
        assertTrue(EcarxSource.onOff(1));
        assertNull(EcarxSource.onOff(2));
        assertNull(EcarxSource.onOff(null));
    }

    @Test
    public void fogIsEitherLampAndUnknownOnlyWhenBothAre() {
        assertNull(EcarxSource.either(null, null));
        assertTrue(EcarxSource.either(true, null));
        assertTrue(EcarxSource.either(null, true));
        assertFalse(EcarxSource.either(false, null));
        assertFalse(EcarxSource.either(false, false));
    }

    @Test
    public void masksCombineKnownPositionsOnly() {
        assertNull(EcarxSource.mask(new Boolean[]{null, null, null, null}));
        assertEquals(Integer.valueOf(0), EcarxSource.mask(new Boolean[]{false, null, false, null}));
        assertEquals(Integer.valueOf(VehicleState.FRONT_RIGHT | VehicleState.REAR_LEFT),
                EcarxSource.mask(new Boolean[]{false, true, true, false}));
    }

    @Test
    public void gearLettersComeFromTheConstantNames() {
        assertEquals("P", EcarxSource.gearLetter("GEAR_PARK"));
        assertEquals("R", EcarxSource.gearLetter("GEAR_REVERSE"));
        assertEquals("N", EcarxSource.gearLetter("GEAR_NEUTRAL"));
        assertEquals("D", EcarxSource.gearLetter("GEAR_DRIVE"));
        assertNull(EcarxSource.gearLetter("GEAR_UNKNOWN"));
    }

    @Test
    public void depthsBecomeFractionsAndBeltsInvert() {
        assertEquals(Float.valueOf(0.145f), EcarxSource.percent(14.5f));
        assertNull(EcarxSource.percent(null));
        assertTrue(EcarxSource.unbuckled(0));
        assertFalse(EcarxSource.unbuckled(1));
        assertNull(EcarxSource.unbuckled(255));
    }
}
