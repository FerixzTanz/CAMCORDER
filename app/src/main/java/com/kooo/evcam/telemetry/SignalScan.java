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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** 名字里带这些的常量先单独列出来（盲区、变道、开门预警、后方横穿）。 */
    static final String[] BLIND_SPOT_WORDS = {"BSD", "BSM", "LCA", "DOW", "RCTA", "RCW"};
    /** 这几个按片段找（BLINDSPOT 连写也算）。 */
    static final String[] BLIND_SPOT_PARTS = {"BLIND", "LANE_CHANGE", "SIDE_RADAR"};

    private static final Object LOCK = new Object();
    private static SignalScan running;

    private final Context app;
    private final Runnable onAutoStop;
    private HandlerThread thread;
    private Handler handler;
    private Object function;
    private Object watcher;
    private int[] ids = new int[0];
    private final Map<Integer, String> names = new HashMap<>();
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
                        collectFrom(Class.forName(name, false, loader), found);
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

    private void onChange(long wall, int id, int zone, Number value) {
        if (stopped || lines >= MAX_LINES) {
            return;
        }
        Float speed = Telemetry.get().latest().speedKmh;
        String name = names.get(id);
        write(time(wall) + " v=" + (speed == null ? "?" : String.format(Locale.US, "%.0f", speed))
                + " " + hex(id) + (zone != 0 ? "@" + hex(zone) : "") + " = " + value
                + (name != null ? " " + name : ""));
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
