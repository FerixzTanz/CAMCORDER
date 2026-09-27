package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 熄屏录制（开发者选项，规格 §3.1）：熄屏时正在录像，就拿住唤醒锁不让车机睡。
 *
 * <p>熄屏录制 = 熄屏持续录制 + 防止休眠。车机熄屏六秒就深睡，睡着的进程一行代码都不跑，
 * 要在会睡的车上接着录，只有不让它睡这一个手段（平台笔记 §3.6）。项目所有者 2026-09-27 明确允许。</p>
 *
 * <p>三条规矩：<b>只在录像期间拿</b>，录像停了就放；最长拿多久由用户设，<b>从熄屏那一刻起算</b>
 * （App 拿不到「下车」这个事件，熄屏是最接近的近似）；到点放开，车机该睡就睡，
 * 录像停在那一刻，醒来接着录（同 §2.4）。</p>
 *
 * <p>静态的：锁和计时得活在进程上，主界面可能撑不到亮屏那一刻。</p>
 */
public final class ScreenOffRecording {

    private static final String TAG = "ScreenOffRecording";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static Runnable timeout;
    private static long heldSinceMs;
    private static int heldForMinutes;

    private ScreenOffRecording() {
    }

    /** 熄屏了。正在录像、而且熄屏录制开着，才拿锁。 */
    public static void onScreenOff(Context context, boolean recording) {
        if (!recording) {
            return;
        }
        AppConfig config = new AppConfig(context);
        if (!config.isScreenOffRecordingEnabled()) {
            return;
        }
        int minutes = config.getScreenOffWakeMinutes();
        cancelTimeout();
        WakeUpHelper.acquirePersistentWakeLock(context);
        heldSinceMs = SystemClock.elapsedRealtime();
        heldForMinutes = minutes;
        timeout = () -> release("timeout");
        HANDLER.postDelayed(timeout, minutes * 60_000L);
        BlackBox.noteImportant("熄屏录制：在录像，拿住唤醒锁不让车机睡，最多 " + minutes + " 分钟");
        AppLog.i(TAG, "wake lock held for up to " + minutes + " min");
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
}
