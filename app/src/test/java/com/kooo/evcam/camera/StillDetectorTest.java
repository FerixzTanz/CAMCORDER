package com.kooo.evcam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link StillDetector}：停稳 5 秒才降，一动就回去。 */
public class StillDetectorTest {

    @Test
    public void stoppedForFiveSecondsCountsAsStill() {
        StillDetector d = new StillDetector();
        assertFalse(d.update(0, 0f));
        assertFalse(d.update(4_999, 0f));
        assertTrue(d.update(5_000, 0f));
    }

    /** 红灯前蠕行：一会儿 0、一会儿 3 km/h —— 计时每次重来。 */
    @Test
    public void creepingRestartsTheClock() {
        StillDetector d = new StillDetector();
        d.update(0, 0f);
        d.update(4_000, 3f);
        assertFalse(d.update(8_000, 0f));
        assertTrue(d.update(13_000, 0f));
    }

    @Test
    public void movingAgainIsImmediate() {
        StillDetector d = new StillDetector();
        d.update(0, 0f);
        assertTrue(d.update(6_000, 0f));
        assertFalse(d.update(6_200, 2f));
    }

    /** 车速读不到：按在开，不降。 */
    @Test
    public void unknownSpeedIsTreatedAsMoving() {
        StillDetector d = new StillDetector();
        d.update(0, 0f);
        assertFalse(d.update(10_000, null));
        assertFalse(d.update(20_000, null));
    }
}
