package com.kooo.evcam.recovery;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.recording.RecordingIntent;

/**
 * 开机自启动（规格 §1）的统一入口：收到各类信号、判断自己能正常运行了，就把核心程序起来。
 *
 * <p>核心程序 = 用户设定状态里开着的那些：悬浮按钮、超级后视镜、自动录制。
 * 谁来调：前台服务起来时、保活任务跑到时（睡醒后一秒内必跑）、亮屏广播来时、应用更新后。
 * 幂等，多调无害：已经在的不重复起。</p>
 *
 * <p>只有「开机自启动」开着才做（规格 1.3：它管的是 App 不在时要不要带回来）；
 * 用户退出了不做（1.4）。主界面还在的时候，它自己会回到设定状态（§0），这里只管它不在的情况。
 * 录像要经过主界面这个对象（1.6）：静默把主界面拉起来，它闪一下就退后台，录像照常。</p>
 */
public final class Recovery {

    private static final String TAG = "Recovery";
    /** 前台服务每分钟都会被例行启动一次；这种例行调用五分钟做一次就够。 */
    private static final long ROUTINE_INTERVAL_MS = 5 * 60_000L;
    /** 前台服务例行启动时传的原因；别的原因（睡醒、亮屏、更新）每次都做。 */
    public static final String WHY_ROUTINE = "service-start";
    /** 拉主界面时带上：它不是人点开的，这一趟的录像选择不清（规格 1.2）。 */
    public static final String EXTRA_SILENT = "from_service_restart";

    private static long lastRoutineAtMs;
    private static String lastSummary = "";

    private Recovery() {
    }

    public static void restore(Context context, String why) {
        try {
            restoreNow(context, why);
        } catch (Throwable t) {
            AppLog.w(TAG, "restore failed (" + why + "): " + t);
        }
    }

    private static void restoreNow(Context context, String why) {
        if (UserExit.isExited(context)) {
            return;
        }
        AppConfig config = new AppConfig(context);
        if (!config.isAutoStartOnBoot()) {
            BlackBox.count("restore-skipped-switch-off");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (WHY_ROUTINE.equals(why)) {
            if (now - lastRoutineAtMs < ROUTINE_INTERVAL_MS) {
                return;
            }
            lastRoutineAtMs = now;
        }

        StringBuilder did = new StringBuilder();
        if (OverlayCoordinator.canShowOverlay(context)) {
            // 按设置起悬浮按钮和后视镜；已经在的不会重复。后视镜自己知道屏幕黑着就不接相机
            OverlayCoordinator.restoreOnLaunch(context, null);
            did.append("overlays");
        }

        boolean recording = recordingNow();
        boolean mainScreenAlive = MainActivity.getInstance() != null;
        RecordingIntent intent = RecordingIntent.current();
        boolean wantRecording = config.isAutoStartRecording() && !recording
                && (intent.shouldRestore(true) || intent.shouldAutoStart(true));
        if (wantRecording && !mainScreenAlive) {
            Intent main = new Intent(context, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            main.putExtra("auto_start_from_boot", true);
            main.putExtra(EXTRA_SILENT, true);
            context.startActivity(main);
            did.append(did.length() > 0 ? "; " : "").append("main-screen-for-recording");
        }

        String summary = did.length() == 0 ? "nothing" : did.toString();
        // 每次都记的话，例行那一条五分钟一行；只有做的事变了、或者拉了主界面，才值一行
        if (!summary.equals(lastSummary) || summary.contains("main-screen")) {
            lastSummary = summary;
            BlackBox.noteImportant("恢复（" + why + "）：" + summary + "；录像中=" + recording
                    + " 主界面在=" + mainScreenAlive + " " + intent.describe());
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
}
