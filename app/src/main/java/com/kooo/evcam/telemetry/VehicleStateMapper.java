package com.kooo.evcam.telemetry;

/**
 * 读数表（{@link Readings}）→ 信息条要的快照（{@link VehicleState}）。
 *
 * <p>规则都在这里，纯函数（{@code VehicleStateMapperTest}）：</p>
 * <ul>
 *   <li>转向灯和双闪优先用不闪的「转向指示状态」（0 关 1 左 2 右 3 双闪）；读不到时退回两盏灯的
 *       闪烁保持判定（双闪 = 两盏同时在闪；专用的双闪功能号一直读 255，不用）。</li>
 *   <li>车门和安全带按「主驾在哪一边」落到左右：右舵车主驾门 / 主驾安全带在右前。</li>
 *   <li>刹车 / 油门深度 0–100 → 0..1；雾灯 = 前雾或后雾。车速在 {@link Signal#decode} 里已从 m/s 换成 km/h。</li>
 *   <li>自动驻车不从 {@link Signal#AUTO_HOLD} 推：那是功能开关（一直 1），不是「正在驻车」；状态信号找到前留空。</li>
 *   <li>非开发者拿到的读数已经滤掉了没验证的信号（{@link Readings#usableOnly()}），这里不再分辨。</li>
 * </ul>
 */
public final class VehicleStateMapper {

    private final TurnSignalHold hold = new TurnSignalHold();

    /**
     * @param driverOnRight 主驾在右（右舵）；决定主驾门 / 主驾安全带画在哪一边
     */
    public void apply(VehicleState.Builder b, Readings r, long nowMs, boolean driverOnRight) {
        TurnSignalHold.Result blink = hold.update(nowMs, r.bool(Signal.TURN_LEFT), r.bool(Signal.TURN_RIGHT));
        Integer indicator = r.code(Signal.INDICATOR);
        if (indicator != null) {
            b.turnSignal(indicator == 1 ? VehicleState.TURN_LEFT : indicator == 2 ? VehicleState.TURN_RIGHT : VehicleState.TURN_NONE);
            b.hazard(indicator == 3);
        } else {
            b.turnSignal(blink.turn);
            b.hazard(blink.hazard);
        }

        b.steeringDegrees(r.number(Signal.STEERING));
        b.gear(r.text(Signal.GEAR));
        b.brake(percent(r.number(Signal.BRAKE_DEPTH)));
        b.throttle(percent(r.number(Signal.THROTTLE_DEPTH)));
        b.speedKmh(r.number(Signal.SPEED));
        b.laneCentering(r.bool(Signal.LCC));
        b.stockSurroundShown(r.bool(Signal.STOCK_360));
        b.lowBeam(r.bool(Signal.LOW_BEAM));
        b.highBeam(r.bool(Signal.HIGH_BEAM));
        b.fogLights(either(r.bool(Signal.FRONT_FOG), r.bool(Signal.REAR_FOG)));
        b.daytimeRunningLights(r.bool(Signal.DRL));
        b.odometerKm(r.number(Signal.ODOMETER));
        b.aeb(r.bool(Signal.AEB));
        b.forwardCollisionWarning(r.bool(Signal.FCW));
        b.laneDepartureWarning(r.bool(Signal.LDW));
        b.laneKeepingAid(r.bool(Signal.LKA));
        b.blindSpotAssist(r.bool(Signal.BSD));
        b.rearCollisionWarning(r.bool(Signal.RCW));

        int driverBit = driverOnRight ? VehicleState.FRONT_RIGHT : VehicleState.FRONT_LEFT;
        int passengerBit = driverOnRight ? VehicleState.FRONT_LEFT : VehicleState.FRONT_RIGHT;
        b.doorsOpen(mask(
                new Boolean[]{r.bool(Signal.DOOR_DRIVER), r.bool(Signal.DOOR_PASSENGER),
                        r.bool(Signal.DOOR_REAR_LEFT), r.bool(Signal.DOOR_REAR_RIGHT)},
                new int[]{driverBit, passengerBit, VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT}));
        // 安全带表里记的是「没系」
        b.beltsUnbuckled(mask(
                new Boolean[]{not(r.bool(Signal.BELT_DRIVER)), not(r.bool(Signal.BELT_PASSENGER)),
                        not(r.bool(Signal.BELT_REAR_LEFT)), not(r.bool(Signal.BELT_REAR_CENTER)),
                        not(r.bool(Signal.BELT_REAR_RIGHT))},
                new int[]{driverBit, passengerBit, VehicleState.REAR_LEFT, VehicleState.REAR_CENTER,
                        VehicleState.REAR_RIGHT}));
    }

    static Float percent(Float v) {
        return v == null ? null : v / 100f;
    }

    /** 两盏里有一盏亮就算亮；都不知道才是不知道。 */
    static Boolean either(Boolean a, Boolean b) {
        if (a == null && b == null) {
            return null;
        }
        return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
    }

    static Boolean not(Boolean v) {
        return v == null ? null : !v;
    }

    /** 各位置的开 / 没系 → 位掩码；一个都不知道时为 null。 */
    static Integer mask(Boolean[] flags, int[] bits) {
        int mask = 0;
        boolean any = false;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i] == null) {
                continue;
            }
            any = true;
            if (flags[i]) {
                mask |= bits[i];
            }
        }
        return any ? mask : null;
    }
}
