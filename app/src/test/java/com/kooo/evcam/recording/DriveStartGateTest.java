package com.kooo.evcam.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** {@link DriveStartGate}：亮屏满 10 秒、人脸识别和环视两路都安静 5 秒才开；最多等 30 秒。 */
public class DriveStartGateTest {

    private static Map<String, long[]> cams(long[] face, long[] surround) {
        Map<String, long[]> m = new HashMap<>();
        if (face != null) {
            m.put("0", face);
        }
        if (surround != null) {
            m.put("2", surround);
        }
        return m;
    }

    private static long[] free(long forMs) {
        return new long[]{1, forMs, 0};
    }

    private static long[] busyOthers(long forMs) {
        return new long[]{0, forMs, 0};
    }

    private static long[] busyOurs(long forMs) {
        return new long[]{0, forMs, 1};
    }

    @Test
    public void waitsForTheScreenToHaveBeenOnTenSeconds() {
        assertFalse(DriveStartGate.ready(3_000L, cams(free(60_000L), free(60_000L)), "2", 0L));
        assertTrue(DriveStartGate.ready(10_000L, cams(free(60_000L), free(60_000L)), "2", 0L));
    }

    @Test
    public void waitsWhileFaceRecognitionHoldsTheCabinCamera() {
        assertFalse(DriveStartGate.ready(20_000L, cams(busyOthers(1_000L), free(60_000L)), "2", 0L));
    }

    @Test
    public void waitsAFewSecondsAfterTheCarReleasesTheSurround() {
        assertFalse(DriveStartGate.ready(20_000L, cams(free(60_000L), free(2_000L)), "2", 0L));
        assertTrue(DriveStartGate.ready(20_000L, cams(free(60_000L), free(5_000L)), "2", 0L));
    }

    @Test
    public void ourOwnOpenSurroundIsFine() {
        assertTrue(DriveStartGate.ready(20_000L, cams(free(60_000L), busyOurs(500L)), "2", 0L));
    }

    @Test
    public void unknownStateDoesNotBlock() {
        assertTrue(DriveStartGate.ready(-1L, new HashMap<>(), "2", 0L));
    }

    @Test
    public void givesUpWaitingAfterThirtySeconds() {
        assertTrue(DriveStartGate.ready(1_000L, cams(busyOthers(1_000L), busyOthers(1_000L)), "2",
                DriveStartGate.MAX_WAIT_MS));
    }
}
