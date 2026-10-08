package com.kooo.evcam.recording;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * {@link DriveSession} 的接线：听车辆信号，换出 P 挡就开录、人下车就停录。规则都在 DriveSession。
 *
 * <ul>
 *   <li>两个开关有一个开着就向 {@link Telemetry} 登记，<b>熄屏也不放</b> —— 开着哨兵模式时车机熄屏照样醒着，
 *       人就是在熄屏之后下车的。不开哨兵模式时车机熄屏就睡，睡着的进程什么都不跑，登记着也不费什么。</li>
 *   <li>座位空了之后读数不一定再变：每 {@link #RECHECK_MS} 再判一次，直到算出人走了或者人回来。
 *       亮屏、熄屏也各判一次 —— 车机睡醒那一刻就知道座位已经空了多久。</li>
 *   <li><b>熄屏时拉住车机一会儿</b>：P 挡、正在录、人还没算走，熄屏那一刻拿一个最多 {@link #STAY_AWAKE_MS}
 *       的唤醒锁（自己到点放）。不开哨兵模式时车机熄屏六秒就深睡，录像会冻在半截、文件没收尾；
 *       拉住这一会儿，主驾座位一空就当场停录、等 {@link #FINISH_STOP_MS} 让文件收好尾，再放车机去睡。
 *       人一直坐着就到点放开，照旧。和开发者的「熄屏录制」用的不是同一把锁，互不影响。</li>
 *   <li>开录走和悬浮按钮一样的路：相机管线在就直接交给 {@link RecordingCoordinator}；
 *       不在（主界面没开过）就把主界面拉起来开录，录起来之后它自己退回后台。用户退出了应用就不开。</li>
 * </ul>
 */
public final class DriveSessionWatcher {

    private static final String TAG = "DriveSession";
    private static final String TELEMETRY_USER = "drive-session";
    /** 座位空着、还没到 30 秒时多久再看一眼。 */
    static final long RECHECK_MS = 5_000L;
    /** 熄屏后最多拉住车机多久，等主驾座位空出来。 */
    static final long STAY_AWAKE_MS = 60_000L;
    /** 停录之后再拉住多久，让录制器把文件收好尾。 */
    static final long FINISH_STOP_MS = 5_000L;

    private static DriveSessionWatcher instance;
    /** 给 {@link RecordingCoordinator} 问：人下车了、车在 P，自己开的录像都不开。 */
    private static volatile boolean away;

    private final Context app;
    private final AppConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 原厂画面在时，隔多久再看一次能不能拉主界面开录；最多等多久。 */
    private static final long CAR_VIEW_RETRY_MS = 2_000L;
    private static final long CAR_VIEW_WAIT_MAX_MS = 5L * 60 * 1000;
    private final DriveSession session = new DriveSession();
    private boolean running;
    private PowerManager.WakeLock wakeLock;
    /** 亮屏的时刻（开机起算）；黑着是 0。上车开录前要亮屏满 10 秒（{@link DriveStartGate}）。 */
    private long screenOnSinceMs;
    /** 要开录、正在等车机和原厂相机安静下来；0 = 没在等。 */
    private long startPendingSinceMs;
    private String startPendingReason;
    private final Runnable tryStart = this::tryStart;
    private final Runnable letSleepAfterStop = () -> letSleep("stopped");

    private final Telemetry.Listener readingsListener = readings -> evaluate();
    private final Runnable recheck = this::evaluate;
    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            screenOnSinceMs = 0;
            evaluate();
            RecordingCoordinator coordinator = RecordingCoordinator.get(app);
            if (running && config.isDriveAutoStop() && !away && "P".equals(session.gear())
                    && (coordinator.isRecording() || coordinator.isWaiting())) {
                holdAwake();
            }
        }

        @Override
        public void onScreenOn() {
            screenOnSinceMs = SystemClock.elapsedRealtime();
            letSleep("screen-on");
            evaluate();
        }
    };

    public static synchronized DriveSessionWatcher get(Context context) {
        if (instance == null) {
            instance = new DriveSessionWatcher(context.getApplicationContext());
        }
        return instance;
    }

    /** 人下车了、车还在 P（「下车停录」开着时才算）。 */
    public static boolean driverAway() {
        return away;
    }

    private DriveSessionWatcher(Context app) {
        this.app = app;
        this.config = new AppConfig(app);
    }

    /** 按两个开关拉起或停下。进程启动、设置页改了开关时调。主线程。 */
    public void apply() {
        boolean want = config.isDriveAutoStart() || config.isDriveAutoStop();
        if (want == running) {
            evaluate();
            return;
        }
        running = want;
        if (want) {
            screenOnSinceMs = ScreenState.dark() ? 0 : SystemClock.elapsedRealtime();
            Telemetry.get().acquire(app, TELEMETRY_USER);
            Telemetry.get().addListener(readingsListener);
            ScreenState.addListener(screenListener);
            BlackBox.noteImportant("录像跟着车走：开（换出 P 挡开录 " + onOff(config.isDriveAutoStart())
                    + "，下车停录 " + onOff(config.isDriveAutoStop()) + "）");
            evaluate();
        } else {
            main.removeCallbacks(recheck);
            cancelPendingStart();
            letSleep("switched-off");
            ScreenState.removeListener(screenListener);
            Telemetry.get().removeListener(readingsListener);
            Telemetry.get().release(TELEMETRY_USER);
            away = false;
            BlackBox.noteImportant("录像跟着车走：关");
        }
    }

    private void evaluate() {
        if (!running) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Readings r = Telemetry.get().readings();
        VehicleState s = Telemetry.get().latest();
        RecordingCoordinator coordinator = RecordingCoordinator.get(app);

        DriveSession.Input in = new DriveSession.Input();
        in.nowElapsedMs = now;
        in.gear = r.text(Signal.GEAR);
        in.driverSeated = r.bool(Signal.SEAT_DRIVER);
        in.sentry = s.sentry;
        in.recording = coordinator.isRecording() || coordinator.isWaiting();
        in.autoStart = config.isDriveAutoStart();
        in.autoStop = config.isDriveAutoStop();
        in.stoppedByUser = RecordingIntent.current().stoppedByUser();
        in.screenOff = ScreenState.dark();

        DriveSession.Action action = session.update(in);
        away = in.autoStop && session.driverAway();
        switch (action) {
            case START:
                beginStart(session.startReason());
                break;
            case STOP:
                BlackBox.noteImportant("下车了（" + session.describe() + "，哨兵 " + in.sentry + "）：停录");
                if (in.screenOff) {
                    // 熄屏了车机六秒就睡：先拉住，停完、文件收好尾再放
                    holdAwake();
                }
                cancelPendingStart();
                RecordingIntent.current().noteLeftCar();
                com.kooo.evcam.storage.DriveLog.driverLeft(app);
                coordinator.stop(RecordingStops.Reason.LEFT_CAR);
                // 拉着车机的话，等录制器把文件收好尾再放
                main.removeCallbacks(letSleepAfterStop);
                main.postDelayed(letSleepAfterStop, FINISH_STOP_MS);
                break;
            case LEFT:
                BlackBox.noteImportant("下车了（" + session.describe() + "，哨兵 " + in.sentry + "），没在录：这一趟结束");
                cancelPendingStart();
                RecordingIntent.current().noteLeftCar();
                com.kooo.evcam.storage.DriveLog.driverLeft(app);
                if (in.autoStop) {
                    coordinator.cancelPending("left-car");
                }
                letSleep("left");
                break;
            default:
                break;
        }

        main.removeCallbacks(recheck);
        long wait = session.msUntilLeft(now);
        if (wait >= 0) {
            main.postDelayed(recheck, Math.min(RECHECK_MS, wait + 50L));
        }
    }

    /** 熄屏那一刻拉住车机，最多 {@link #STAY_AWAKE_MS}，到点自己放。 */
    private void holdAwake() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm == null) {
                    return;
                }
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CAMCORDER:DriveSession");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(STAY_AWAKE_MS);
                BlackBox.noteImportant("熄屏、P 挡、在录：拉住车机最多 " + STAY_AWAKE_MS / 1000 + " 秒，等主驾座位空出来");
            }
        } catch (Exception e) {
            AppLog.w(TAG, "wake lock failed: " + e);
        }
    }

    private void letSleep(String why) {
        main.removeCallbacks(letSleepAfterStop);
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                BlackBox.noteImportant("放开车机（" + why + "）");
            }
        } catch (Exception e) {
            AppLog.w(TAG, "wake lock release failed: " + e);
        }
    }

    /**
     * 要开录：先等车机和原厂相机安静下来（{@link DriveStartGate}），再开。
     * 上车那一刻车机刚醒、人脸识别正拿着相机、触屏在重新配置 —— 实车两次卡死都在这几秒里开录。
     */
    private void beginStart(String reason) {
        if (startPendingSinceMs != 0) {
            return;
        }
        startPendingSinceMs = SystemClock.elapsedRealtime();
        startPendingReason = reason;
        BlackBox.noteImportant("要开录（" + reason + "）：先等车机和原厂相机安静下来");
        tryStart();
    }

    private void cancelPendingStart() {
        startPendingSinceMs = 0;
        main.removeCallbacks(tryStart);
        cancelCarViewRetry();
    }

    /** 等原厂画面收掉的那一下重试（{@link #start}）；排着就只有这一个，开关关了 / 人下车了都撤掉。 */
    private Runnable carViewRetry;

    private void cancelCarViewRetry() {
        if (carViewRetry != null) {
            main.removeCallbacks(carViewRetry);
            carViewRetry = null;
        }
    }

    private void tryStart() {
        if (!running || startPendingSinceMs == 0) {
            return;
        }
        RecordingCoordinator coordinator = RecordingCoordinator.get(app);
        if (coordinator.isRecording() || coordinator.isWaiting() || RecordingIntent.current().stoppedByUser()) {
            cancelPendingStart();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long waited = now - startPendingSinceMs;
        long screenOnFor = ScreenState.dark() ? 0 : screenOnSinceMs == 0 ? -1 : now - screenOnSinceMs;
        if (!DriveStartGate.ready(screenOnFor, com.kooo.evcam.camera.CameraAvailabilityWatch.snapshot(),
                com.kooo.evcam.zeekr.StreamLayoutTable.compositeCameraId(), waited)) {
            main.postDelayed(tryStart, 1_000L);
            return;
        }
        String reason = startPendingReason;
        cancelPendingStart();
        start(coordinator, reason, waited, false);
    }

    private void start(RecordingCoordinator coordinator, String reason, long waitedMs, boolean retry) {
        if (UserExit.blocks(app, "DriveSession")) {
            AppLog.i(TAG, "start skipped: user exited the app");
            return;
        }
        if (!retry) {
            BlackBox.noteImportant("开录（" + reason + "，等了 " + waitedMs / 1000 + " 秒让车机安静）");
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null || manager.isReleased()) {
            manager = CameraManagerHolder.getInstance().getOrInit(app);
        }
        if (manager != null && !manager.isReleased()) {
            coordinator.setCameraManager(manager);
            coordinator.resetBudget();
            coordinator.request(RecordingCoordinator.Why.DRIVE);
            return;
        }
        // 相机管线得是主界面开好的：像悬浮按钮那样把主界面拉起来开录，录起来之后它自己退回后台
        if (com.kooo.evcam.zeekr.CarViewGate.isActiveNow()) {
            // 原厂画面（倒车、360、泊车）在屏幕上：不拉主界面盖住它，每 2 秒再看，收了再拉（最多等 5 分钟）
            // （2.11.0 驾驶安全审查）
            if (waitedMs < CAR_VIEW_WAIT_MAX_MS) {
                AppLog.i(TAG, "相机管线不在，但原厂画面在：先不拉主界面，2 秒后再看");
                final long waitedSoFar = waitedMs + CAR_VIEW_RETRY_MS;
                cancelCarViewRetry();
                carViewRetry = () -> {
                    carViewRetry = null;
                    // 等的这一阵开关关了、熄屏了、人下车了、或者自己停了 / 已经在录：不再开
                    if (!running || ScreenState.dark() || session.driverAway()
                            || RecordingIntent.current().stoppedByUser()
                            || coordinator.isRecording() || coordinator.isWaiting()) {
                        return;
                    }
                    start(coordinator, reason, waitedSoFar, true);
                };
                main.postDelayed(carViewRetry, CAR_VIEW_RETRY_MS);
            } else {
                BlackBox.noteImportant("原厂画面一直在，放弃这一次上车开录");
            }
            return;
        }
        AppLog.i(TAG, "相机管线不在，拉起主界面开录");
        Intent intent = new Intent(app, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("auto_start_recording", true);
        try {
            app.startActivity(intent);
        } catch (Exception e) {
            AppLog.w(TAG, "拉起主界面失败: " + e);
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }
}
