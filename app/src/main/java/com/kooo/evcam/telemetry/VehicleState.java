package com.kooo.evcam.telemetry;

/**
 * 车此刻的状态：一份不可变的快照。
 *
 * <h3>为什么每一项都可以是「不知道」</h3>
 *
 * <p>信号从哪来、来不来，由各个来源决定（{@link Telemetry}）：这台车机的容器里车辆属性
 * 多半读不到（平台笔记 §「能拿到什么信号」），定位要看有没有给权限。画信息条的人
 * 只看这份快照，不知道就画成「没数据」—— 而不是画成「关」。「没数据」和「关」
 * 在画面上必须分得开，否则转向灯亮着而我们读不到，画出来就是「没打灯」。</p>
 *
 * <p>所以每个字段都是可空的包装类型：{@code null} = 没数据。</p>
 *
 * <p>纯数据，不碰 Android，{@code VehicleStateTest} 里测。</p>
 */
public final class VehicleState {

    public static final int TURN_NONE = 0;
    public static final int TURN_LEFT = 1;
    public static final int TURN_RIGHT = 2;

    /** 车门 / 座椅的位掩码：位 0 左前、位 1 右前、位 2 左后、位 3 右后。 */
    public static final int FRONT_LEFT = 1;
    public static final int FRONT_RIGHT = 1 << 1;
    public static final int REAR_LEFT = 1 << 2;
    public static final int REAR_RIGHT = 1 << 3;

    /** 每发布一份就 +1，画的人据此知道要不要重画。 */
    public final long version;

    /** {@link #TURN_NONE} / {@link #TURN_LEFT} / {@link #TURN_RIGHT}。 */
    public final Integer turnSignal;
    public final Boolean hazard;
    /** 方向盘转角（度），顺时针为正。 */
    public final Float steeringDegrees;
    /** 档位字母：P R N D S 等。 */
    public final String gear;
    /** 油门 / 刹车开度 0..1。 */
    public final Float throttle;
    public final Float brake;
    public final Float speedKmh;
    public final Boolean autoHold;
    public final Boolean adaptiveCruise;
    public final Boolean laneCentering;
    /** 开着的门（位掩码）。 */
    public final Integer doorsOpen;
    /** 没系安全带的座位（位掩码）。 */
    public final Integer beltsUnbuckled;
    public final Boolean daytimeRunningLights;
    public final Boolean lowBeam;
    public final Boolean highBeam;
    public final Boolean fogLights;
    public final Float odometerKm;
    public final Double latitude;
    public final Double longitude;

    private VehicleState(Builder b) {
        this.version = b.version;
        this.turnSignal = b.turnSignal;
        this.hazard = b.hazard;
        this.steeringDegrees = b.steeringDegrees;
        this.gear = b.gear;
        this.throttle = b.throttle;
        this.brake = b.brake;
        this.speedKmh = b.speedKmh;
        this.autoHold = b.autoHold;
        this.adaptiveCruise = b.adaptiveCruise;
        this.laneCentering = b.laneCentering;
        this.doorsOpen = b.doorsOpen;
        this.beltsUnbuckled = b.beltsUnbuckled;
        this.daytimeRunningLights = b.daytimeRunningLights;
        this.lowBeam = b.lowBeam;
        this.highBeam = b.highBeam;
        this.fogLights = b.fogLights;
        this.odometerKm = b.odometerKm;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
    }

    /** 什么都不知道。 */
    public static VehicleState empty() {
        return new Builder().build();
    }

    /** 在这份之上改几项，得到新的一份（版本号 +1）。 */
    public Builder edit() {
        return new Builder(this);
    }

    /** 有几项是有数据的（黑匣子那一行用）。 */
    public int knownCount() {
        int n = 0;
        Object[] all = {turnSignal, hazard, steeringDegrees, gear, throttle, brake, speedKmh,
                autoHold, adaptiveCruise, laneCentering, doorsOpen, beltsUnbuckled,
                daytimeRunningLights, lowBeam, highBeam, fogLights, odometerKm, latitude, longitude};
        for (Object o : all) {
            if (o != null) {
                n++;
            }
        }
        return n;
    }

    public static final class Builder {
        private long version;
        private Integer turnSignal;
        private Boolean hazard;
        private Float steeringDegrees;
        private String gear;
        private Float throttle;
        private Float brake;
        private Float speedKmh;
        private Boolean autoHold;
        private Boolean adaptiveCruise;
        private Boolean laneCentering;
        private Integer doorsOpen;
        private Integer beltsUnbuckled;
        private Boolean daytimeRunningLights;
        private Boolean lowBeam;
        private Boolean highBeam;
        private Boolean fogLights;
        private Float odometerKm;
        private Double latitude;
        private Double longitude;

        public Builder() {
        }

        private Builder(VehicleState s) {
            version = s.version + 1;
            turnSignal = s.turnSignal;
            hazard = s.hazard;
            steeringDegrees = s.steeringDegrees;
            gear = s.gear;
            throttle = s.throttle;
            brake = s.brake;
            speedKmh = s.speedKmh;
            autoHold = s.autoHold;
            adaptiveCruise = s.adaptiveCruise;
            laneCentering = s.laneCentering;
            doorsOpen = s.doorsOpen;
            beltsUnbuckled = s.beltsUnbuckled;
            daytimeRunningLights = s.daytimeRunningLights;
            lowBeam = s.lowBeam;
            highBeam = s.highBeam;
            fogLights = s.fogLights;
            odometerKm = s.odometerKm;
            latitude = s.latitude;
            longitude = s.longitude;
        }

        public Builder turnSignal(Integer v) { turnSignal = v; return this; }
        public Builder hazard(Boolean v) { hazard = v; return this; }
        public Builder steeringDegrees(Float v) { steeringDegrees = v; return this; }
        public Builder gear(String v) { gear = v; return this; }
        public Builder throttle(Float v) { throttle = clamp01(v); return this; }
        public Builder brake(Float v) { brake = clamp01(v); return this; }
        public Builder speedKmh(Float v) { speedKmh = v; return this; }
        public Builder autoHold(Boolean v) { autoHold = v; return this; }
        public Builder adaptiveCruise(Boolean v) { adaptiveCruise = v; return this; }
        public Builder laneCentering(Boolean v) { laneCentering = v; return this; }
        public Builder doorsOpen(Integer v) { doorsOpen = v; return this; }
        public Builder beltsUnbuckled(Integer v) { beltsUnbuckled = v; return this; }
        public Builder daytimeRunningLights(Boolean v) { daytimeRunningLights = v; return this; }
        public Builder lowBeam(Boolean v) { lowBeam = v; return this; }
        public Builder highBeam(Boolean v) { highBeam = v; return this; }
        public Builder fogLights(Boolean v) { fogLights = v; return this; }
        public Builder odometerKm(Float v) { odometerKm = v; return this; }
        public Builder position(Double lat, Double lon) { latitude = lat; longitude = lon; return this; }

        /** 改一个门 / 一个座位的那一位，其余位不动；之前不知道就从 0 起。 */
        public Builder door(int bit, boolean open) {
            int mask = doorsOpen == null ? 0 : doorsOpen;
            doorsOpen = open ? (mask | bit) : (mask & ~bit);
            return this;
        }

        public Builder belt(int bit, boolean unbuckled) {
            int mask = beltsUnbuckled == null ? 0 : beltsUnbuckled;
            beltsUnbuckled = unbuckled ? (mask | bit) : (mask & ~bit);
            return this;
        }

        public VehicleState build() {
            return new VehicleState(this);
        }

        private static Float clamp01(Float v) {
            if (v == null) {
                return null;
            }
            return Math.max(0f, Math.min(1f, v));
        }
    }
}
