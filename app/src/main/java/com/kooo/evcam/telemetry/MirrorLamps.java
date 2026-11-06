package com.kooo.evcam.telemetry;

/**
 * 后视镜上的盲区指示灯此刻亮没亮（旁边车道有车），左右各一份。不可变，{@link MirrorLampSource} 每变一次给一份新的。
 * 用在侧视画面上：显示的那一边灯亮着，画面靠旁边车道的一侧显示渐变红（{@code zeekr.SideViewPopupView}）。
 *
 * <p>读法来自 dts88 的 2.97.0-alpha（他们车上验证过）。他们还读停车后的开门预警（同一盏灯），我们不用，不读。</p>
 *
 * <p>读不到的是 null：没连上、这台车没有这个号、这一侧还没读到过。值：0 灭，不是 0 的都算亮 ——
 * 多显示一次红框，好过该提醒时不提醒。</p>
 *
 * <p>纯 Java，见 {@code MirrorLampsTest}。</p>
 */
public final class MirrorLamps {

    /** 哪一盏。 */
    public enum Lamp {
        BLIND_SPOT_LEFT,
        BLIND_SPOT_RIGHT
    }

    /** 全都读不到（没连上、没人要）。 */
    public static final MirrorLamps UNKNOWN = new MirrorLamps(null, null);

    public final Boolean blindSpotLeft;
    public final Boolean blindSpotRight;

    MirrorLamps(Boolean blindSpotLeft, Boolean blindSpotRight) {
        this.blindSpotLeft = blindSpotLeft;
        this.blindSpotRight = blindSpotRight;
    }

    /** 车给的值：数，不是 0 就是亮；null、不是数的算读不到。 */
    static Boolean on(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    public Boolean get(Lamp lamp) {
        return lamp == Lamp.BLIND_SPOT_LEFT ? blindSpotLeft : blindSpotRight;
    }

    /** 换掉一盏的值；没变就是自己。 */
    MirrorLamps with(Lamp lamp, Boolean value) {
        if (same(get(lamp), value)) {
            return this;
        }
        return new MirrorLamps(
                lamp == Lamp.BLIND_SPOT_LEFT ? value : blindSpotLeft,
                lamp == Lamp.BLIND_SPOT_RIGHT ? value : blindSpotRight);
    }

    /** 有一盏亮着（对账只在这时做，{@link MirrorLampSource}）。 */
    boolean anyOn() {
        return Boolean.TRUE.equals(blindSpotLeft) || Boolean.TRUE.equals(blindSpotRight);
    }

    private static boolean same(Boolean a, Boolean b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MirrorLamps)) {
            return false;
        }
        MirrorLamps other = (MirrorLamps) o;
        return same(blindSpotLeft, other.blindSpotLeft) && same(blindSpotRight, other.blindSpotRight);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(blindSpotLeft, blindSpotRight);
    }

    /** 日志、诊断用（英文）：1 亮、0 灭、? 读不到。 */
    @Override
    public String toString() {
        return "blindSpot L" + mark(blindSpotLeft) + " R" + mark(blindSpotRight);
    }

    private static String mark(Boolean on) {
        return on == null ? "?" : on ? "1" : "0";
    }
}
