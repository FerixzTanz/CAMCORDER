package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MirrorLamps} 的单元测试：车给的值怎么算亮、换一盏的值、有没有亮着的。
 */
public class MirrorLampsTest {

    @Test
    public void anyNonZeroNumberIsOnAndAnythingElseIsUnknown() {
        assertEquals(Boolean.FALSE, MirrorLamps.on(0));
        assertEquals(Boolean.TRUE, MirrorLamps.on(1));
        assertEquals(Boolean.TRUE, MirrorLamps.on(3));
        assertEquals(Boolean.TRUE, MirrorLamps.on(true));
        assertNull(MirrorLamps.on(null));
        assertNull(MirrorLamps.on("1"));
    }

    @Test
    public void withChangesOneLampAndKeepsTheOther() {
        MirrorLamps left = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_LEFT, true);
        assertEquals(Boolean.TRUE, left.blindSpotLeft);
        assertNull(left.blindSpotRight);
        MirrorLamps both = left.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, false);
        assertEquals(Boolean.TRUE, both.blindSpotLeft);
        assertEquals(Boolean.FALSE, both.blindSpotRight);
        assertEquals("blindSpot L1 R0", both.toString());
    }

    @Test
    public void anUnchangedValueKeepsTheSameSnapshot() {
        MirrorLamps left = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_LEFT, true);
        assertSame(left, left.with(MirrorLamps.Lamp.BLIND_SPOT_LEFT, true));
        assertSame(MirrorLamps.UNKNOWN, MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, null));
    }

    @Test
    public void anyOnNeedsALitLamp() {
        assertFalse(MirrorLamps.UNKNOWN.anyOn());
        assertFalse(MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_LEFT, false).anyOn());
        assertTrue(MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, true).anyOn());
    }
}
