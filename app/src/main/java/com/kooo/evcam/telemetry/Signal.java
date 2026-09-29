package com.kooo.evcam.telemetry;

import com.kooo.evcam.R;

/**
 * 车上能读到的信号，一张表：怎么读（功能号 / 带区域的功能号 / 传感器事件 / 传感器数值）、
 * 读到的数怎么解、在车上验证到什么程度。
 *
 * <p>信息条（{@link VehicleStateMapper}）和「系统信息」页都从这张表取，加一个信号就是加一行。
 * 号码和含义来自 zeekr-shortcut-lab 在 7X 上的实测（其 {@code docs/findings.md}），
 * {@link #verified} 为 true 的是操作车时看到它跟着变的，false 的读数合理但还没专门验证。</p>
 *
 * <p>解码是纯函数（{@code SignalDecodeTest}）：占位值 255 / 254 / 253 / -1 / -65535，浮点 255 / -65535 和
 * 绝对值小于 1e-6 的非零数，都算「没数据」。</p>
 */
public enum Signal {

    // ---- 行驶
    GEAR(Group.DRIVE, Kind.SENSOR_EVENT, 0x00200200, 0, R.string.vi_gear, true, Format.GEAR),
    SPEED(Group.DRIVE, Kind.SENSOR_VALUE, 0x00100100, 0, R.string.vi_speed, true, Format.MPS),
    IGNITION(Group.DRIVE, Kind.FUNCTION, 0x20259000, 0, R.string.vi_ignition, true, Format.IGNITION),
    BRAKE_PEDAL(Group.DRIVE, Kind.FUNCTION, 0x20317A00, 0, R.string.vi_brake_pedal, true, Format.ON_OFF),
    BRAKE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101300, 0, R.string.vi_brake_depth, true, Format.PERCENT),
    THROTTLE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101400, 0, R.string.vi_throttle_depth, true, Format.PERCENT),
    STEERING(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101000, 0, R.string.vi_steering, false, Format.DEGREES),
    /** 自动驻车的功能开关（设置项），不是「正在驻车」——信息条不用它。 */
    AUTO_HOLD(Group.DRIVE, Kind.FUNCTION, 0x20060400, 0, R.string.vi_auto_hold, true, Format.ON_OFF),

    // ---- 灯光
    INDICATOR(Group.LAMPS, Kind.FUNCTION, 0x2A091500, 0, R.string.vi_indicator, true, Format.INDICATOR),
    TURN_LEFT(Group.LAMPS, Kind.FUNCTION, 0x21051100, 0, R.string.vi_turn_left, true, Format.ON_OFF),
    TURN_RIGHT(Group.LAMPS, Kind.FUNCTION, 0x21051200, 0, R.string.vi_turn_right, true, Format.ON_OFF),
    LOW_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050100, 0, R.string.vi_low_beam, true, Format.ON_OFF),
    HIGH_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050200, 0, R.string.vi_high_beam, false, Format.ON_OFF),
    DRL(Group.LAMPS, Kind.FUNCTION, 0x21050900, 0, R.string.vi_drl, true, Format.ON_OFF),
    FRONT_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050400, 0, R.string.vi_front_fog, false, Format.ON_OFF),
    REAR_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050500, 0, R.string.vi_rear_fog, false, Format.ON_OFF),
    REVERSE_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050E00, 0, R.string.vi_reverse_lamp, true, Format.ON_OFF),
    STOP_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050D00, 0, R.string.vi_stop_lamp, true, Format.ON_OFF),

    // ---- 车身。区域值按 Lab 实测：0x1 = 主驾门（右舵车上是右前），0x4 = 副驾门，0x10 = 左后，0x40 = 右后，
    // 0x10000000 = 前备箱（ROOF_TOP），0x20000000 = 后备箱（VehicleZone 里没名字，直接传值）
    DOOR_DRIVER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x1, R.string.vi_door_driver, true, Format.DOOR),
    DOOR_PASSENGER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x4, R.string.vi_door_passenger, true, Format.DOOR),
    DOOR_REAR_LEFT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10, R.string.vi_door_rear_left, true, Format.DOOR),
    DOOR_REAR_RIGHT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x40, R.string.vi_door_rear_right, true, Format.DOOR),
    DOOR_FRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10000000, R.string.vi_door_frunk, true, Format.DOOR),
    DOOR_TRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x20000000, R.string.vi_door_trunk, true, Format.DOOR),
    CHARGE_PORT(Group.BODY, Kind.FUNCTION, 0x21020500, 0, R.string.vi_charge_port, true, Format.DOOR),
    SUNROOF_SHADE(Group.BODY, Kind.FUNCTION_ZONE, 0x20080100, 0x8, R.string.vi_sunroof_shade, true, Format.RAW),
    BELT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00201200, 0, R.string.vi_belt_driver, true, Format.BELT),
    BELT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00201300, 0, R.string.vi_belt_passenger, false, Format.BELT),
    BELT_REAR_LEFT(Group.BODY, Kind.SENSOR_EVENT, 0x00201800, 0, R.string.vi_belt_rear_left, false, Format.BELT),
    BELT_REAR_CENTER(Group.BODY, Kind.SENSOR_EVENT, 0x00201A00, 0, R.string.vi_belt_rear_center, false, Format.BELT),
    BELT_REAR_RIGHT(Group.BODY, Kind.SENSOR_EVENT, 0x00201900, 0, R.string.vi_belt_rear_right, false, Format.BELT),
    SEAT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00203300, 0, R.string.vi_seat_driver, true, Format.SEAT),
    SEAT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00203400, 0, R.string.vi_seat_passenger, true, Format.SEAT),

    // ---- 原厂界面
    STOCK_360(Group.STOCK, Kind.FUNCTION, 0x2031FE00, 0, R.string.vi_stock_360, true, Format.SHOWN),
    PARK_ASSIST(Group.STOCK, Kind.FUNCTION, 0x23030100, 0, R.string.vi_park_assist, true, Format.ON_OFF),

    // ---- 环境 / 车辆
    ODOMETER(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100700, 0, R.string.vi_odometer, false, Format.KM),
    BATTERY(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00404000, 0, R.string.vi_battery, false, Format.PERCENT_RAW),
    RANGE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100800, 0, R.string.vi_range, false, Format.KM),
    TEMP_OUTSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100B00, 0, R.string.vi_temp_outside, false, Format.CELSIUS),
    TEMP_INSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100C00, 0, R.string.vi_temp_inside, false, Format.CELSIUS),

    // ---- 安全辅助（读的是开关）
    AEB(Group.ASSIST, Kind.FUNCTION, 0x20070E00, 0, R.string.vi_aeb, false, Format.ON_OFF),
    FCW(Group.ASSIST, Kind.FUNCTION, 0x200E0100, 0, R.string.vi_fcw, false, Format.ON_OFF),
    LDW(Group.ASSIST, Kind.FUNCTION, 0x28084100, 0, R.string.vi_ldw, false, Format.ON_OFF),
    LKA(Group.ASSIST, Kind.FUNCTION, 0x20070100, 0, R.string.vi_lka, false, Format.ON_OFF),
    BSD(Group.ASSIST, Kind.FUNCTION, 0x28081600, 0, R.string.vi_bsd, false, Format.ON_OFF),
    RCW(Group.ASSIST, Kind.FUNCTION, 0x20071000, 0, R.string.vi_rcw, false, Format.ON_OFF),
    LCC(Group.ASSIST, Kind.FUNCTION, 0x28085B00, 0, R.string.vi_lcc, false, Format.ON_OFF);

    /** 怎么读。 */
    public enum Kind {
        /** {@code ICarFunction.getFunctionValue(id)} */
        FUNCTION,
        /** {@code ICarFunction.getFunctionValue(id, zone)} */
        FUNCTION_ZONE,
        /** {@code ISensor.getSensorEvent(type)}：枚举，值 = 类型 + 序号 */
        SENSOR_EVENT,
        /** {@code ISensor.getSensorLatestValue(type)}：浮点 */
        SENSOR_VALUE
    }

    /** 页面上的分组。 */
    public enum Group {
        DRIVE(R.string.vi_group_drive),
        LAMPS(R.string.vi_group_lamps),
        BODY(R.string.vi_group_body),
        STOCK(R.string.vi_group_stock),
        VEHICLE(R.string.vi_group_vehicle),
        ASSIST(R.string.vi_group_assist);

        public final int labelRes;

        Group(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** 读到的数是什么意思；也决定页面上怎么写。 */
    public enum Format {
        /** 0 关 1 开 → Boolean */
        ON_OFF,
        /** 0 关着 1 开着 → Boolean（开着为 true）；开关过程中的 0x…01 算没数据 */
        DOOR,
        /** 1 系着 0 没系 → Boolean（系着为 true） */
        BELT,
        /** 低字节 1 没人 2 有人 → Boolean（有人为 true） */
        SEAT,
        /** 1 显示中 2 平时 → Boolean（显示中为 true） */
        SHOWN,
        /** 0 关 1 左 2 右 3 双闪 → Integer */
        INDICATOR,
        /** 点火状态的枚举码 → Integer（0x00200104 ACC、05 ON、07 DRIVING） */
        IGNITION,
        /** 档位枚举 → 字母 P / R / N / D */
        GEAR,
        /** 原样的整数（量程待定） → Integer */
        RAW,
        /** 传感器给的是 m/s（0.2778 = 1 km/h）→ 乘 3.6 记成 km/h（Float） */
        MPS,
        /** 浮点 → Float */
        KMH, KM, PERCENT, PERCENT_RAW, DEGREES, CELSIUS
    }

    /**
     * 方向盘转角：读数 → 方向盘转过的度数（信息条上的数字和图标的转动都用它）。
     * 满舵几圈、满舵时读数多少还没测（等 Lab），先按读数就是度数（1:1）。
     */
    public static final float STEERING_DEGREES_PER_UNIT = 1f;

    public static final int IGNITION_ACC = 0x00200104;
    public static final int IGNITION_ON = 0x00200105;
    public static final int IGNITION_DRIVING = 0x00200107;

    public final Group group;
    public final Kind kind;
    public final int id;
    /** 带区域读时的区域值；不带区域的为 0。 */
    public final int zone;
    public final int labelRes;
    /** 车上操作时看到它跟着变过。 */
    public final boolean verified;
    public final Format format;

    Signal(Group group, Kind kind, int id, int zone, int labelRes, boolean verified, Format format) {
        this.group = group;
        this.kind = kind;
        this.id = id;
        this.zone = zone;
        this.labelRes = labelRes;
        this.verified = verified;
        this.format = format;
    }

    public boolean isSensor() {
        return kind == Kind.SENSOR_EVENT || kind == Kind.SENSOR_VALUE;
    }

    /** 浮点传感器：不订阅，按节拍读（变一点就推，订了只是噪声）。 */
    public boolean isFloat() {
        return kind == Kind.SENSOR_VALUE;
    }

    // ================================================================= 解码（纯函数）

    /**
     * 原始读数 → 按 {@link #format} 归一的值；没数据或解不开返回 null。
     *
     * @param raw Integer（功能号、传感器事件）或 Float（传感器数值）
     */
    public Object decode(Object raw) {
        if (raw == null) {
            return null;
        }
        if (format == Format.DEGREES) {
            Float units = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return units == null ? null : units * STEERING_DEGREES_PER_UNIT;
        }
        if (format == Format.MPS) {
            Float mps = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return mps == null ? null : mps * 3.6f;
        }
        if (format == Format.KMH || format == Format.KM || format == Format.PERCENT
                || format == Format.PERCENT_RAW || format == Format.DEGREES || format == Format.CELSIUS) {
            return raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
        }
        if (!(raw instanceof Number)) {
            return null;
        }
        Integer v = intOrNull(((Number) raw).intValue());
        if (v == null) {
            return null;
        }
        switch (format) {
            case ON_OFF:
            case DOOR:
                return onOff(v);
            case BELT:
                return onOff(v);
            case SEAT:
                switch (v & 0xFF) {
                    case 1: return Boolean.FALSE;
                    case 2: return Boolean.TRUE;
                    default: return null;
                }
            case SHOWN:
                return v == 1 ? Boolean.TRUE : (v == 2 ? Boolean.FALSE : null);
            case INDICATOR:
                return v >= 0 && v <= 3 ? v : null;
            case IGNITION:
            case RAW:
                return v;
            case GEAR:
                return gearLetter(v);
            default:
                return null;
        }
    }

    /** ECARX 的整数占位值：255 未知、254 无、253 错误，车上还见过 -1 和 -65535。 */
    static Integer intOrNull(int v) {
        if (v == 255 || v == 254 || v == 253 || v == -1 || v == -65535) {
            return null;
        }
        return v;
    }

    /** 浮点占位值：255、-65535，以及「没数据」时那个极小的非零数。0 是真的 0。 */
    static Float floatOrNull(float v) {
        if (Float.isNaN(v) || v == 255f || v == -65535f) {
            return null;
        }
        if (v != 0f && Math.abs(v) < 1e-6f) {
            return null;
        }
        return v;
    }

    /** 0 关 / 1 开；别的（2 默认、开关过程中的中间态）算不知道。 */
    static Boolean onOff(Integer v) {
        if (v == null) {
            return null;
        }
        if (v == 0) {
            return Boolean.FALSE;
        }
        if (v == 1) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** 档位传感器事件：P = 0x00200230、R = 0x00200240、N = 0x00200210、D = 0x00200220（车上实测）。 */
    static String gearLetter(int event) {
        switch (event) {
            case 0x00200230: return "P";
            case 0x00200240: return "R";
            case 0x00200210: return "N";
            case 0x00200220: return "D";
            default: return null;
        }
    }
}
