package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppLog;

/**
 * 车辆信号的唯一入口：来源往这里写，画的人从这里读。
 *
 * <h3>框架</h3>
 *
 * <ul>
 *   <li>一份快照（{@link VehicleState}），不可变；来源改几项就发布新的一份，版本号 +1。</li>
 *   <li>来源各管一条路：{@link EcarxSource}（车机系统自带的 ECARX 车辆接口，反射只读；
 *       zeekr-shortcut-lab 在容器里验证过能用），{@link LocationSource}（定位：经纬度，车辆不给车速时用 GPS 车速）。
 *       安卓标准的 {@code android.car} 在这台车机的容器里结构性拿不到（平台笔记），不接。
 *       再有新的路（OBD 蓝牙盒子……）就是再加一个来源，快照和信息条都不用动。</li>
 *   <li>只在录制而且开着信息条时跑（{@link #start} / {@link #stop}，由录制的开停带着）；
 *       停了快照清空，下次不会把旧值画进新录像。</li>
 * </ul>
 *
 * <p>每个来源连上（或连不上）都往黑匣子记一行「什么拿得到、什么拿不到」——
 * 这是试验项目，车上跑出来的答案就在那几行里。</p>
 */
public final class Telemetry {

    private static final String TAG = "Telemetry";
    private static final Telemetry INSTANCE = new Telemetry();

    /** 来源改快照的方式：在最新一份上改几项。 */
    public interface Edit {
        void apply(VehicleState.Builder builder);
    }

    private final Object lock = new Object();
    private volatile VehicleState state = VehicleState.empty();
    private boolean running;
    /** 车速有没有从车辆属性来过：有的话定位那边的 GPS 车速就不写了（那个是推算的）。 */
    private volatile boolean carSpeedSeen;
    private EcarxSource car;
    private LocationSource location;

    private Telemetry() {
    }

    public static Telemetry get() {
        return INSTANCE;
    }

    /** 开始收信号。重复调无害。 */
    public void start(Context context) {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            carSpeedSeen = false;
            state = VehicleState.empty();
            Context app = context.getApplicationContext();
            car = new EcarxSource(this);
            location = new LocationSource(this);
            car.start(app);
            location.start(app);
        }
        AppLog.i(TAG, "车辆信号开始收集");
    }

    /** 停止收信号，快照清空。 */
    public void stop() {
        EcarxSource c;
        LocationSource l;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            c = car;
            l = location;
            car = null;
            location = null;
            state = VehicleState.empty();
        }
        if (l != null) {
            l.stop();
        }
        if (c != null) {
            c.stop();
        }
        AppLog.i(TAG, "车辆信号停止收集");
    }

    public boolean isRunning() {
        return running;
    }

    /** 最新的一份快照，永远不为 null。 */
    public VehicleState latest() {
        return state;
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

    void noteCarSpeed() {
        carSpeedSeen = true;
    }

    boolean hasCarSpeed() {
        return carSpeedSeen;
    }

    /** 来源报到：连上了什么、连不上什么。黑匣子里一行，诊断报告也读它。 */
    void sourceReported(String source, String status) {
        AppLog.i(TAG, source + ": " + status);
        com.kooo.evcam.blackbox.BlackBox.note("行驶信息来源 " + source + ": " + status);
    }

    /** 诊断报告 / 日志用的一段。 */
    public String describe() {
        EcarxSource c;
        LocationSource l;
        synchronized (lock) {
            c = car;
            l = location;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(running ? "running" : "stopped");
        sb.append(", known=").append(state.knownCount()).append("/20");
        if (c != null) {
            sb.append("; ecarx: ").append(c.status());
        }
        if (l != null) {
            sb.append("; location: ").append(l.status());
        }
        return sb.toString();
    }
}
