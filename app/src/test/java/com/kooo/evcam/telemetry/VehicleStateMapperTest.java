package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

/**
 * 读数表 → 信息条快照：转向灯的优先级、主驾在哪一边、位掩码。
 */
public class VehicleStateMapperTest {

    private static Readings readings(Object... pairs) {
        Map<Signal, Object> map = new EnumMap<>(Signal.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Signal) pairs[i], pairs[i + 1]);
        }
        return new Readings(map, 1);
    }

    private static VehicleState map(Readings r, boolean driverOnRight) {
        VehicleState.Builder b = VehicleState.empty().edit();
        new VehicleStateMapper().apply(b, r, 1000L, driverOnRight);
        return b.build();
    }

    /** 有不闪的「转向指示状态」就听它的，哪怕灯此刻正好在灭的半周期。 */
    @Test
    public void steadyIndicatorBeatsTheBlinkingLamps() {
        VehicleState s = map(readings(Signal.INDICATOR, 1, Signal.TURN_LEFT, false, Signal.TURN_RIGHT, false), true);
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), s.turnSignal);
        assertEquals(Boolean.FALSE, s.hazard);
    }

    @Test
    public void withoutTheIndicatorTheLampHoldDecides() {
        VehicleState s = map(readings(Signal.TURN_RIGHT, true, Signal.TURN_LEFT, false), true);
        assertEquals(Integer.valueOf(VehicleState.TURN_RIGHT), s.turnSignal);
    }

    /** 转向指示状态 3 = 双闪，不闪、不用等两盏灯。 */
    @Test
    public void indicatorThreeIsHazard() {
        VehicleState s = map(readings(Signal.INDICATOR, 3, Signal.TURN_LEFT, false, Signal.TURN_RIGHT, false), true);
        assertEquals(Boolean.TRUE, s.hazard);
        assertEquals(Integer.valueOf(VehicleState.TURN_NONE), s.turnSignal);
    }

    /** 读不到指示状态时：两盏一起闪就是双闪。 */
    @Test
    public void bothLampsBlinkingIsHazard() {
        VehicleState s = map(readings(Signal.TURN_LEFT, true, Signal.TURN_RIGHT, true), true);
        assertEquals(Boolean.TRUE, s.hazard);
    }

    /** 主驾门落在主驾那一边：右舵在右前，左舵在左前。 */
    @Test
    public void driverDoorLandsOnTheDriverSide() {
        Readings r = readings(Signal.DOOR_DRIVER, true, Signal.DOOR_PASSENGER, false,
                Signal.DOOR_REAR_LEFT, false, Signal.DOOR_REAR_RIGHT, true);
        assertEquals(Integer.valueOf(VehicleState.FRONT_RIGHT | VehicleState.REAR_RIGHT), map(r, true).doorsOpen);
        assertEquals(Integer.valueOf(VehicleState.FRONT_LEFT | VehicleState.REAR_RIGHT), map(r, false).doorsOpen);
    }

    /** 表里记的是「系着」，快照里记的是「没系」。 */
    @Test
    public void beltsAreReportedAsUnbuckled() {
        Readings r = readings(Signal.BELT_DRIVER, false, Signal.BELT_PASSENGER, true, Signal.BELT_REAR_CENTER, false);
        assertEquals(Integer.valueOf(VehicleState.FRONT_RIGHT | VehicleState.REAR_CENTER), map(r, true).beltsUnbuckled);
        assertEquals(Integer.valueOf(VehicleState.FRONT_LEFT | VehicleState.REAR_CENTER), map(r, false).beltsUnbuckled);
    }

    /** 0x20060400 是自动驻车的功能开关，不是「正在驻车」：信息条上不能拿它亮灯。 */
    @Test
    public void autoHoldSwitchDoesNotLightTheCell() {
        assertNull(map(readings(Signal.AUTO_HOLD, true), true).autoHold);
    }

    /** 非开发者：不能用的信号在映射前滤掉 —— 副驾安全带不会被画成红的；先用着的（方向盘）留着。 */
    @Test
    public void unusableSignalsAreMaskedBeforeMapping() {
        Readings r = readings(Signal.BELT_DRIVER, false, Signal.BELT_PASSENGER, false,
                Signal.STEERING, -12f).usableOnly();
        assertEquals(Float.valueOf(-12f), map(r, true).steeringDegrees);
        assertNull(r.bool(Signal.BELT_PASSENGER));
        assertEquals(Boolean.FALSE, r.bool(Signal.BELT_DRIVER));
        assertEquals(Integer.valueOf(VehicleState.FRONT_RIGHT), map(r, true).beltsUnbuckled);
    }

    @Test
    public void nothingKnownLeavesTheMasksNull() {
        VehicleState s = map(readings(), true);
        assertNull(s.doorsOpen);
        assertNull(s.beltsUnbuckled);
        assertNull(s.turnSignal);
        assertNull(s.gear);
    }

    @Test
    public void depthsBecomeFractionsAndFogIsEitherLamp() {
        VehicleState s = map(readings(Signal.BRAKE_DEPTH, 15f, Signal.THROTTLE_DEPTH, 50f,
                Signal.FRONT_FOG, false, Signal.REAR_FOG, true, Signal.GEAR, "D"), true);
        assertEquals(0.15f, s.brake, 1e-6f);
        assertEquals(57f, map(readings(Signal.SPEED, 57f), true).speedKmh, 1e-3f);
        assertEquals(0.5f, s.throttle, 1e-6f);
        assertEquals(Boolean.TRUE, s.fogLights);
        assertEquals("D", s.gear);
        assertNull(map(readings(), true).fogLights);
    }
}
