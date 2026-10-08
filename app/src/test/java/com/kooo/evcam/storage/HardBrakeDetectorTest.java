package com.kooo.evcam.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link HardBrakeDetector}：急刹车才锁，正常停车、一个跳变、泊车都不锁。 */
public class HardBrakeDetectorTest {

    /** 每 200 ms 一个读数，按给定的减速度（g）从 fromKmh 降，返回第一次触发。 */
    private static HardBrakeDetector.Event brake(HardBrakeDetector d, float fromKmh, double g,
                                                 Boolean braking) {
        float kmhPerTick = (float) (g * HardBrakeDetector.G * 3.6 * 0.2);
        HardBrakeDetector.Event event = null;
        long t = 0;
        // 先匀速开一秒
        for (int i = 0; i < 5; i++, t += 200) {
            d.update(t, fromKmh, false);
        }
        float v = fromKmh;
        while (v > 0) {
            v = Math.max(0f, v - kmhPerTick);
            HardBrakeDetector.Event e = d.update(t, v, braking);
            if (e != null && event == null) {
                event = e;
            }
            t += 200;
        }
        return event;
    }

    @Test
    public void emergencyStopWithTheBrakeLocks() {
        HardBrakeDetector.Event e = brake(new HardBrakeDetector(), 60f, 0.7, true);
        assertNotNull(e);
        assertTrue(e.braking);
        assertTrue(e.g >= 0.5);
    }

    @Test
    public void normalStopAtLightsDoesNot() {
        assertNull(brake(new HardBrakeDetector(), 60f, 0.3, true));
    }

    @Test
    public void firmButNormalStopDoesNot() {
        assertNull(brake(new HardBrakeDetector(), 60f, 0.45, true));
    }

    /** 自动紧急制动：车自己刹的，没踩刹车 —— 门槛高一点，0.6 g。 */
    @Test
    public void automaticEmergencyBrakingWithoutThePedalLocksAboveSixTenths() {
        assertNotNull(brake(new HardBrakeDetector(), 60f, 0.8, false));
        assertNull(brake(new HardBrakeDetector(), 60f, 0.55, false));
    }

    /** 能量回收（单踏板）最多约 0.25 g。 */
    @Test
    public void regenSlowingDoesNot() {
        assertNull(brake(new HardBrakeDetector(), 80f, 0.25, false));
    }

    @Test
    public void parkingSpeedJerkDoesNot() {
        assertNull(brake(new HardBrakeDetector(), 15f, 0.9, true));
    }

    /** 一个读数掉到 0 又回来：不是持续减速。 */
    @Test
    public void singleBadSpeedReadingDoesNot() {
        HardBrakeDetector d = new HardBrakeDetector();
        long t = 0;
        for (int i = 0; i < 10; i++, t += 200) {
            d.update(t, 60f, false);
        }
        assertNull(d.update(t, 0f, false));
        t += 200;
        assertNull(d.update(t, 60f, false));
        t += 200;
        assertNull(d.update(t, 60f, false));
    }

    /** 读数断了一阵（信号重连）再接上，断前快、接上慢：不能算成一次急刹车。 */
    @Test
    public void gapInReadingsIsNotADeceleration() {
        HardBrakeDetector d = new HardBrakeDetector();
        d.update(0, 80f, false);
        d.update(200, 80f, false);
        d.update(400, 80f, false);
        assertNull(d.update(3_000, 20f, true));
        assertNull(d.update(3_200, 19f, true));
        assertNull(d.update(3_400, 18f, true));
    }

    @Test
    public void oneStopIsOneEvent() {
        HardBrakeDetector d = new HardBrakeDetector();
        long t = 0;
        for (int i = 0; i < 5; i++, t += 200) {
            d.update(t, 100f, false);
        }
        int events = 0;
        float v = 100f;
        while (v > 0) {
            v = Math.max(0f, v - 5f);   // 约 0.7 g
            if (d.update(t, v, true) != null) {
                events++;
            }
            t += 200;
        }
        assertEquals(1, events);
    }

    @Test
    public void unknownSpeedIsIgnored() {
        HardBrakeDetector d = new HardBrakeDetector();
        assertNull(d.update(0, null, true));
        assertFalse(d.update(200, null, true) != null);
    }
}
