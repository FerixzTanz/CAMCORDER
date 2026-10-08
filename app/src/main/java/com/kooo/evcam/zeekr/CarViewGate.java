package com.kooo.evcam.zeekr;

import android.content.Context;

import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 原厂画面在屏幕上（或马上要有）：倒车（R 挡）、360、原厂侧方小窗、泊车辅助（2.11.0 驾驶安全审查）。
 *
 * <p>「原厂画面永远优先」以前只有侧视弹窗遵守。超级后视镜、悬浮按钮是 TYPE_APPLICATION_OVERLAY，
 * 系统把它们放在所有界面之上，会挡住倒车影像或泊车雷达的一部分；应用自己把主界面拉到前台
 * （亮屏接回、上车开录）也会盖住原厂画面。现在它们都看这一个开关：开着时悬浮窗全透明、不接触摸，
 * 也不拉主界面。判断和侧视弹窗是同一条（{@link SideViewDecision#factoryViewActive}）。</p>
 *
 * <p>回调在主线程上。车辆信号读不到时算「没有」（不挡人；侧视弹窗那边另有读不到就不弹的规矩）。</p>
 */
public final class CarViewGate implements Telemetry.Listener {

    /** 原厂画面出现 / 消失。 */
    public interface Listener {
        void onCarViewChanged(boolean active);
    }

    private static final String USER = "car-view-gate";
    private static final CarViewGate INSTANCE = new CarViewGate();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean watching;
    private boolean active;

    private CarViewGate() {
    }

    public static CarViewGate get() {
        return INSTANCE;
    }

    /** 这一组读数里，原厂画面在不在。 */
    public static boolean activeFrom(Readings r) {
        if (r == null) {
            return false;
        }
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.gear = r.text(Signal.GEAR);
        in.stock360Shown = r.bool(Signal.STOCK_360);
        in.stockPopupShown = r.bool(Signal.STOCK_POPUP);
        in.parkAssistOn = r.bool(Signal.PARK_ASSIST);
        return SideViewDecision.factoryViewActive(in);
    }

    /** 此刻原厂画面在不在（车辆信号没在收时算不在）。哪个线程都能问。 */
    public static boolean isActiveNow() {
        try {
            return activeFrom(Telemetry.get().readings());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 登记（主线程）。第一个登记的开始看车辆信号；登记时马上按此刻的状态回调一次。 */
    public void addListener(Context context, Listener listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        if (!watching) {
            watching = true;
            Telemetry telemetry = Telemetry.get();
            telemetry.addListener(this);
            telemetry.acquire(context.getApplicationContext(), USER);
            active = activeFrom(telemetry.readings());
        }
        listener.onCarViewChanged(active);
    }

    /** 注销（主线程）。最后一个走的把车辆信号还回去。 */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
        if (watching && listeners.isEmpty()) {
            watching = false;
            Telemetry telemetry = Telemetry.get();
            telemetry.removeListener(this);
            telemetry.release(USER);
            active = false;
        }
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        boolean now = activeFrom(readings);
        if (now == active) {
            return;
        }
        active = now;
        BlackBox.note(now ? "原厂画面出来了：悬浮窗让开" : "原厂画面收了：悬浮窗回来");
        for (Listener listener : listeners) {
            listener.onCarViewChanged(now);
        }
    }
}
