package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 车辆信号这一路：车机系统自带的 ECARX 车辆接口（{@code com.ecarx.xui.adaptapi}），全反射，只读。
 *
 * <h3>为什么是它</h3>
 *
 * <p>安卓标准的 {@code android.car} 在 App Lab 容器里结构性拿不到（平台笔记）。ECARX 这一套
 * 由 zeekr-shortcut-lab 在 7X 上、在容器里验证过（2026-09-28）：{@code ecarx.adaptapi.impl.jar}
 * 在 BOOTCLASSPATH 上，任何应用都能 {@code Car.create(context)}，不要任何车辆权限；
 * 读一次 0.2–0.9 ms，{@code Car.create} 约 1 秒（所以在自己的线程上做，只做一次）。
 * 事实（类名、方法名、功能号、取值含义）来自那边的 {@code docs/findings.md}，代码按本项目的框架另写。</p>
 *
 * <h3>怎么读</h3>
 *
 * <p>每 {@link #POLL_MS} 读一轮：车辆功能用 {@code ICarFunction.getFunctionValue(功能号[, 区域])}，
 * 传感器用 {@code ISensor.getSensorEvent(类型)}（枚举）和 {@code getSensorLatestValue(类型)}（浮点）。
 * 一轮二十来次 binder 调用，十毫秒上下。只有轮询这一条路：转向灯本来就要按 100–200 ms 采样才能跟上闪烁，
 * 车上的回调接口收不收得到还没验证，先不多一套机制。</p>
 *
 * <p>占位值：整数 255 / 254 / 253 / -1 / -65535，浮点 255 / -65535 和绝对值小于 1e-6 的非零数，
 * 都算「没数据」（{@link #intOrNull} / {@link #floatOrNull}）。</p>
 *
 * <h3>哪些已经在车上验证过、哪些还没有</h3>
 *
 * <p>验证过跟着变的：左右转向灯、档位 P/R/D、刹车踏板、刹车深度、主驾门、近光、日行灯、自动驻车、原厂 360 显示状态。
 * 读得到但还没操作验证：车速（单位按 km/h 记）、油门深度、方向盘转角（单位按度记）、远光、雾灯、
 * 其余三扇门（区域按值对左右，右舵车上哪个区域是哪扇门待对）、安全带（0 / 1 的含义待对）、车道居中。
 * 没找到读法的：ACC。这些在信息条上照样画，车上看它们跟不跟着变。</p>
 */
final class EcarxSource {

    private static final String TAG = "EcarxSource";

    static final String CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car";
    static final String ZONE_CLASS = "com.ecarx.xui.adaptapi.vehicle.VehicleZone";
    static final String SENSOR_CLASS = "com.ecarx.xui.adaptapi.car.sensor.ISensor";

    /** 多久读一轮：转向灯半个周期约 365 ms，200 ms 一次每个亮相都采得到。 */
    static final long POLL_MS = 200L;

    // ---- 车辆功能（ICarFunction.getFunctionValue），IBcm / IVehicle / IADAS 的常量值 ----
    static final int F_TURN_LEFT = 0x21051100;      // BCM_FUNC_LIGHT_LEFT_TRUN_SIGNAL（原文拼写）
    static final int F_TURN_RIGHT = 0x21051200;     // BCM_FUNC_LIGHT_RIGHT_TRUN_SIGNAL
    static final int F_LOW_BEAM = 0x21050100;       // BCM_FUNC_LIGHT_DIPPED_BEAM
    static final int F_HIGH_BEAM = 0x21050200;      // BCM_FUNC_LIGHT_MAIN_BEAM
    static final int F_FRONT_FOG = 0x21050400;      // BCM_FUNC_LIGHT_FRONT_FOG_LAMPS
    static final int F_REAR_FOG = 0x21050500;       // BCM_FUNC_LIGHT_REAR_FOG_LAMPS
    static final int F_DRL = 0x21050900;            // BCM_FUNC_LIGHT_DAYTIME_RUNNING_LAMPS
    static final int F_DOOR = 0x21020100;           // BCM_FUNC_DOOR，带区域：0 关 1 开
    static final int F_AUTO_HOLD = 0x20060400;      // SETTING_FUNC_AUTO_HOLD
    static final int F_LCC_ACTIVE = 0x28085B00;     // SETTING_FUNC_LCC_ACTIVE_STATE（静止时 255，编码待验证）
    static final int F_AVM_SHOW = 0x2031FE00;       // SETTING_FUNC_AVM_SHOW_STATUS：2 平时，1 原厂 360 画面显示中（验证过）

    // ---- 传感器（ISensor），类型值 ----
    static final int S_SPEED = 0x00100100;          // SENSOR_TYPE_CAR_SPEED，getSensorLatestValue
    static final int S_ODOMETER = 0x00100700;       // SENSOR_TYPE_ODOMETER，km
    static final int S_STEERING = 0x00101000;       // SENSOR_TYPE_STEERING_WHEEL_ANGLE
    static final int S_BRAKE_DEPTH = 0x00101300;    // SENSOR_TYPE_BRAKE_DEPTH，踩下 11–15
    static final int S_THROTTLE_DEPTH = 0x00101400; // SENSOR_TYPE_ACCELERATOR_DEPTH
    static final int S_GEAR = 0x00200200;           // SENSOR_TYPE_GEAR，getSensorEvent：值 = 类型 + 序号
    static final int S_BELT_DRIVER = 0x00201200;    // SENSOR_TYPE_SAFE_BELT_DRIVER，getSensorEvent
    static final int S_BELT_PASSENGER = 0x00201300;
    static final int S_BELT_ROW2_LEFT = 0x00201800;
    static final int S_BELT_ROW2_RIGHT = 0x00201900;

    /** 车门的四个区域：名字按车上的 {@code VehicleZone} 取值；取不到时用极氪 OS 6.0.5 的值。 */
    private static final String[] DOOR_ZONES = {"ROW_1_DRVR", "ROW_1_PASS", "ROW_2_LEFT", "ROW_2_RIGHT"};
    private static final int[] DOOR_ZONE_FALLBACK = {0x1, 0x4, 0x10, 0x40};
    private static final int[] DOOR_BITS = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
            VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT};

    /** 档位枚举里认得的几个：值 = 0x00200200 + 序号，序号从车上的 ISensor.GEAR_* 常量按名字取；取不到时用这份。 */
    private static final int[] GEAR_FALLBACK_VALUES = {0x00200220, 0x00200230, 0x00200240};
    private static final String[] GEAR_FALLBACK_LETTERS = {"D", "P", "R"};

    /** 车辆对象整个进程只建一次：Car.create 要一秒。 */
    private static volatile Object cachedCar;

    private final Telemetry telemetry;
    private final TurnSignalHold turn = new TurnSignalHold();
    private HandlerThread thread;
    private Handler handler;
    private volatile String status = "not started";
    private volatile boolean stopped;

    private Object function;
    private Object sensor;
    private Method getFunctionValue;
    private Method getFunctionValueZoned;
    private Method getSensorEvent;
    private Method getSensorLatestValue;
    private final int[] doorZones = DOOR_ZONE_FALLBACK.clone();
    private final Map<Integer, String> gearLetters = new LinkedHashMap<>();
    private boolean firstRoundReported;

    private final Runnable poll = this::pollOnce;

    EcarxSource(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    void start(Context context) {
        final Context app = context.getApplicationContext();
        thread = new HandlerThread("Telemetry-Ecarx");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> connect(app));
    }

    void stop() {
        stopped = true;
        Handler h = handler;
        HandlerThread t = thread;
        handler = null;
        thread = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (t != null) {
            t.quitSafely();
        }
        status = "stopped";
    }

    String status() {
        return status;
    }

    // ================================================================= 连接（在自己的线程上）

    private void connect(Context context) {
        long start = SystemClock.elapsedRealtime();
        try {
            Class<?> carClass = Class.forName(CAR_CLASS);
            Object car = cachedCar;
            if (car == null) {
                Method create = match(carClass.getMethods(), "create", Context.class);
                if (create == null) {
                    status = "no Car.create(Context)";
                    report();
                    return;
                }
                car = create.invoke(null, context);
                if (car == null) {
                    status = "Car.create returned null";
                    report();
                    return;
                }
                cachedCar = car;
            }
            function = call(car, "getICarFunction");
            sensor = call(car, "getSensorManager");
            if (function != null) {
                getFunctionValue = findMethod(function, "getFunctionValue", int.class);
                getFunctionValueZoned = findMethod(function, "getFunctionValue", int.class, int.class);
            }
            if (sensor != null) {
                getSensorEvent = findMethod(sensor, "getSensorEvent", int.class);
                getSensorLatestValue = findMethod(sensor, "getSensorLatestValue", int.class);
            }
            if (getFunctionValue == null && getSensorEvent == null) {
                status = "no readable managers (function=" + (function != null) + ", sensor=" + (sensor != null) + ")";
                report();
                return;
            }
            String zones = loadZones(carClass.getClassLoader());
            String gears = loadGearNames(carClass.getClassLoader());
            status = String.format(Locale.US, "connected in %d ms; function=%s zoned=%s sensorEvent=%s sensorValue=%s; zones %s; gears %s",
                    SystemClock.elapsedRealtime() - start, getFunctionValue != null, getFunctionValueZoned != null,
                    getSensorEvent != null, getSensorLatestValue != null, zones, gears);
            report();
            if (!stopped && handler != null) {
                handler.post(poll);
            }
        } catch (ClassNotFoundException e) {
            status = "no " + CAR_CLASS + " on this head unit";
            report();
        } catch (Throwable t) {
            status = "connect failed: " + describe(t);
            report();
        }
    }

    private void report() {
        AppLog.i(TAG, status);
        telemetry.sourceReported("ecarx", status);
    }

    /** 车门区域的值按名字从车上的 VehicleZone 取；取不到的留 6.0.5 的值。 */
    private String loadZones(ClassLoader loader) {
        try {
            Class<?> zoneClass = Class.forName(ZONE_CLASS, false, loader);
            int found = 0;
            for (int i = 0; i < DOOR_ZONES.length; i++) {
                try {
                    Field f = zoneClass.getField("ZONE_" + DOOR_ZONES[i]);
                    doorZones[i] = f.getInt(null);
                    found++;
                } catch (NoSuchFieldException ignored) {
                    // 这台车的 VehicleZone 没有这个名字：留后备值
                }
            }
            return "from car " + found + "/" + DOOR_ZONES.length;
        } catch (Throwable t) {
            return "fallback (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** 档位枚举：ISensor 里 GEAR_* 常量的值 → 字母。 */
    private String loadGearNames(ClassLoader loader) {
        gearLetters.clear();
        try {
            Class<?> sensorClass = Class.forName(SENSOR_CLASS, false, loader);
            for (Field f : sensorClass.getFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class
                        || !f.getName().startsWith("GEAR_")) {
                    continue;
                }
                String letter = gearLetter(f.getName());
                if (letter != null) {
                    gearLetters.put(f.getInt(null), letter);
                }
            }
        } catch (Throwable t) {
            // 没有这个类：用后备表
        }
        if (gearLetters.isEmpty()) {
            for (int i = 0; i < GEAR_FALLBACK_VALUES.length; i++) {
                gearLetters.put(GEAR_FALLBACK_VALUES[i], GEAR_FALLBACK_LETTERS[i]);
            }
            return "fallback";
        }
        return "from car " + gearLetters.size();
    }

    // ================================================================= 轮询

    private void pollOnce() {
        if (stopped) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        final Boolean left = onOff(readFunction(F_TURN_LEFT));
        final Boolean right = onOff(readFunction(F_TURN_RIGHT));
        final TurnSignalHold.Result turnResult = turn.update(now, left, right);
        final Boolean lowBeam = onOff(readFunction(F_LOW_BEAM));
        final Boolean highBeam = onOff(readFunction(F_HIGH_BEAM));
        final Boolean fog = either(onOff(readFunction(F_FRONT_FOG)), onOff(readFunction(F_REAR_FOG)));
        final Boolean drl = onOff(readFunction(F_DRL));
        final Boolean autoHold = onOff(readFunction(F_AUTO_HOLD));
        final Boolean lcc = onOff(readFunction(F_LCC_ACTIVE));
        final Boolean stock360 = avmShown(readFunction(F_AVM_SHOW));
        final Boolean[] doors = new Boolean[DOOR_ZONES.length];
        for (int i = 0; i < DOOR_ZONES.length; i++) {
            doors[i] = onOff(readFunctionZoned(F_DOOR, doorZones[i]));
        }
        final Float speed = readFloat(S_SPEED);
        final Float odometer = readFloat(S_ODOMETER);
        final Float steering = readFloat(S_STEERING);
        final Float brake = percent(readFloat(S_BRAKE_DEPTH));
        final Float throttle = percent(readFloat(S_THROTTLE_DEPTH));
        final Integer gearEvent = readEvent(S_GEAR);
        final String gear = gearEvent == null ? null : gearLetters.get(gearEvent);
        final Boolean[] belts = {
                unbuckled(readEvent(S_BELT_DRIVER)), unbuckled(readEvent(S_BELT_PASSENGER)),
                unbuckled(readEvent(S_BELT_ROW2_LEFT)), unbuckled(readEvent(S_BELT_ROW2_RIGHT))};

        if (speed != null) {
            telemetry.noteCarSpeed();
        }
        telemetry.edit(b -> {
            b.turnSignal(turnResult.turn);
            b.hazard(turnResult.hazard);
            b.lowBeam(lowBeam);
            b.highBeam(highBeam);
            b.fogLights(fog);
            b.daytimeRunningLights(drl);
            b.autoHold(autoHold);
            b.laneCentering(lcc);
            b.stockSurroundShown(stock360);
            b.doorsOpen(mask(doors));
            b.beltsUnbuckled(mask(belts));
            if (speed != null) {
                b.speedKmh(speed);
            }
            b.odometerKm(odometer);
            b.steeringDegrees(steering);
            b.brake(brake);
            b.throttle(throttle);
            b.gear(gear);
        });

        if (!firstRoundReported) {
            firstRoundReported = true;
            String summary = "turn=" + name(left) + "/" + name(right) + " low=" + name(lowBeam) + " high=" + name(highBeam)
                    + " fog=" + name(fog) + " drl=" + name(drl) + " hold=" + name(autoHold) + " lcc=" + name(lcc)
                    + " avm=" + name(stock360)
                    + " doors=" + name(doors[0]) + name(doors[1]) + name(doors[2]) + name(doors[3])
                    + " belts=" + name(belts[0]) + name(belts[1]) + name(belts[2]) + name(belts[3])
                    + " speed=" + speed + " odo=" + odometer + " steer=" + steering + " brake=" + brake
                    + " throttle=" + throttle + " gear=" + gear + "(" + (gearEvent == null ? "-" : Integer.toHexString(gearEvent)) + ")";
            com.kooo.evcam.blackbox.BlackBox.note("行驶信息 ecarx 第一轮读数：" + summary);
        }
        Handler h = handler;
        if (!stopped && h != null) {
            h.postDelayed(poll, POLL_MS);
        }
    }

    private Integer readFunction(int id) {
        if (getFunctionValue == null) {
            return null;
        }
        try {
            Object v = getFunctionValue.invoke(function, id);
            return v instanceof Number ? intOrNull(((Number) v).intValue()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Integer readFunctionZoned(int id, int zone) {
        if (getFunctionValueZoned == null) {
            return null;
        }
        try {
            Object v = getFunctionValueZoned.invoke(function, id, zone);
            return v instanceof Number ? intOrNull(((Number) v).intValue()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Integer readEvent(int type) {
        if (getSensorEvent == null) {
            return null;
        }
        try {
            Object v = getSensorEvent.invoke(sensor, type);
            return v instanceof Number ? intOrNull(((Number) v).intValue()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Float readFloat(int type) {
        if (getSensorLatestValue == null) {
            return null;
        }
        try {
            Object v = getSensorLatestValue.invoke(sensor, type);
            return v instanceof Number ? floatOrNull(((Number) v).floatValue()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================= 解码（纯函数，EcarxDecodeTest）

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

    /** 0 关 / 1 开；别的（2 默认之类）算不知道。 */
    static Boolean onOff(Integer v) {
        if (v == null) {
            return null;
        }
        if (v == 0) {
            return false;
        }
        if (v == 1) {
            return true;
        }
        return null;
    }

    /** 两盏里有一盏亮就算亮；都不知道才是不知道。 */
    static Boolean either(Boolean a, Boolean b) {
        if (a == null && b == null) {
            return null;
        }
        return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
    }

    /** 原厂 360 显示状态：1 显示中、2 平时；别的算不知道。 */
    static Boolean avmShown(Integer v) {
        if (v == null) {
            return null;
        }
        if (v == 1) {
            return true;
        }
        if (v == 2) {
            return false;
        }
        return null;
    }

    /** 深度 0–100 → 0..1。 */
    static Float percent(Float v) {
        return v == null ? null : v / 100f;
    }

    /** 安全带事件：1 系着 / 0 没系（含义待车上对：主驾坐着时读到 0）。 */
    static Boolean unbuckled(Integer v) {
        Boolean buckled = onOff(v);
        return buckled == null ? null : !buckled;
    }

    /** 四个位置的开 / 没系 → 位掩码；一个都不知道时为 null。 */
    static Integer mask(Boolean[] flags) {
        int mask = 0;
        boolean any = false;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i] == null) {
                continue;
            }
            any = true;
            if (flags[i]) {
                mask |= DOOR_BITS[i];
            }
        }
        return any ? mask : null;
    }

    /** ISensor 里 GEAR_* 常量的名字 → 字母。 */
    static String gearLetter(String constantName) {
        String n = constantName.toUpperCase(Locale.US);
        if (n.contains("PARK")) {
            return "P";
        }
        if (n.contains("REVERSE")) {
            return "R";
        }
        if (n.contains("NEUTRAL")) {
            return "N";
        }
        if (n.contains("DRIVE")) {
            return "D";
        }
        return null;
    }

    private static String name(Boolean b) {
        return b == null ? "?" : (b ? "1" : "0");
    }

    // ================================================================= 反射小工具

    private static Object call(Object target, String name) {
        Method m = findMethod(target, name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            AppLog.w(TAG, name + " failed: " + describe(t));
            return null;
        }
    }

    /**
     * 找公开方法：先在对象实现的<b>公开接口</b>上找（实现类 {@code com.zeekrlife.adaptapi.car.impl.*}
     * 不是公开的，拿它的 Method 去调会被访问检查拦下），找不到再退回类本身。
     */
    static Method findMethod(Object target, String name, Class<?>... params) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            collectInterfaces(c.getInterfaces(), types);
        }
        for (Class<?> type : types) {
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            Method m = match(type.getMethods(), name, params);
            if (m != null) {
                return m;
            }
        }
        Method m = match(target.getClass().getMethods(), name, params);
        if (m != null) {
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // 设不上就算了，调的时候再看
            }
        }
        return m;
    }

    private static void collectInterfaces(Class<?>[] interfaces, List<Class<?>> into) {
        for (Class<?> i : interfaces) {
            if (!into.contains(i)) {
                into.add(i);
                collectInterfaces(i.getInterfaces(), into);
            }
        }
    }

    private static Method match(Method[] methods, String name, Class<?>... params) {
        for (Method m : methods) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] types = m.getParameterTypes();
            if (types.length != params.length) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < types.length; i++) {
                if (types[i] != params[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return m;
            }
        }
        return null;
    }

    /** 反射那一层剥掉，露出真正的原因。 */
    private static String describe(Throwable e) {
        Throwable t = e;
        while ((t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
