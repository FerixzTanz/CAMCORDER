package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 车辆信号的唯一入口：来源往这里写，用的人从这里读。
 *
 * <h3>框架</h3>
 *
 * <ul>
 *   <li>两份快照，都不可变、版本号只往上走：{@link Readings}（每个信号的读数，「系统信息」页看它）和
 *       {@link VehicleState}（信息条要的那几项，由 {@link VehicleStateMapper} 从读数推出来，再加定位）。</li>
 *   <li>来源各管一条路：{@link EcarxSource}（车机自带的 ECARX 接口，订阅），{@link LocationSource}（经纬度，
 *       车不给车速时用 GPS 车速）。再有新的路就是再加一个来源。</li>
 *   <li><b>谁要用就登记</b>（{@link #acquire} / {@link #release}，和相机的登记表一个道理）：录像开着信息条、
 *       「系统信息」页开着、录像时开着闪远光自动锁定（{@code storage.AutoLock}），都算要用；
 *       没人要了来源全停、快照清空 —— 关掉页面就释放全部资源，
 *       下次不会把旧值画进新录像。</li>
 * </ul>
 *
 * <p>每个来源连上（或连不上）都往黑匣子记一行「什么拿得到、什么拿不到」。</p>
 */
public final class Telemetry {

    private static final String TAG = "Telemetry";
    private static final Telemetry INSTANCE = new Telemetry();

    /** 来源改快照的方式：在最新一份上改几项。 */
    public interface Edit {
        void apply(VehicleState.Builder builder);
    }

    /** 读数变了谁来看（在主线程上回调）。 */
    public interface Listener {
        void onReadingsChanged(Readings readings);
    }

    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> users = new LinkedHashSet<>();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile VehicleState state = VehicleState.empty();
    private volatile Readings readings = Readings.empty();
    private boolean running;
    /** 车速有没有从车辆接口来过：有的话定位那边的 GPS 车速就不写了（那个是推算的）。 */
    private volatile boolean carSpeedSeen;
    private EcarxSource car;
    private LocationSource location;

    private Telemetry() {
    }

    public static Telemetry get() {
        return INSTANCE;
    }

    /** 信息条的勾选或开发者模式变了（{@link InfoBar}）：读数不变也按新的勾选重新映射一次。 */
    public void selectionChanged() {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        if (c != null) {
            c.republish();
        }
    }

    /** 登记：我要用车辆信号。第一个登记的把来源拉起来；重复登记无害。 */
    public void acquire(Context context, String who) {
        synchronized (lock) {
            users.add(who);
            if (running) {
                return;
            }
            running = true;
            carSpeedSeen = false;
            state = VehicleState.empty();
            readings = Readings.empty();
            Context app = context.getApplicationContext();
            car = new EcarxSource(this);
            location = new LocationSource(this);
            car.start(app);
            location.start(app);
        }
        AppLog.i(TAG, "车辆信号开始收集（" + who + "）");
    }

    /** 注销：我不用了。最后一个注销的把来源全停、快照清空；没登记过也无害。 */
    public void release(String who) {
        EcarxSource c;
        LocationSource l;
        synchronized (lock) {
            users.remove(who);
            if (!running || !users.isEmpty()) {
                return;
            }
            running = false;
            c = car;
            l = location;
            car = null;
            location = null;
            state = VehicleState.empty();
            readings = Readings.empty();
        }
        if (l != null) {
            l.stop();
        }
        if (c != null) {
            c.stop();
        }
        AppLog.i(TAG, "车辆信号停止收集（" + who + " 是最后一个）");
        notifyListeners(Readings.empty());
    }

    public boolean isRunning() {
        return running;
    }

    /** 信息条要的快照，永远不为 null。 */
    public VehicleState latest() {
        return state;
    }

    /** 每个信号的读数，永远不为 null。 */
    public Readings readings() {
        return readings;
    }

    /** 来源改几项并发布。停了之后的写入丢掉。 */
    public void edit(Edit edit) {
        synchronized (lock) {
            if (!running) {
                return;
            }
            VehicleState.Builder b = state.edit();
            edit.apply(b);
            state = b.build();
        }
    }

    /** 来源发布一份读数。停了之后的丢掉。 */
    void publishReadings(Readings snapshot) {
        synchronized (lock) {
            if (!running) {
                return;
            }
            readings = snapshot;
        }
        notifyListeners(snapshot);
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(Readings snapshot) {
        List<Listener> copy;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            copy = new ArrayList<>(listeners);
        }
        main.post(() -> {
            for (Listener l : copy) {
                try {
                    l.onReadingsChanged(snapshot);
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "listener failed: " + e);
                }
            }
        });
    }


    void noteCarSpeed() {
        carSpeedSeen = true;
    }

    boolean hasCarSpeed() {
        return carSpeedSeen;
    }

    /**
     * 来源报到：连上了什么、连不上什么。细节（耗时、方法探测、订阅、异常原文）只进日志和黑匣子，诊断报告也读它；
     * 页面上只显示 {@link #carLink} / {@link #locationLink} 的结果。
     */
    void sourceReported(String source, String status) {
        AppLog.i(TAG, source + ": " + status);
        com.kooo.evcam.blackbox.BlackBox.note("行驶信息来源 " + source + ": " + status);
        // 页面上的状态行跟着换
        notifyListeners(readings);
    }

    /** 车辆接口这一路的结果（「系统信息」页的状态行）。 */
    public enum CarLink {
        /** 还在连（{@code Car.create} 第一次要一秒左右）：状态行先不写这一项 */
        CONNECTING,
        CONNECTED,
        /** 这台车机没有 ECARX 接口，或者接口里没有能读的 */
        UNAVAILABLE,
        /** 有接口，连的时候出错 */
        FAILED
    }

    /** 定位这一路的结果（「系统信息」页的状态行）。 */
    public enum LocationLink {
        ON,
        NO_PERMISSION,
        /** 系统定位关着（没有一个提供者能订阅） */
        OFF
    }

    /** 车辆接口此刻的结果；没在收集时 null。 */
    public CarLink carLink() {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        return c == null ? null : c.link();
    }

    /** 定位此刻的结果；没在收集时 null。 */
    public LocationLink locationLink() {
        LocationSource l;
        synchronized (lock) {
            l = location;
        }
        return l == null ? null : l.link();
    }
}
