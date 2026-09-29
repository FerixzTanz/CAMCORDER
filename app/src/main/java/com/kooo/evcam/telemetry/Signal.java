package com.kooo.evcam.telemetry;

import com.kooo.evcam.R;

/**
 * 车上能读到的信号，一张表：怎么读（功能号 / 带区域的功能号 / 传感器事件 / 传感器数值）、
 * 读到的数怎么解、在车上验证到什么程度。
 *
 * <p>信息条（{@link VehicleStateMapper}）和「系统信息」页都从这张表取，加一个信号就是加一行。
 * 号码和含义来自 zeekr-shortcut-lab 在 7X 上的实测：它的信号手册 {@code docs/signals.md} 是唯一依据
 * （分工：Lab 把信号找准，这里把找到的用准）。</p>
 *
 * <p>每条信号有一个可信程度（{@link Trust}）：确认了的、先用着的、没验证的。
 * 信息条默认只启用信号都{@linkplain #usable() 能用}的栏目，其余划掉。</p>
 *
 * <p>解码是纯函数（{@code SignalDecodeTest}）：占位值 255 / 254 / 253 / -1 / -65535，浮点 255 / -65535 和
 * 绝对值小于 1e-6 的非零数，都算「没数据」。</p>
 */
public enum Signal {

    // ---- 行驶
    GEAR(Group.DRIVE, Kind.SENSOR_EVENT, 0x00200200, 0, R.string.vi_gear, Trust.CONFIRMED, Format.GEAR),
    SPEED(Group.DRIVE, Kind.SENSOR_VALUE, 0x00100100, 0, R.string.vi_speed, Trust.CONFIRMED, Format.MPS),
    IGNITION(Group.DRIVE, Kind.FUNCTION, 0x20259000, 0, R.string.vi_ignition, Trust.CONFIRMED, Format.IGNITION),
    BRAKE_PEDAL(Group.DRIVE, Kind.FUNCTION, 0x20317A00, 0, R.string.vi_brake_pedal, Trust.CONFIRMED, Format.ON_OFF),
    /** 跟着变（停车踩下 11–15），踩到底是多少没测（Lab 0.13.0）；先按 0–100 画。用户定：保留。 */
    BRAKE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101300, 0, R.string.vi_brake_depth, Trust.PROVISIONAL, Format.PERCENT),
    /** 行驶中跟着变，踩到底是多少没测（Lab 0.13.0）；先按 0–100 画。用户定：保留。 */
    THROTTLE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101400, 0, R.string.vi_throttle_depth, Trust.PROVISIONAL, Format.PERCENT),
    /** 开车时跟着变；单位、正负、满舵读数和圈数没测（Lab 0.13.0），先按读数就是度数。用户定：能用，开放。 */
    STEERING(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101000, 0, R.string.vi_steering, Trust.PROVISIONAL, Format.DEGREES),
    /** 自动驻车的功能开关（设置项），不是「正在驻车」——信息条不用它。 */
    AUTO_HOLD(Group.DRIVE, Kind.FUNCTION, 0x20060400, 0, R.string.vi_auto_hold, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 灯光
    INDICATOR(Group.LAMPS, Kind.FUNCTION, 0x2A091500, 0, R.string.vi_indicator, Trust.CONFIRMED, Format.INDICATOR),
    TURN_LEFT(Group.LAMPS, Kind.FUNCTION, 0x21051100, 0, R.string.vi_turn_left, Trust.CONFIRMED, Format.ON_OFF),
    TURN_RIGHT(Group.LAMPS, Kind.FUNCTION, 0x21051200, 0, R.string.vi_turn_right, Trust.CONFIRMED, Format.ON_OFF),
    LOW_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050100, 0, R.string.vi_low_beam, Trust.CONFIRMED, Format.ON_OFF),
    HIGH_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050200, 0, R.string.vi_high_beam, Trust.CONFIRMED, Format.ON_OFF),
    /** 近光没亮时才是 1；晚上前灯带亮着时读 0（那时报的是前位置灯）。白天自动档待 Lab 复核，接法先不动。用户定：保留。 */
    DRL(Group.LAMPS, Kind.FUNCTION, 0x21050900, 0, R.string.vi_drl, Trust.PROVISIONAL, Format.ON_OFF),
    FRONT_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050400, 0, R.string.vi_front_fog, Trust.UNVERIFIED, Format.ON_OFF),
    REAR_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050500, 0, R.string.vi_rear_fog, Trust.UNVERIFIED, Format.ON_OFF),
    FRONT_POSITION_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050800, 0, R.string.vi_front_position, Trust.CONFIRMED, Format.ON_OFF),
    REAR_POSITION_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050C00, 0, R.string.vi_rear_position, Trust.CONFIRMED, Format.ON_OFF),
    LIGHT_SWITCH(Group.LAMPS, Kind.FUNCTION, 0x20040E00, 0, R.string.vi_light_switch, Trust.CONFIRMED, Format.LIGHT_SWITCH),
    REVERSE_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050E00, 0, R.string.vi_reverse_lamp, Trust.CONFIRMED, Format.ON_OFF),
    STOP_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050D00, 0, R.string.vi_stop_lamp, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 车身。区域值按 Lab 实测：0x1 = 主驾门（右舵车上是右前），0x4 = 副驾门，0x10 = 左后，0x40 = 右后，
    // 0x10000000 = 前备箱（ROOF_TOP），0x20000000 = 后备箱（VehicleZone 里没名字，直接传值）
    DOOR_DRIVER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x1, R.string.vi_door_driver, Trust.CONFIRMED, Format.DOOR),
    DOOR_PASSENGER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x4, R.string.vi_door_passenger, Trust.CONFIRMED, Format.DOOR),
    DOOR_REAR_LEFT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10, R.string.vi_door_rear_left, Trust.CONFIRMED, Format.DOOR),
    DOOR_REAR_RIGHT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x40, R.string.vi_door_rear_right, Trust.CONFIRMED, Format.DOOR),
    DOOR_FRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10000000, R.string.vi_door_frunk, Trust.CONFIRMED, Format.DOOR),
    DOOR_TRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x20000000, R.string.vi_door_trunk, Trust.CONFIRMED, Format.DOOR),
    CHARGE_PORT(Group.BODY, Kind.FUNCTION, 0x21020500, 0, R.string.vi_charge_port, Trust.CONFIRMED, Format.DOOR),
    SUNROOF_SHADE(Group.BODY, Kind.FUNCTION_ZONE, 0x20080100, 0x8, R.string.vi_sunroof_shade, Trust.CONFIRMED, Format.RAW),
    BELT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00201200, 0, R.string.vi_belt_driver, Trust.CONFIRMED, Format.BELT),
    BELT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00201300, 0, R.string.vi_belt_passenger, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_LEFT(Group.BODY, Kind.SENSOR_EVENT, 0x00201800, 0, R.string.vi_belt_rear_left, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_CENTER(Group.BODY, Kind.SENSOR_EVENT, 0x00201A00, 0, R.string.vi_belt_rear_center, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_RIGHT(Group.BODY, Kind.SENSOR_EVENT, 0x00201900, 0, R.string.vi_belt_rear_right, Trust.UNVERIFIED, Format.BELT),
    SEAT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00203300, 0, R.string.vi_seat_driver, Trust.CONFIRMED, Format.SEAT),
    SEAT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00203400, 0, R.string.vi_seat_passenger, Trust.CONFIRMED, Format.SEAT),

    // ---- 原厂界面
    STOCK_360(Group.STOCK, Kind.FUNCTION, 0x2031FE00, 0, R.string.vi_stock_360, Trust.CONFIRMED, Format.SHOWN),
    PARK_ASSIST(Group.STOCK, Kind.FUNCTION, 0x23030100, 0, R.string.vi_park_assist, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 环境 / 车辆
    ODOMETER(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100700, 0, R.string.vi_odometer, Trust.CONFIRMED, Format.KM),
    BATTERY(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00404000, 0, R.string.vi_battery, Trust.CONFIRMED, Format.PERCENT_RAW),
    RANGE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100800, 0, R.string.vi_range, Trust.CONFIRMED, Format.KM),
    TEMP_OUTSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100B00, 0, R.string.vi_temp_outside, Trust.CONFIRMED, Format.CELSIUS),
    TEMP_INSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100C00, 0, R.string.vi_temp_inside, Trust.CONFIRMED, Format.CELSIUS),
    BATTERY_TEMP(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00102A00, 0, R.string.vi_battery_temp, Trust.CONFIRMED, Format.CELSIUS),

    // ---- 安全辅助（读的是开关）
    AEB(Group.ASSIST, Kind.FUNCTION, 0x20070E00, 0, R.string.vi_aeb, Trust.CONFIRMED, Format.ON_OFF),
    /** 前碰预警的灵敏度：0 关，低 / 中 / 高是 0x200E0201–03（原来读的 0x200E0100 一直 255）。 */
    FCW(Group.ASSIST, Kind.FUNCTION, 0x200E0200, 0, R.string.vi_fcw, Trust.CONFIRMED, Format.LEVEL),
    LDW(Group.ASSIST, Kind.FUNCTION, 0x28084100, 0, R.string.vi_ldw, Trust.UNVERIFIED, Format.ON_OFF),
    LKA(Group.ASSIST, Kind.FUNCTION, 0x20070100, 0, R.string.vi_lka, Trust.CONFIRMED, Format.ON_OFF),
    BSD(Group.ASSIST, Kind.FUNCTION, 0x28081600, 0, R.string.vi_bsd, Trust.UNVERIFIED, Format.ON_OFF),
    RCW(Group.ASSIST, Kind.FUNCTION, 0x20071000, 0, R.string.vi_rcw, Trust.CONFIRMED, Format.ON_OFF),
    LANE_CHANGE_ASSIST(Group.ASSIST, Kind.FUNCTION, 0x20070300, 0, R.string.vi_lane_change_assist, Trust.CONFIRMED, Format.ON_OFF),
    AUTO_LANE_CHANGE(Group.ASSIST, Kind.FUNCTION, 0x28040100, 0, R.string.vi_auto_lane_change, Trust.CONFIRMED, Format.ON_OFF),
    DOOR_OPEN_WARNING(Group.ASSIST, Kind.FUNCTION, 0x20120100, 0, R.string.vi_door_open_warning, Trust.CONFIRMED, Format.ON_OFF),
    LCC(Group.ASSIST, Kind.FUNCTION, 0x28085B00, 0, R.string.vi_lcc, Trust.UNVERIFIED, Format.ON_OFF);

    /** 可信到什么程度。 */
    public enum Trust {
        /** Lab 手册「确认了的」一节里有它，这里用到的含义、取值、量程都没挂着「待测」 */
        CONFIRMED,
        /** 车上跟着变、能用，但量程 / 比例 / 某个场景还等 Lab 测；用户点名先开放的（方向盘、油门刹车、日行灯） */
        PROVISIONAL,
        /** 读得到，含义没对上，或者还没专门测过 */
        UNVERIFIED
    }

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
        /** 0 关，其余是档位（枚举）→ Boolean（不是 0 就算开） */
        LEVEL,
        /** 灯光开关的位置 → Integer（0 关、0x20040E01 位置灯、0x20040E03 自动） */
        LIGHT_SWITCH,
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

    public static final int LIGHT_SWITCH_POSITION = 0x20040E01;
    public static final int LIGHT_SWITCH_AUTO = 0x20040E03;

    public static final int IGNITION_ACC = 0x00200104;
    public static final int IGNITION_ON = 0x00200105;
    public static final int IGNITION_DRIVING = 0x00200107;

    public final Group group;
    public final Kind kind;
    public final int id;
    /** 带区域读时的区域值；不带区域的为 0。 */
    public final int zone;
    public final int labelRes;
    public final Trust trust;
    public final Format format;

    Signal(Group group, Kind kind, int id, int zone, int labelRes, Trust trust, Format format) {
        this.group = group;
        this.kind = kind;
        this.id = id;
        this.zone = zone;
        this.labelRes = labelRes;
        this.trust = trust;
        this.format = format;
    }

    /** 能不能直接用：确认了的，或者先用着的。 */
    public boolean usable() {
        return trust != Trust.UNVERIFIED;
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
            case LEVEL:
                return v != 0;
            case IGNITION:
            case LIGHT_SWITCH:
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
