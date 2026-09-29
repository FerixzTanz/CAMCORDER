package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * 读数怎么归一。占位值的规则是车上遍历看出来的（zeekr-shortcut-lab findings §2.1），
 * 认错一个，页面上就会把「没数据」写成「关」。
 */
public class SignalDecodeTest {

    @Test
    public void integerPlaceholdersAreUnknown() {
        assertNull(Signal.intOrNull(255));
        assertNull(Signal.intOrNull(254));
        assertNull(Signal.intOrNull(253));
        assertNull(Signal.intOrNull(-1));
        assertNull(Signal.intOrNull(-65535));
        assertEquals(Integer.valueOf(0), Signal.intOrNull(0));
        assertEquals(Integer.valueOf(1), Signal.intOrNull(1));
        assertEquals(Integer.valueOf(0x00200230), Signal.intOrNull(0x00200230));
    }

    /** 没数据的浮点不是 0.0，是一个极小的非零数；真正的 0（停着的车速）要留下。 */
    @Test
    public void floatPlaceholdersAreUnknownButZeroIsZero() {
        assertNull(Signal.floatOrNull(255f));
        assertNull(Signal.floatOrNull(-65535f));
        assertNull(Signal.floatOrNull(1e-9f));
        assertNull(Signal.floatOrNull(Float.NaN));
        assertEquals(Float.valueOf(0f), Signal.floatOrNull(0f));
        assertEquals(Float.valueOf(11053f), Signal.floatOrNull(11053f));
        assertEquals(Float.valueOf(-0.011f), Signal.floatOrNull(-0.011f));
    }

    @Test
    public void onOffOnlyAcceptsZeroAndOne() {
        assertEquals(Boolean.FALSE, Signal.LOW_BEAM.decode(0));
        assertEquals(Boolean.TRUE, Signal.LOW_BEAM.decode(1));
        assertNull(Signal.LOW_BEAM.decode(2));
        assertNull(Signal.LOW_BEAM.decode(255));
        assertNull(Signal.LOW_BEAM.decode(null));
    }

    /** 车门开关过程中读到 0x21020101（DOOR_PAUSE）：算没数据，不算开也不算关。 */
    @Test
    public void doorTransientIsUnknown() {
        assertEquals(Boolean.TRUE, Signal.DOOR_DRIVER.decode(1));
        assertEquals(Boolean.FALSE, Signal.DOOR_DRIVER.decode(0));
        assertNull(Signal.DOOR_DRIVER.decode(0x21020101));
    }

    @Test
    public void seatUsesTheLowByte() {
        assertEquals(Boolean.FALSE, Signal.SEAT_DRIVER.decode(0x00203301));
        assertEquals(Boolean.TRUE, Signal.SEAT_DRIVER.decode(0x00203302));
        assertNull(Signal.SEAT_DRIVER.decode(0x00203300));
    }

    @Test
    public void stock360ShownIsOneHiddenIsTwo() {
        assertEquals(Boolean.TRUE, Signal.STOCK_360.decode(1));
        assertEquals(Boolean.FALSE, Signal.STOCK_360.decode(2));
        assertNull(Signal.STOCK_360.decode(0));
    }

    @Test
    public void indicatorIsZeroToThree() {
        assertEquals(Integer.valueOf(0), Signal.INDICATOR.decode(0));
        assertEquals(Integer.valueOf(1), Signal.INDICATOR.decode(1));
        assertEquals(Integer.valueOf(2), Signal.INDICATOR.decode(2));
        assertEquals(Integer.valueOf(3), Signal.INDICATOR.decode(3));
        assertNull(Signal.INDICATOR.decode(4));
    }

    @Test
    public void ignitionKeepsTheCode() {
        assertEquals(Integer.valueOf(Signal.IGNITION_DRIVING), Signal.IGNITION.decode(0x00200107));
        assertEquals(Integer.valueOf(Signal.IGNITION_ACC), Signal.IGNITION.decode(0x00200104));
    }

    @Test
    public void gearLettersComeFromSensorEvents() {
        assertEquals("P", Signal.GEAR.decode(0x00200230));
        assertEquals("R", Signal.GEAR.decode(0x00200240));
        assertEquals("N", Signal.GEAR.decode(0x00200210));
        assertEquals("D", Signal.GEAR.decode(0x00200220));
        assertNull(Signal.GEAR.decode(0x00200250));
    }

    @Test
    public void floatFormatsPassNumbersThrough() {
        assertEquals(Float.valueOf(11053f), Signal.ODOMETER.decode(11053f));
        assertEquals(Float.valueOf(0f), Signal.SPEED.decode(0f));
        assertNull(Signal.SPEED.decode(255f));
        assertEquals(Float.valueOf(-12f), Signal.STEERING.decode(-12f));
    }

    /** 车速传感器给的是 m/s（Lab 0.10.0：15.833 = 57 km/h），表里直接换成 km/h。 */
    @Test
    public void speedIsMetresPerSecondTurnedIntoKmh() {
        assertEquals(57f, (Float) Signal.SPEED.decode(15.833f), 0.01f);
        assertEquals(1f, (Float) Signal.SPEED.decode(0.2778f), 0.001f);
        assertEquals(Float.valueOf(0f), Signal.SPEED.decode(0f));
    }

    /** 同一个读法 + 号码 + 区域只能对应一条信号，否则回调分不清给谁。 */
    @Test
    public void everyAddressIsUnique() {
        Set<String> seen = new HashSet<>();
        for (Signal s : Signal.values()) {
            assertTrue(s.name(), seen.add(s.kind + "/" + s.id + "/" + s.zone));
        }
    }
}
