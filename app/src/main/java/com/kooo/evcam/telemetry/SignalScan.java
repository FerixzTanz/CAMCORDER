package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 信号普查（开发者选项，2.11.3）：车机给的<b>全部</b>功能号都挂上，哪个变了就记一行。
 *
 * <h3>为什么</h3>
 *
 * <p>项目拥有者 2026-10-09：后视镜上的盲区灯亮的时候，车机读不读得到？{@link Signal#BSD}（0x28081600）
 * 按 dts88 的笔记是「开关状态，不是正在干预」。要找真正的报警，只能把所有号都听着，开一趟车，
 * 看盲区灯亮的那几下哪个号跟着变 —— 和 dts88 当初找喇叭（Lab 0.23.0，全部 2009 个号）是同一个办法。</p>
 *
 * <h3>号从哪来</h3>
 *
 * <p>ECARX 的车辆接口把功能号写成接口里的常量（{@code BCM_FUNC_DOOR}、{@code SETTING_FUNC_LIGHT_INDCR_STS}……）。
 * 从 BOOTCLASSPATH 里找出 ECARX 那个 jar，列出 {@code com.ecarx.xui.adaptapi} 下的类，名字里带 {@code FUNC} 的
 * int 常量都收进来，连名字一起 —— 名字本身就是线索（报告里先列出名字带 BSD / BLIND / LCA 的）。
 * 有的常量其实是取值不是号，挂上去也只是永远不来回调。</p>
 *
 * <h3>记什么、记在哪</h3>
 *
 * <p>每次变化一行：时刻、车速、号、区域、值、常量名，写到 {@code files/signal_scan.txt}，最多
 * {@link #MAX_LINES} 行。诊断报告里出摘要和过滤掉刷屏号之后的时间线。只读，不往车上写任何东西。
 * 开着最多 {@link #MAX_RUN_MS}，到点自己停，开关也跟着关。</p>
 *
 * <h3>2.11.4 补的两样</h3>
 *
 * <p>2.11.3 那一趟（2068 个号，124 个变过）里盲区灯一次都没对上。还没看过的两处：</p>
 * <ul>
 *   <li><b>传感器事件</b>（{@code ISensor}，档位、安全带那一类，类型号 0x0020xx00）：类型号同样从 jar 里收，
 *       行里记成 {@code S:0x...}。浮点传感器（车速、转角，0x0010 / 0x0040 段）不挂：变一点就推，只是噪声。
 *       和 {@link EcarxSource} 一样，传感器监听<b>进程里每个类型只注册一次、永不注销</b>（注销不掉，
 *       注销再注册回调会变成两三条），回调转给此刻在跑的那次普查，没在跑就丢掉。</li>
 *   <li><b>轮询名字像盲区的号</b>：{@code BLIND_SPOT_DETECTION_WARNING} 这类挂着却从没回调过，
 *       可能只是不推、要自己读。每 {@link #POLL_MS} 读一次（不带区域和几个常见区域），变了记一行
 *       {@code P:0x...}。开头读一遍，读到占位值（255 / 254 / 253 / -1 / -65535）或读不出来的组合不再读，
 *       最多 {@link #MAX_POLL_PAIRS} 组。</li>
 * </ul>
 */
public final class SignalScan {

    private static final String TAG = "SignalScan";
    static final String FILE_NAME = "signal_scan.txt";
    /** 一次普查最多开多久。 */
    public static final long MAX_RUN_MS = 3 * 60 * 60 * 1000L;
    /** 文件最多记多少行变化。 */
    static final int MAX_LINES = 60_000;
    /** 多久把缓冲写一次盘。 */
    private static final long FLUSH_MS = 5_000L;
    /** 名字像盲区的号多久读一次。 */
    static final long POLL_MS = 500L;
    /** 轮询时试的区域：0 表示不带区域。 */
    private static final int[] POLL_ZONES = {0, 0x80000000, 0x1, 0x4, 0x8, 0x80};
    /** 最多轮询多少组号 / 区域。 */
    static final int MAX_POLL_PAIRS = 40;
    /** 传感器事件的类型号段：0x0020xx00。jar 里收不到常量时就把这一段挨个试一遍。 */
    private static final int SENSOR_EVENT_PREFIX = 0x00200000;

    /** 传感器监听：进程里每个类型只注册一次、永不注销（见类注释），回调转给 {@link #running}。 */
    private static final Object SENSOR_LOCK = new Object();
    private static Object sensorProxy;
    private static final Set<Integer> sensorTypesRegistered = new HashSet<>();
    /** 名字里带这些的常量先单独列出来（盲区、变道、开门预警、后方横穿）。 */
    static final String[] BLIND_SPOT_WORDS = {"BSD", "BSM", "LCA", "DOW", "RCTA", "RCW"};
    /** 这几个按片段找（BLINDSPOT 连写也算）。 */
    static final String[] BLIND_SPOT_PARTS = {"BLIND", "LANE_CHANGE", "SIDE_RADAR"};

    private static final Object LOCK = new Object();
    /** 此刻在跑的那次普查；改它要拿 {@link #LOCK}，传感器回调不拿锁直接读。 */
    private static volatile SignalScan running;

    private final Context app;
    private final Runnable onAutoStop;
    private HandlerThread thread;
    private Handler handler;
    private Object function;
    private Object watcher;
    private int[] ids = new int[0];
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, String> sensorNames = new TreeMap<>();
    /** jar 里认出来的传感器事件类型常量有几个（含应用自己也认识的）。 */
    private int sensorTypesInJar;
    private Object sensor;
    /** 轮询的组合：{号, 区域}，和上一次读到的值。 */
    private final List<int[]> polled = new ArrayList<>();
    private final Map<String, String> lastPolled = new HashMap<>();
    private Method getValue;
    private Method getValueZoned;
    private final Runnable poll = this::pollNow;
    private final Map<Integer, String> known = new HashMap<>();
    private Writer out;
    private int lines;
    private volatile boolean stopped;
    private final Runnable flush = this::flushNow;
    private final Runnable autoStop = this::autoStop;

    private SignalScan(Context context, Runnable onAutoStop) {
        this.app = context.getApplicationContext();
        this.onAutoStop = onAutoStop;
        for (Signal s : Signal.values()) {
            if (s.kind == Signal.Kind.FUNCTION || s.kind == Signal.Kind.FUNCTION_ZONE) {
                known.put(s.id, s.name());
            } else if (s.kind == Signal.Kind.SENSOR_EVENT) {
                sensorNames.put(s.id, "(app) " + s.name());
            }
        }
    }

    /**
     * 开始普查（已经在跑就不动）。
     *
     * @param remainingMs 还能跑多久（重启进程后接着上一次时传剩下的）
     * @param onAutoStop  到点自己停时调（主线程之外），用来把开关关掉
     */
    public static void start(Context context, long remainingMs, Runnable onAutoStop) {
        synchronized (LOCK) {
            if (running != null) {
                return;
            }
            running = new SignalScan(context, onAutoStop);
            running.begin(Math.max(60_000L, Math.min(MAX_RUN_MS, remainingMs)));
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (running != null) {
                running.end("stopped by switch");
                running = null;
            }
        }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return running != null;
        }
    }

    // ================================================================= 线程上

    private void begin(long runMs) {
        thread = new HandlerThread("SignalScan");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::connect);
        handler.postDelayed(autoStop, runMs);
    }

    private void connect() {
        try {
            File file = new File(app.getFilesDir(), FILE_NAME);
            out = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
            write("# signal scan started " + stamp() + ", running up to " + (MAX_RUN_MS / 60000) + " min");
            Object car = EcarxSource.carFor(app);
            function = car == null ? null : EcarxSource.call(car, "getICarFunction");
            if (function == null) {
                write("# no ECARX car function manager on this head unit: nothing to scan");
                finishEarly();
                return;
            }
            String how = collectIds();
            write("# ids: " + ids.length + " (" + how + ")");
            for (Map.Entry<Integer, String> e : names.entrySet()) {
                if (matchesHint(e.getValue())) {
                    write("# hint " + hex(e.getKey()) + " " + e.getValue());
                }
            }
            if (ids.length == 0) {
                write("# found no function ids: nothing to scan");
                finishEarly();
                return;
            }
            write("# registered: " + register());
            sensor = EcarxSource.call(car, "getSensorManager");
            write("# sensors: " + (sensor == null ? "no sensor manager" : registerSensors()));
            write("# polling: " + startPolling());
            flushNow();
        } catch (Throwable t) {
            AppLog.w(TAG, "scan failed to start: " + EcarxSource.describe(t));
            write("# failed to start: " + EcarxSource.describe(t));
            finishEarly();
        }
    }

    /**
     * 找全部功能号：ECARX 那个 jar 里 {@code com.ecarx.xui.adaptapi} 下各类的 FUNC 常量。
     * 列不出类（jar 里没有 dex）就退回到车辆对象身上的接口和它们的内部类。
     */
    private String collectIds() {
        Map<Integer, String> found = new TreeMap<>();
        int classes = 0;
        String source = "none";
        ClassLoader loader = function.getClass().getClassLoader();
        for (String path : ecarxJars()) {
            try {
                @SuppressWarnings("deprecation")
                dalvik.system.DexFile dex = new dalvik.system.DexFile(path);
                Enumeration<String> entries = dex.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (!name.startsWith("com.ecarx.xui.adaptapi")) {
                        continue;
                    }
                    try {
                        Class<?> type = Class.forName(name, false, loader);
                        collectFrom(type, found);
                        sensorTypesInJar += collectSensorTypes(type, sensorNames);
                        classes++;
                    } catch (Throwable ignored) {
                        // 加载不了的类跳过
                    }
                }
                source = "dex " + path;
            } catch (Throwable t) {
                AppLog.w(TAG, "cannot list " + path + ": " + EcarxSource.describe(t));
            }
        }
        if (found.isEmpty()) {
            source = "interfaces of the function manager";
            List<Class<?>> todo = new ArrayList<>();
            for (Class<?> c : function.getClass().getInterfaces()) {
                todo.add(c);
            }
            for (int i = 0; i < todo.size() && i < 500; i++) {
                Class<?> c = todo.get(i);
                collectFrom(c, found);
                sensorTypesInJar += collectSensorTypes(c, sensorNames);
                classes++;
                for (Class<?> inner : c.getDeclaredClasses()) {
                    todo.add(inner);
                }
                for (Class<?> parent : c.getInterfaces()) {
                    todo.add(parent);
                }
            }
        }
        // 应用自己认识的号也一定挂上
        for (Map.Entry<Integer, String> e : known.entrySet()) {
            if (!found.containsKey(e.getKey())) {
                found.put(e.getKey(), "(app) " + e.getValue());
            }
        }
        names.putAll(found);
        ids = new int[found.size()];
        int i = 0;
        for (Integer id : found.keySet()) {
            ids[i++] = id;
        }
        return source + ", " + classes + " classes";
    }

    private static void collectFrom(Class<?> type, Map<Integer, String> into) {
        Field[] fields;
        try {
            fields = type.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }
        for (Field f : fields) {
            int mod = f.getModifiers();
            if (f.getType() != int.class || !Modifier.isStatic(mod) || !Modifier.isFinal(mod)
                    || !f.getName().contains("FUNC")) {
                continue;
            }
            try {
                f.setAccessible(true);
                int v = f.getInt(null);
                if ((v & 0xFFFF0000) == 0) {
                    continue;   // 小数字是取值，不是号
                }
                String name = type.getSimpleName() + "." + f.getName();
                String prev = into.get(v);
                into.put(v, prev == null ? name : prev.length() <= name.length() ? prev : name);
            } catch (Throwable ignored) {
                // 读不了的常量跳过
            }
        }
    }

    /**
     * 传感器事件类型号：名字带 Sensor 的类里，名字带 TYPE、不带 FUNC 的 int 常量，形如 0x0020xx00
     * （末字节是 0；末字节不是 0 的是事件取值，比如档位 D）。返回认出来几个（已经在表里的也算）。
     */
    static int collectSensorTypes(Class<?> type, Map<Integer, String> into) {
        if (!type.getSimpleName().contains("Sensor")) {
            return 0;
        }
        Field[] fields;
        try {
            fields = type.getDeclaredFields();
        } catch (Throwable t) {
            return 0;
        }
        int count = 0;
        for (Field f : fields) {
            int mod = f.getModifiers();
            String n = f.getName();
            if (f.getType() != int.class || !Modifier.isStatic(mod) || !Modifier.isFinal(mod)
                    || !n.contains("TYPE") || n.contains("FUNC")) {
                continue;
            }
            try {
                f.setAccessible(true);
                int v = f.getInt(null);
                if (isSensorEventType(v)) {
                    count++;
                    if (!into.containsKey(v)) {
                        into.put(v, type.getSimpleName() + "." + n);
                    }
                }
            } catch (Throwable ignored) {
                // 读不了的常量跳过
            }
        }
        return count;
    }

    /** 传感器事件类型号：0x0020xx00，xx 不是 0。浮点传感器（0x0010 / 0x0040 段）不算。 */
    static boolean isSensorEventType(int v) {
        return (v & 0xFFFF00FF) == SENSOR_EVENT_PREFIX && (v & 0x0000FF00) != 0;
    }

    /**
     * 传感器事件逐个类型挂（它只认一个一个挂）；jar 里没认出类型就把 0x0020xx00 挨个试。
     * 进程里已经挂过的类型不再挂（挂不掉、也不注销，见类注释）。
     */
    private String registerSensors() {
        Method register = EcarxSource.findMethodWithInterfaceFirst(sensor, "registerListener", int.class);
        if (register == null) {
            return "no registerListener";
        }
        boolean fromJar = sensorTypesInJar > 0;
        if (!fromJar) {
            for (int mid = 1; mid <= 0xFF; mid++) {
                int v = SENSOR_EVENT_PREFIX | (mid << 8);
                if (!sensorNames.containsKey(v)) {
                    sensorNames.put(v, null);
                }
            }
        }
        int added = 0;
        synchronized (SENSOR_LOCK) {
            if (sensorProxy == null) {
                sensorProxy = Proxy.newProxyInstance(SignalScan.class.getClassLoader(),
                        new Class<?>[]{register.getParameterTypes()[0]}, new SensorForward());
            }
            for (int type : sensorNames.keySet()) {
                if (stopped) {
                    break;
                }
                if (!sensorTypesRegistered.contains(type) && quietOk(register, sensor, sensorProxy, type)) {
                    sensorTypesRegistered.add(type);
                    added++;
                }
            }
            return (fromJar ? sensorTypesInJar + " event types from the jar" : "no event types in the jar, tried 0x0020xx00")
                    + ", " + sensorTypesRegistered.size() + " listening (+" + added + ")";
        }
    }

    /** 开头把名字像盲区的号在几个区域上各读一遍，读得出来的组合以后每 {@link #POLL_MS} 读一次。 */
    private String startPolling() {
        getValue = EcarxSource.findMethod(function, "getFunctionValue", int.class);
        getValueZoned = EcarxSource.findMethod(function, "getFunctionValue", int.class, int.class);
        if (getValue == null && getValueZoned == null) {
            return "no getFunctionValue";
        }
        // 名字带 BLIND / BSD 的排前面；先把所有号不带区域读一遍，再试区域 —— 到上限时砍掉的是后面的
        List<Integer> hinted = new ArrayList<>();
        for (Map.Entry<Integer, String> e : names.entrySet()) {
            if (matchesHint(e.getValue())) {
                hinted.add(e.getKey());
            }
        }
        hinted.sort((x, y) -> Boolean.compare(!isBlindSpotName(names.get(x)), !isBlindSpotName(names.get(y))));
        int skipped = 0;
        for (int zone : POLL_ZONES) {
            for (int id : hinted) {
                if (stopped) {
                    break;
                }
                String v = readValue(id, zone);
                if (v == null || isPlaceholder(v)) {
                    continue;
                }
                if (polled.size() >= MAX_POLL_PAIRS) {
                    skipped++;
                    continue;
                }
                polled.add(new int[]{id, zone});
                lastPolled.put(pollKey(id, zone), v);
                write("# poll start " + pollKey(id, zone) + " = " + v + " " + names.get(id));
            }
        }
        if (!polled.isEmpty()) {
            handler.postDelayed(poll, POLL_MS);
        }
        return polled.size() + " id/zone pairs every " + POLL_MS + " ms"
                + (skipped > 0 ? " (" + skipped + " more left out, cap " + MAX_POLL_PAIRS + ")" : "");
    }

    private void pollNow() {
        if (stopped) {
            return;
        }
        long wall = System.currentTimeMillis();
        for (int[] p : polled) {
            String v = readValue(p[0], p[1]);
            String key = pollKey(p[0], p[1]);
            if (v != null && !v.equals(lastPolled.get(key))) {
                lastPolled.put(key, v);
                logLine(wall, key, v, names.get(p[0]));
            }
        }
        Handler h = handler;
        if (h != null && !stopped) {
            h.postDelayed(poll, POLL_MS);
        }
    }

    /** 读一个号（zone 为 0 时不带区域）；抛异常或读到 null 返回 null。 */
    private String readValue(int id, int zone) {
        try {
            Object v = zone == 0
                    ? (getValue == null ? null : getValue.invoke(function, id))
                    : (getValueZoned == null ? null : getValueZoned.invoke(function, id, zone));
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 名字直说是盲区的（不只是变道、开门预警那些）。 */
    static boolean isBlindSpotName(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        return upper.contains("BLIND_SPOT") || upper.contains("BSD");
    }

    /** ECARX 的占位值（未知 / 无 / 错误 / 这个区域没有）：开头读到它的组合不轮询。 */
    static boolean isPlaceholder(String v) {
        try {
            double d = Double.parseDouble(v);
            return d == 255 || d == 254 || d == 253 || d == -1 || d == -65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String pollKey(int id, int zone) {
        return "P:" + hex(id) + (zone != 0 ? "@" + hex(zone) : "");
    }

    /** 和 {@link EcarxSource#invokeOk} 一样，只是失败不进日志（试几百个类型号时会刷屏）。 */
    private static boolean quietOk(Method m, Object target, Object... args) {
        try {
            Object r = m.invoke(target, args);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /** BOOTCLASSPATH（和 /system/framework）里名字带 ecarx 的 jar。 */
    private static List<String> ecarxJars() {
        List<String> out = new ArrayList<>();
        String boot = System.getenv("BOOTCLASSPATH");
        if (boot != null) {
            for (String p : boot.split(":")) {
                if (p.toLowerCase(Locale.US).contains("ecarx")) {
                    out.add(p);
                }
            }
        }
        if (out.isEmpty()) {
            File[] files = new File("/system/framework").listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.US);
                    if (n.contains("ecarx") && n.endsWith(".jar")) {
                        out.add(f.getAbsolutePath());
                    }
                }
            }
        }
        return out;
    }

    /** 一次挂全部；不认数组、或者数组里有它不认的号整个被拒，就逐个挂。 */
    private String register() {
        Method byArray = EcarxSource.findMethodWithInterface(function, "registerFunctionValueWatcher", int[].class);
        Method byOne = EcarxSource.findMethodWithInterface(function, "registerFunctionValueWatcher", int.class);
        Method any = byArray != null ? byArray : byOne;
        if (any == null) {
            return "no registerFunctionValueWatcher";
        }
        Object proxy = Proxy.newProxyInstance(SignalScan.class.getClassLoader(),
                new Class<?>[]{any.getParameterTypes()[1]}, new Callback());
        if (byArray != null && EcarxSource.invokeOk(byArray, function, ids, proxy)) {
            watcher = proxy;
            return "all " + ids.length + " at once";
        }
        if (byOne == null) {
            return "refused";
        }
        int done = 0;
        for (int id : ids) {
            if (stopped) {
                break;
            }
            if (EcarxSource.invokeOk(byOne, function, id, proxy)) {
                done++;
            }
        }
        if (done > 0) {
            watcher = proxy;
        }
        return "one by one " + done + "/" + ids.length;
    }

    private final class Callback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("onFunctionValueChanged".equals(method.getName()) && args != null && args.length == 3
                    && args[0] instanceof Integer && args[1] instanceof Integer && args[2] instanceof Number) {
                final int id = (Integer) args[0];
                final int zone = (Integer) args[1];
                final Number value = (Number) args[2];
                final long wall = System.currentTimeMillis();
                Handler h = handler;
                if (h != null && !stopped) {
                    h.post(() -> onChange(wall, id, zone, value));
                }
                return null;
            }
            return EcarxSource.proxyDefault(proxy, method, args, "SignalScan.Callback");
        }
    }

    /** 传感器的回调（进程级的一个代理，永不注销）：转给此刻在跑的那次普查，没在跑就丢掉。 */
    private static final class SensorForward implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("onSensorEventChanged".equals(name) && args != null && args.length == 2
                    && args[0] instanceof Integer && args[1] instanceof Number) {
                SignalScan scan = running;
                if (scan != null) {
                    scan.onSensorEvent(System.currentTimeMillis(), (Integer) args[0], (Number) args[1]);
                }
                return null;
            }
            if ("onSensorValueChanged".equals(name)) {
                return null;   // 浮点值（车速之类）不记，见类注释
            }
            return EcarxSource.proxyDefault(proxy, method, args, "SignalScan.SensorForward");
        }
    }

    private void onSensorEvent(long wall, int type, Number value) {
        Handler h = handler;
        if (h != null && !stopped) {
            h.post(() -> logLine(wall, "S:" + hex(type), String.valueOf(value), sensorNames.get(type)));
        }
    }

    private void onChange(long wall, int id, int zone, Number value) {
        logLine(wall, hex(id) + (zone != 0 ? "@" + hex(zone) : ""), String.valueOf(value), names.get(id));
    }

    /** 一行：时刻 车速 号 = 值 名字（报告按空格切，号里不能有空格）。 */
    private void logLine(long wall, String key, String value, String name) {
        if (stopped || lines >= MAX_LINES) {
            return;
        }
        Float speed = Telemetry.get().latest().speedKmh;
        write(time(wall) + " v=" + (speed == null ? "?" : String.format(Locale.US, "%.0f", speed))
                + " " + key + " = " + value + (name != null ? " " + name : ""));
        lines++;
        if (lines == MAX_LINES) {
            write("# reached " + MAX_LINES + " lines, no more changes recorded");
        }
    }

    private void autoStop() {
        synchronized (LOCK) {
            if (running == this) {
                running = null;
            }
        }
        end("ran " + (MAX_RUN_MS / 60000) + " min");
        if (onAutoStop != null) {
            onAutoStop.run();
        }
    }

    private void finishEarly() {
        flushNow();
    }

    private void end(String why) {
        stopped = true;
        Handler h = handler;
        HandlerThread t = thread;
        if (h == null) {
            return;
        }
        h.removeCallbacks(autoStop);
        h.removeCallbacks(poll);
        h.post(() -> {
            unregister();
            write("# stopped " + stamp() + " (" + why + "), " + lines + " changes");
            flushNow();
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
                // 关不上也没什么可做的
            }
            out = null;
            if (t != null) {
                t.quitSafely();
            }
        });
    }

    private void unregister() {
        // 传感器监听不注销（见类注释）：running 一清，回调就丢掉了
        Object w = watcher;
        watcher = null;
        if (w == null || function == null) {
            return;
        }
        Method one = EcarxSource.findMethodWithInterface(function, "unregisterFunctionValueWatcher");
        if (one != null && EcarxSource.invokeOk(one, function, w)) {
            return;
        }
        Method withIds = EcarxSource.findMethodWithInterface(function, "unregisterFunctionValueWatcher", int[].class);
        if (withIds != null) {
            EcarxSource.invokeOk(withIds, function, ids, w);
        }
    }

    private void write(String line) {
        Writer w = out;
        if (w == null) {
            return;
        }
        try {
            w.write(line);
            w.write('\n');
        } catch (IOException e) {
            AppLog.w(TAG, "write failed: " + e.getMessage());
        }
        Handler h = handler;
        if (h != null && !stopped) {
            h.removeCallbacks(flush);
            h.postDelayed(flush, FLUSH_MS);
        }
    }

    private void flushNow() {
        try {
            if (out != null) {
                out.flush();
            }
        } catch (IOException ignored) {
            // 下次再写
        }
    }

    /** 缩写按整词比（WINDOW 里的 DOW 不算），长的按片段找。 */
    static boolean matchesHint(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        for (String part : BLIND_SPOT_PARTS) {
            if (upper.contains(part)) {
                return true;
            }
        }
        for (String token : upper.split("[^A-Z0-9]+")) {
            for (String word : BLIND_SPOT_WORDS) {
                if (token.equals(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String hex(int v) {
        return String.format(Locale.US, "0x%08X", v);
    }

    private static String time(long wall) {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(wall));
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " (up " + (SystemClock.elapsedRealtime() / 1000) + "s)";
    }

    // ================================================================= 诊断报告

    /** 诊断报告里的一节：头几行、提示名、每个号变了几次、过滤掉刷屏号之后的时间线。 */
    public static void appendTo(StringBuilder sb, Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        sb.append("## 7. Signal scan (developer options)").append('\n');
        sb.append("now: ").append(isRunning() ? "recording" : "not recording").append('\n');
        if (!file.isFile()) {
            sb.append("never run").append('\n').append('\n');
            return;
        }
        List<String> header = new ArrayList<>();
        List<String[]> changes = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#")) {
                    header.add(line);
                    continue;
                }
                String[] parts = line.split(" ", 6);
                if (parts.length < 6) {
                    continue;
                }
                String key = parts[3];   // 号（@区域）
                counts.put(key, counts.getOrDefault(key, 0) + 1);
                changes.add(parts);
            }
        } catch (IOException e) {
            sb.append("cannot read ").append(file).append(": ").append(e.getMessage()).append('\n').append('\n');
            return;
        }
        for (String h : header) {
            sb.append(h).append('\n');
        }
        sb.append("ids that changed: ").append(counts.size()).append(", changes: ").append(changes.size()).append('\n');
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, String> nameOf = new HashMap<>();
        for (String[] p : changes) {
            // 一行：日期 时刻 v=车速 号(@区域) = 值 名字 —— p[5] 是「值 名字」
            String[] tail = p[5].split(" ", 2);
            if (tail.length == 2) {
                nameOf.put(p[3], tail[1]);
            }
        }
        sb.append("--- changes per id (most first) ---").append('\n');
        for (Map.Entry<String, Integer> e : sorted) {
            String n = nameOf.get(e.getKey());
            sb.append(String.format(Locale.US, "%6d  %s%s", e.getValue(), e.getKey(),
                    n != null ? "  " + n : "")).append('\n');
        }
        // 时间线：变化超过 200 次的号是刷屏的（车速档、时间之类），不进时间线；最多 4000 行，取最后的
        sb.append("--- timeline (ids with over 200 changes left out, last 4000 lines) ---").append('\n');
        List<String> timeline = new ArrayList<>();
        for (String[] p : changes) {
            if (counts.get(p[3]) <= 200) {
                timeline.add(String.join(" ", p));
            }
        }
        for (int i = Math.max(0, timeline.size() - 4000); i < timeline.size(); i++) {
            sb.append(timeline.get(i)).append('\n');
        }
        sb.append('\n');
    }
}
