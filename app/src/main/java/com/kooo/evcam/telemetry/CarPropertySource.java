package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆属性这一路（{@code android.car}，全反射，只读）。
 *
 * <h3>预期：在这台车机上多半一项都拿不到</h3>
 *
 * <p>平台笔记写明：容器里车辆权限是结构性拿不到的（系统认识的是容器的包，不是我们的），
 * 点火 / 档位 / 手刹三个 normal 级属性全是 SecurityException；转向灯、车门、灯光、
 * 里程、方向盘更是 signature|privileged。1.37.0 把当时的探测都删了，因为它们只是在反复
 * 证明这一条。这里把它<b>作为信息条的一个来源</b>再接上，不是再证明一次 ——
 * 而是信息条要显示这些项，来源就得有这一路；哪天换了容器、换了车机，或者厂商放开了
 * 某个属性，信息条上对应的那一格自然就亮了。连不上的每一项都记进黑匣子。</p>
 *
 * <p>Android 15 才有的属性（油门 / 刹车开度）在这台 Android 12 车机上没有那个常量，
 * 记成「无此常量」；ACC / 车道居中是 Android 14 的。</p>
 *
 * <p>解码那几条（档位字母、灯光状态、转向灯、ADAS 状态）是纯函数，
 * {@code CarPropertyDecodeTest} 里测。</p>
 */
final class CarPropertySource {

    private static final String TAG = "CarPropertySource";

    /** 值一变就回调。0 是 {@code SENSOR_RATE_ONCHANGE}。 */
    private static final float RATE_ON_CHANGE = 0f;
    /** 方向盘、车速这类连续量按 10 Hz 要（{@code SENSOR_RATE_UI}）。 */
    private static final float RATE_UI = 5f;

    // VehicleAreaDoor / VehicleAreaSeat 的四个位置（值相同）
    private static final int AREA_ROW1_LEFT = 0x1;
    private static final int AREA_ROW1_RIGHT = 0x4;
    private static final int AREA_ROW2_LEFT = 0x10;
    private static final int AREA_ROW2_RIGHT = 0x40;
    /** 全局属性的 area。 */
    private static final int AREA_GLOBAL = 0;

    // CruiseControlState.ACTIVATED / LaneCenteringAssistState.ACTIVATED
    static final int ACC_ACTIVATED = 2;
    static final int LCC_ACTIVATED = 3;

    // VehicleLightState（要放在映射表前面：静态初始化里按简单名引用不能往后指）
    static final int LIGHT_OFF = 0;
    static final int LIGHT_ON = 1;
    static final int LIGHT_DAYTIME_RUNNING = 2;

    /** 一个属性值怎么落到快照上。 */
    interface Apply {
        void apply(VehicleState.Builder b, Object value);
    }

    /** 属性名 → 区域 → 怎么落到快照。 */
    private static final class Mapping {
        final String name;
        final int areaId;
        final float rate;
        final Apply apply;

        Mapping(String name, int areaId, float rate, Apply apply) {
            this.name = name;
            this.areaId = areaId;
            this.rate = rate;
            this.apply = apply;
        }
    }

    private static final Mapping[] MAPPINGS = {
            new Mapping("TURN_SIGNAL_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.turnSignal(decodeTurnSignal(asInt(v)))),
            new Mapping("HAZARD_LIGHTS_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.hazard(lightOn(asInt(v)))),
            new Mapping("PERF_STEERING_ANGLE", AREA_GLOBAL, RATE_UI,
                    (b, v) -> b.steeringDegrees(asFloat(v))),
            new Mapping("GEAR_SELECTION", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.gear(gearLetter(asInt(v)))),
            new Mapping("ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE", AREA_GLOBAL, RATE_UI,
                    (b, v) -> b.throttle(asFloat(v) / 100f)),
            new Mapping("BRAKE_PEDAL_COMPRESSION_PERCENTAGE", AREA_GLOBAL, RATE_UI,
                    (b, v) -> b.brake(asFloat(v) / 100f)),
            new Mapping("PERF_VEHICLE_SPEED", AREA_GLOBAL, RATE_UI,
                    (b, v) -> b.speedKmh(Math.abs(asFloat(v)) * 3.6f)),
            new Mapping("PARKING_BRAKE_AUTO_APPLY", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.autoHold(asBool(v))),
            new Mapping("CRUISE_CONTROL_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.adaptiveCruise(adasActive(asInt(v), ACC_ACTIVATED))),
            new Mapping("LANE_CENTERING_ASSIST_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.laneCentering(adasActive(asInt(v), LCC_ACTIVATED))),
            new Mapping("DOOR_POS", AREA_ROW1_LEFT, RATE_ON_CHANGE,
                    (b, v) -> b.door(VehicleState.FRONT_LEFT, asInt(v) > 0)),
            new Mapping("DOOR_POS", AREA_ROW1_RIGHT, RATE_ON_CHANGE,
                    (b, v) -> b.door(VehicleState.FRONT_RIGHT, asInt(v) > 0)),
            new Mapping("DOOR_POS", AREA_ROW2_LEFT, RATE_ON_CHANGE,
                    (b, v) -> b.door(VehicleState.REAR_LEFT, asInt(v) > 0)),
            new Mapping("DOOR_POS", AREA_ROW2_RIGHT, RATE_ON_CHANGE,
                    (b, v) -> b.door(VehicleState.REAR_RIGHT, asInt(v) > 0)),
            new Mapping("SEAT_BELT_BUCKLED", AREA_ROW1_LEFT, RATE_ON_CHANGE,
                    (b, v) -> b.belt(VehicleState.FRONT_LEFT, !asBool(v))),
            new Mapping("SEAT_BELT_BUCKLED", AREA_ROW1_RIGHT, RATE_ON_CHANGE,
                    (b, v) -> b.belt(VehicleState.FRONT_RIGHT, !asBool(v))),
            new Mapping("SEAT_BELT_BUCKLED", AREA_ROW2_LEFT, RATE_ON_CHANGE,
                    (b, v) -> b.belt(VehicleState.REAR_LEFT, !asBool(v))),
            new Mapping("SEAT_BELT_BUCKLED", AREA_ROW2_RIGHT, RATE_ON_CHANGE,
                    (b, v) -> b.belt(VehicleState.REAR_RIGHT, !asBool(v))),
            new Mapping("HEADLIGHTS_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> {
                        int s = asInt(v);
                        b.daytimeRunningLights(s == LIGHT_DAYTIME_RUNNING);
                        b.lowBeam(s == LIGHT_ON);
                    }),
            new Mapping("HIGH_BEAM_LIGHTS_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.highBeam(lightOn(asInt(v)))),
            new Mapping("FOG_LIGHTS_STATE", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.fogLights(lightOn(asInt(v)))),
            new Mapping("PERF_ODOMETER", AREA_GLOBAL, RATE_ON_CHANGE,
                    (b, v) -> b.odometerKm(asFloat(v))),
    };

    private final Telemetry telemetry;
    /** 属性 id → 这个 id 下的几条映射（车门、安全带一个 id 四个区域）。 */
    private final Map<Integer, List<Mapping>> byId = new LinkedHashMap<>();
    private volatile String status = "not started";
    /** 强引用：车辆服务那边多半只弱引用回调，我们不拿着它就悄悄没了。 */
    private Object propertyManager;
    private Object callbackProxy;
    private Method unregister;

    CarPropertySource(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    void start(Context context) {
        try {
            Object manager = connect(context);
            if (manager == null) {
                telemetry.sourceReported("car", status);
                return;
            }
            propertyManager = manager;
            String outcome = subscribe(manager);
            readAllNow(manager);
            telemetry.sourceReported("car", outcome);
        } catch (Throwable t) {
            status = "failed: " + t;
            AppLog.w(TAG, "车辆属性来源启动失败: " + t);
            telemetry.sourceReported("car", "start failed: " + t.getClass().getSimpleName());
        }
    }

    void stop() {
        Object manager = propertyManager;
        Object proxy = callbackProxy;
        Method un = unregister;
        propertyManager = null;
        callbackProxy = null;
        unregister = null;
        if (manager != null && proxy != null && un != null) {
            try {
                un.invoke(manager, proxy);
            } catch (Throwable t) {
                AppLog.w(TAG, "取消车辆属性订阅失败: " + t);
            }
        }
        status = "stopped";
    }

    String status() {
        return status;
    }

    // ================================================================= 连接

    private Object connect(Context context) throws Exception {
        Class<?> carClass;
        try {
            carClass = Class.forName("android.car.Car");
        } catch (ClassNotFoundException e) {
            status = "no android.car";
            return null;
        }
        Method createCar = null;
        for (Method m : carClass.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("createCar".equals(m.getName()) && params.length == 1 && params[0] == Context.class) {
                createCar = m;
                break;
            }
        }
        if (createCar == null) {
            status = "no createCar(Context)";
            return null;
        }
        Object car = createCar.invoke(null, context);
        if (car == null) {
            status = "createCar returned null";
            return null;
        }
        Object manager = carClass.getMethod("getCarManager", String.class).invoke(car, "property");
        if (manager == null) {
            status = "no property manager";
            return null;
        }
        return manager;
    }

    /** 每个属性挂一次监听；结果汇成一句话（黑匣子那一行）。 */
    private String subscribe(Object manager) {
        Class<?> callbackClass;
        try {
            callbackClass = Class.forName(
                    "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
        } catch (ClassNotFoundException e) {
            status = "no CarPropertyEventCallback";
            return status;
        }
        callbackProxy = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, new Callback());
        Method register = null;
        for (Method m : manager.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("registerCallback".equals(m.getName()) && params.length == 3
                    && params[0].isAssignableFrom(callbackClass)
                    && params[1] == int.class && params[2] == float.class) {
                register = m;
            } else if ("unregisterCallback".equals(m.getName()) && params.length == 1
                    && params[0].isAssignableFrom(callbackClass)) {
                unregister = m;
            }
        }
        if (register == null) {
            status = "no registerCallback";
            return status;
        }

        int ok = 0;
        int total = 0;
        StringBuilder failures = new StringBuilder();
        for (Mapping mapping : MAPPINGS) {
            Integer propId = propertyId(mapping.name);
            if (propId == null) {
                if (!failures.toString().contains(mapping.name + "=")) {
                    failures.append(mapping.name).append("=absent ");
                }
                continue;
            }
            List<Mapping> list = byId.get(propId);
            boolean first = list == null;
            if (first) {
                list = new ArrayList<>();
                byId.put(propId, list);
            }
            list.add(mapping);
            if (!first) {
                continue;   // 同一个属性的第二个区域：监听已经挂过
            }
            total++;
            try {
                Object result = register.invoke(manager, callbackProxy, propId, mapping.rate);
                boolean accepted = !(result instanceof Boolean) || (Boolean) result;
                if (accepted) {
                    ok++;
                } else {
                    failures.append(mapping.name).append("=refused ");
                }
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                failures.append(mapping.name).append('=').append(cause.getClass().getSimpleName()).append(' ');
            }
        }
        status = "subscribed " + ok + "/" + total
                + (failures.length() > 0 ? " failed: " + failures.toString().trim() : "");
        return status;
    }

    /** 挂上之后先读一遍，免得「一直没变」就什么都不显示。 */
    private void readAllNow(Object manager) {
        Method getProperty = null;
        for (Method m : manager.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("getProperty".equals(m.getName()) && params.length == 2
                    && params[0] == int.class && params[1] == int.class) {
                getProperty = m;
                break;
            }
        }
        if (getProperty == null) {
            return;
        }
        for (Map.Entry<Integer, List<Mapping>> entry : byId.entrySet()) {
            for (Mapping mapping : entry.getValue()) {
                try {
                    Object carValue = getProperty.invoke(manager, entry.getKey(), mapping.areaId);
                    if (carValue != null) {
                        Object value = carValue.getClass().getMethod("getValue").invoke(carValue);
                        applyValue(entry.getKey(), mapping.areaId, value);
                    }
                } catch (Throwable t) {
                    // 读不到就读不到：挂监听那一步已经把原因记过了
                }
            }
        }
    }

    private static Integer propertyId(String name) {
        try {
            Class<?> ids = Class.forName("android.car.VehiclePropertyIds");
            return ids.getField(name).getInt(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================= 回调

    private final class Callback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            try {
                if ("onChangeEvent".equals(name) && args != null && args.length > 0) {
                    onChange(args[0]);
                } else if ("onErrorEvent".equals(name)) {
                    AppLog.w(TAG, "车辆属性回调报错: " + java.util.Arrays.toString(args));
                }
            } catch (Throwable t) {
                AppLog.w(TAG, "处理车辆属性回调失败: " + t);
            }
            if ("toString".equals(name)) {
                return "CarPropertySource$Callback";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            return null;
        }
    }

    private void onChange(Object carPropertyValue) throws Exception {
        if (carPropertyValue == null) {
            return;
        }
        Class<?> cls = carPropertyValue.getClass();
        int propId = (Integer) cls.getMethod("getPropertyId").invoke(carPropertyValue);
        int areaId = (Integer) cls.getMethod("getAreaId").invoke(carPropertyValue);
        Object value = cls.getMethod("getValue").invoke(carPropertyValue);
        applyValue(propId, areaId, value);
    }

    private void applyValue(int propId, int areaId, Object value) {
        List<Mapping> list = byId.get(propId);
        if (list == null || value == null) {
            return;
        }
        for (Mapping mapping : list) {
            if (mapping.areaId != AREA_GLOBAL && mapping.areaId != areaId) {
                continue;
            }
            if ("PERF_VEHICLE_SPEED".equals(mapping.name)) {
                telemetry.noteCarSpeed();
            }
            try {
                telemetry.edit(b -> mapping.apply.apply(b, value));
            } catch (RuntimeException e) {
                AppLog.w(TAG, mapping.name + " 的值解不开: " + value + " " + e);
            }
        }
    }

    // ================================================================= 解码（纯函数）

    /** VehicleTurnSignal：NONE=0、RIGHT=1、LEFT=2 → 快照里的常量。 */
    static Integer decodeTurnSignal(int value) {
        switch (value) {
            case 0: return VehicleState.TURN_NONE;
            case 1: return VehicleState.TURN_RIGHT;
            case 2: return VehicleState.TURN_LEFT;
            default: return null;
        }
    }

    /** VehicleLightState：亮着（含日行灯档）算开。 */
    static Boolean lightOn(int value) {
        if (value == LIGHT_OFF) {
            return false;
        }
        if (value == LIGHT_ON || value == LIGHT_DAYTIME_RUNNING) {
            return true;
        }
        return null;
    }

    /** VehicleGear：NEUTRAL=1、REVERSE=2、PARK=4、DRIVE=8，前进档 FIRST(0x10) 起都算 D。 */
    static String gearLetter(int value) {
        switch (value) {
            case 0x1: return "N";
            case 0x2: return "R";
            case 0x4: return "P";
            case 0x8: return "D";
            default:
                return value >= 0x10 ? "D" : null;
        }
    }

    /** ADAS 状态：等于「已激活」那个码才算激活；负数是错误码，算不知道。 */
    static Boolean adasActive(int value, int activatedCode) {
        if (value < 0) {
            return null;
        }
        return value == activatedCode;
    }

    private static int asInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? 1 : 0;
        }
        throw new IllegalArgumentException("not a number: " + v);
    }

    private static float asFloat(Object v) {
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        throw new IllegalArgumentException("not a number: " + v);
    }

    private static boolean asBool(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        throw new IllegalArgumentException("not a boolean: " + v);
    }
}
