package com.kooo.evcam.recording;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;

/**
 * 熄屏录制（开发者选项，规格 §3.1）：熄屏时正在录像，就拿住唤醒锁不让车机睡。
 *
 * <p>熄屏录制 = 熄屏持续录制 + 防止休眠。车机熄屏六秒就深睡，睡着的进程一行代码都不跑，
 * 要在会睡的车上接着录，只有不让它睡这一个手段（平台笔记 §3.6）。项目所有者 2026-09-27 明确允许。
 * 原来「常驻唤醒锁」那个开关的能力全部归到这里，再往上做：</p>
 *
 * <ul>
 *   <li><b>只在录像期间拿</b>，录像停了就放；</li>
 *   <li>最长拿多久由用户设，<b>从熄屏那一刻起算</b>（App 拿不到「下车」这个事件，熄屏是最接近的近似）；
 *       到点放开，车机该睡就睡，录像停在那一刻，醒来接着录（同 §2.4）；</li>
 *   <li>屏幕已经黑着时才开始的录像（熄屏期间接回的那种）同样拿，剩余时长从熄屏那一刻算；</li>
 *   <li>活在进程上，不靠主界面：熄屏 / 亮屏广播只能动态注册，这里自己注册一份，
 *       主界面在不在都一样；前台服务每分钟再核对一次屏幕状态。</li>
 * </ul>
 */
public final class ScreenOffRecording {

    private static final String TAG = "ScreenOffRecording";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static boolean installed;
    private static Runnable timeout;
    /** 屏幕什么时候黑的（开机起算，含深睡）；0 = 亮着或不知道。 */
    private static long screenOffAtMs;
    private static long heldSinceMs;
    private static int heldForMinutes;

    private ScreenOffRecording() {
    }

    /** 进程一起来就装上：熄屏、亮屏广播只能动态注册，而主界面可能不在。 */
    public static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        Context app = context.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff(c);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    /** 熄屏了：记下时刻；正在录像、熄屏录制开着，就拿锁。 */
    public static void onScreenOff(Context context) {
        screenOffAtMs = SystemClock.elapsedRealtime();
        ensure(context);
    }

    /** 录像开始了。屏幕可能早就黑着（熄屏期间接回的那种），那也要拿。 */
    public static void onRecordingStarted(Context context) {
        if (!screenDark(context)) {
            return;
        }
        if (screenOffAtMs == 0) {
            // 不知道什么时候黑的（进程刚起来）：从现在起算
            screenOffAtMs = SystemClock.elapsedRealtime();
        }
        ensure(context);
    }

    /** 亮屏了：放。 */
    public static void onScreenOn() {
        screenOffAtMs = 0;
        release("screen-on");
    }

    /** 前台服务每分钟问一次：锁还拿着、屏幕其实亮着，就放。亮屏广播漏了也不会一直拿着。 */
    public static void checkScreenOn(Context context) {
        if (WakeUpHelper.isPersistentWakeLockHeld() && !screenDark(context)) {
            screenOffAtMs = 0;
            release("screen-on-noticed");
        }
    }

    /** 该拿就拿、到点就放。幂等，多调无害。 */
    private static void ensure(Context context) {
        AppConfig config = new AppConfig(context);
        if (!config.isScreenOffRecordingEnabled() || !recordingNow()) {
            return;
        }
        int minutes = config.getScreenOffWakeMinutes();
        long remaining = minutes * 60_000L - (SystemClock.elapsedRealtime() - screenOffAtMs);
        if (remaining <= 0) {
            release("timeout");
            return;
        }
        cancelTimeout();
        timeout = () -> release("timeout");
        HANDLER.postDelayed(timeout, remaining);
        if (!WakeUpHelper.isPersistentWakeLockHeld()) {
            WakeUpHelper.acquirePersistentWakeLock(context);
            heldSinceMs = SystemClock.elapsedRealtime();
            heldForMinutes = minutes;
            BlackBox.noteImportant("熄屏录制：在录像，拿住唤醒锁不让车机睡，还能拿 " + remaining / 60000
                    + " 分钟（上限 " + minutes + " 分钟，从熄屏起算）");
            AppLog.i(TAG, "wake lock held, " + remaining / 60000 + " min left of " + minutes);
        }
    }

    /**
     * 放开唤醒锁。亮屏、录像停了、到点，都从这里走；没拿着时什么也不做。
     *
     * @param why 给黑匣子看的原因，用 ASCII（screen-on / recording-stopped / timeout）
     */
    public static void release(String why) {
        cancelTimeout();
        if (!WakeUpHelper.isPersistentWakeLockHeld()) {
            return;
        }
        long heldSeconds = (SystemClock.elapsedRealtime() - heldSinceMs) / 1000;
        WakeUpHelper.releasePersistentWakeLock();
        BlackBox.noteImportant("熄屏录制：放开唤醒锁（" + why + "，拿了 " + heldSeconds + " 秒，上限 "
                + heldForMinutes + " 分钟）");
        AppLog.i(TAG, "wake lock released: " + why + " after " + heldSeconds + "s");
    }

    private static void cancelTimeout() {
        if (timeout != null) {
            HANDLER.removeCallbacks(timeout);
            timeout = null;
        }
    }

    private static boolean recordingNow() {
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            return manager != null && manager.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean screenDark(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && !power.isInteractive();
    }
}
