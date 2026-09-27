package com.kooo.evcam.camera;

import android.content.Context;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原厂功能拿走相机时让路（平台笔记 §3.1：第三方应用永远不能被视为优先于原厂车辆功能）。
 *
 * <h3>实测（2026-09-26 22:41:52）</h3>
 *
 * <p>车机自己的座舱画面一开，相机服务先把相机 1 标成「被别的程序占用」，3 毫秒后我们的环视（相机 2）
 * 被断开 —— 这两路在 HAL 里是冲突的。基座的反应是立刻重连（退避 500 毫秒起）；主界面在前台时
 * 我们的优先级不比原厂应用低，重连一成功就把原厂画面顶掉。用户看到的就是
 * 「主界面开着时，车机自己的后座舱画面打不开；主界面关掉过一会儿才行」——
 * 过那一会儿是我们的进程掉到后台、重连抢不过它了。</p>
 *
 * <h3>规则</h3>
 *
 * <p>相机服务说有相机被别的程序占着（不是我们开的），我们所有的重连、重开、看门狗一律停手；
 * 它放开了，再把该开的接回来（{@link MultiCameraManager#resumeAfterYield}）。
 * 占用超过 {@link #MAX_YIELD_MS} 还没放开的，当它是相机服务里没清掉的残留（§3.1.1），
 * 恢复正常的退避重试。</p>
 */
public final class CameraYield {

    /** 别的程序占着相机超过这么久还不放，就不再让了：多半是相机服务里的残留占用。 */
    static final long MAX_YIELD_MS = 10 * 60_000L;

    private static final Set<String> HELD_BY_OTHERS = ConcurrentHashMap.newKeySet();
    private static volatile Context app;
    /** 别的程序从什么时候起占着相机（开机起算，含深睡）；0 = 没有。 */
    private static volatile long othersSinceMs;

    private CameraYield() {
    }

    /** 纯规则：别的程序占着、而且还没占太久，就让。 */
    static boolean shouldYield(long othersSince, long nowMs) {
        return othersSince != 0 && nowMs - othersSince < MAX_YIELD_MS;
    }

    static void init(Context context) {
        app = context.getApplicationContext();
    }

    /**
     * 此刻该不该让路。开发者选项「相机让路」关着（默认）就永远不让 ——
     * 项目所有者 2026-09-27：先照常拿相机，争用过程由 {@link CameraContention} 记下来。
     */
    public static boolean shouldYield() {
        Context context = app;
        if (context == null || !new AppConfig(context).isCameraYieldEnabled()) {
            return false;
        }
        return shouldYield(othersSinceMs, SystemClock.elapsedRealtime());
    }

    /** 别的程序占着哪几路，给日志看。 */
    public static String describe() {
        return HELD_BY_OTHERS.toString();
    }

    /** 相机服务报：这一路被别的程序占了。 */
    static void othersTook(String cameraId) {
        boolean wasEmpty = HELD_BY_OTHERS.isEmpty();
        HELD_BY_OTHERS.add(cameraId);
        if (wasEmpty) {
            othersSinceMs = SystemClock.elapsedRealtime();
        }
    }

    /** 相机服务报：这一路空出来了。别的程序一路都不占了，就把让路时断掉的接回来。 */
    static void othersReleased(String cameraId) {
        if (!HELD_BY_OTHERS.remove(cameraId) || !HELD_BY_OTHERS.isEmpty()) {
            return;
        }
        boolean wasYielding = shouldYield();
        othersSinceMs = 0;
        if (!wasYielding) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager != null) {
            manager.resumeAfterYield(cameraId);
        }
    }
}
