package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 快照：不知道就是 null，改一项其余不动，版本号只往上走。
 */
public class VehicleStateTest {

    @Test
    public void anEmptySnapshotKnowsNothing() {
        VehicleState s = VehicleState.empty();
        assertEquals(0, s.knownCount());
        assertNull(s.speedKmh);
        assertNull(s.turnSignal);
    }

    @Test
    public void editingKeepsTheOtherFieldsAndBumpsTheVersion() {
        VehicleState first = VehicleState.empty().edit().speedKmh(42f).gear("D").build();
        VehicleState second = first.edit().turnSignal(VehicleState.TURN_LEFT).build();
        assertEquals(Float.valueOf(42f), second.speedKmh);
        assertEquals("D", second.gear);
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), second.turnSignal);
        assertEquals(first.version + 1, second.version);
        assertEquals(3, second.knownCount());
    }

    /** 车门和安全带按位记：一扇一扇地报，之前不知道就从「都关着」起。 */
    @Test
    public void doorsAndBeltsAreKeptPerPosition() {
        VehicleState s = VehicleState.empty().edit()
                .door(VehicleState.FRONT_LEFT, true)
                .door(VehicleState.REAR_RIGHT, true)
                .door(VehicleState.FRONT_LEFT, false)
                .belt(VehicleState.FRONT_RIGHT, true)
                .build();
        assertEquals(Integer.valueOf(VehicleState.REAR_RIGHT), s.doorsOpen);
        assertEquals(Integer.valueOf(VehicleState.FRONT_RIGHT), s.beltsUnbuckled);
    }

    @Test
    public void pedalsAreClampedToZeroOne() {
        VehicleState s = VehicleState.empty().edit().throttle(1.7f).brake(-0.2f).build();
        assertEquals(Float.valueOf(1f), s.throttle);
        assertEquals(Float.valueOf(0f), s.brake);
    }
}
