package com.kooo.evcam.camera;

import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.zeekr.CarViewGate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 别的程序拿着相机的时候我们怎么办。
 *
 * <h3>实测（2026-09-27，三份日志）</h3>
 *
 * <p>车机上相机 1 和相机 2 同时只能开一路：别的程序一拿相机 1，相机服务几毫秒内就把我们的相机 2
 * 断开；之后我们每次重开都报 {@code ERROR_MAX_CAMERAS_IN_USE}，直到它放开。谁拿得到由相机服务按
 * 进程优先级定 —— 我们在前台它开不了（我们收不到任何信号），我们在后台就是我们被踢。
 * 所以「让路」没有意义：前台让不出去，后台不用让。</p>
 *
 * <h3>规则（项目所有者 2026-09-27 定）</h3>
 *
 * <ul>
 *   <li>它拿着的时候不按退避猛试（每次都失败、还刷日志），只每 {@link #RETRY_WHILE_HELD_MS} 试一次
 *       （2.10.10 起这个节奏在看门狗 {@link CameraLiveness} 里，相机自己不再重连）——
 *       这一下是保险：它要是占着很久、而我们是别的原因断的（哨兵模式下相机 1 被车机自己的服务占过两个小时，
 *       我们照常录着），不至于一直不接；</li>
 *   <li>它一放开（相机服务会通知）立刻接回；</li>
 *   <li>相机服务说访问优先级变了（我们回到前台那种）立刻试一次：前台的一方拿得到。</li>
 * </ul>
 *
 * <p>录像那一侧：录着的一路被断开时立刻停这一段（{@link MultiCameraManager}），不等 15 秒看门狗；
 * 接回之后主界面按「录像被打断」的路子自动继续。</p>
 *
 * <h3>原厂画面在用相机时不抢（2.11.0 驾驶安全审查）</h3>
 *
 * <p>别的程序占着相机、原厂画面（倒车、360、原厂侧方小窗、泊车辅助，见 {@link CarViewGate}）又在屏幕上：
 * 拿着相机的多半就是它。这时候去开相机，抢到了就是把倒车影像踢掉 —— 所以优先级变了不趁机试、
 * 30 秒那一下也跳过，等原厂画面收了再接（它放开相机时照旧立刻接回）。</p>
 */
public final class CameraTaken {

    /** 别的程序占着相机时多久试一次（看门狗 {@link CameraLiveness#step} 用）。 */
    static final long RETRY_WHILE_HELD_MS = 30_000L;
    /** 原厂画面在、先不抢：多久再看一次它收了没有。 */
    static final long CAR_VIEW_RECHECK_MS = 2_000L;

    private static Handler mainHandler;
    private static final Runnable AFTER_CAR_VIEW = CameraTaken::prioritiesChanged;

    private static final Set<String> HELD_BY_OTHERS = ConcurrentHashMap.newKeySet();

    private CameraTaken() {
    }

    /** 此刻有没有别的程序占着相机。 */
    public static boolean othersHold() {
        return !HELD_BY_OTHERS.isEmpty();
    }

    /**
     * 别的程序占着相机、原厂画面又在屏幕上：这时候别去开相机（抢回来会把倒车影像顶掉）。哪个线程都能问。
     * 2.10.10 起由看门狗（{@link CameraLiveness#step}）和 SingleCamera 开相机前问。
     */
    static boolean yieldToCarView() {
        return othersHold() && CarViewGate.isActiveNow();
    }

    /** 别的程序占着哪几路，给日志看。 */
    public static String describe() {
        return HELD_BY_OTHERS.toString();
    }

    /** 相机服务报：这一路被别的程序拿了。 */
    static void othersTook(String cameraId, android.content.Context context) {
        HELD_BY_OTHERS.add(cameraId);
        // 要靠车辆信号判断原厂画面在不在，那就在拿走期间读着（主线程）
        CarViewGate.get().watchWhileCameraTaken(context);
    }

    /** 相机服务报：这一路空出来了。别的程序一路都不占了，就把被拿走时断掉的接回来。 */
    static void othersReleased(String cameraId) {
        if (HELD_BY_OTHERS.remove(cameraId) && HELD_BY_OTHERS.isEmpty()) {
            CarViewGate.get().stopWatchingCameraTaken();
            retryNow(cameraId);
        }
    }

    /**
     * 相机服务说访问优先级变了（前后台切换那种）：别的程序还占着的话，趁机试一次。
     * 原厂画面在的话先不试，每 {@link #CAR_VIEW_RECHECK_MS} 看一次，收了再试（主线程）。
     */
    static void prioritiesChanged() {
        Handler handler = main();
        handler.removeCallbacks(AFTER_CAR_VIEW);
        if (!othersHold()) {
            return;
        }
        if (CarViewGate.isActiveNow()) {
            handler.postDelayed(AFTER_CAR_VIEW, CAR_VIEW_RECHECK_MS);
            return;
        }
        retryNow(null);
    }

    private static synchronized Handler main() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    /** @param releasedCameraId 放开的那一路；优先级变了那种传 null */
    private static void retryNow(String releasedCameraId) {
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager != null) {
            manager.retryTaken(releasedCameraId);
        }
    }
}
