package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.MultiCameraManager;
// import android.app.AlarmManager;  // 已移除，使用 TIME_TICK 替代
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
// import android.os.SystemClock;  // 已移除 AlarmManager
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务，用于在后台使用摄像头
 * Android 11+ 要求后台使用摄像头时必须有前台服务
 * 
 * 增强保活功能：
 * - 当无障碍服务未开启时，此服务会动态注册 TIME_TICK 广播
 * - TIME_TICK 每分钟触发一次，可以保持应用活跃
 * - onTaskRemoved: 用户滑动清除应用时自动重启
 * - onDestroy: 服务被杀时发送延迟重启广播
 * - WakeLock: 防止系统休眠（需用户开启）
 */
public class CameraForegroundService extends Service {
    private static final String TAG = "CameraForegroundService";
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // 服务重启延迟时间
    private static final long RESTART_DELAY_MS = 1000;

    private static final long CAMERA_REPAIR_INTERVAL_MS = 10000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable cameraRepairRunnable;

    private static volatile boolean isForegroundReady = false;
    private static final java.util.List<Runnable> pendingReadyCallbacks = new java.util.ArrayList<>();

    /**
     * 前台服务就绪后执行回调。
     * 如果服务已经运行，立即在主线程执行；否则排队等待 startForeground 完成后执行。
     */
    public static void whenReady(Context context, Runnable callback) {
        if (isForegroundReady) {
            callback.run();
        } else {
            synchronized (pendingReadyCallbacks) {
                pendingReadyCallbacks.add(callback);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:CameraForegroundService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("前台服务 CameraForegroundService onCreate");
        if (UserExit.blocks(this, "CameraForegroundService.onCreate")) {
            // 系统按 START_STICKY 重启了我们，但用户已经退出：什么都别起（尤其别去拉主界面），
            // 在 onStartCommand 里把自己停掉
            exitedIdle = true;
            return;
        }
        AppLog.d(TAG, "Service created");
        createNotificationChannel();
        
        // 保活开着就注册 TIME_TICK 广播（每分钟确认一次前台服务在）
        registerTimeTickIfNeeded();
                
        // 黑匣子心跳：每分钟一行。停车那一夜有没有真的睡过去、睡了多久，
        // 全在这些行的 slept= 里；中间断掉的那一段就是「我们不在」
        startBlackBoxHeartbeat();

        // 相机服务眼里每一路空不空。环视卡死、只能重启车机的那种状态，
        // 要靠它说出「是谁占着」—— 注册时系统会把当前状态报一遍
        com.kooo.evcam.camera.CameraAvailabilityWatch.start(this);

        // 前台服务起来了 = 我们的代码有机会运行了：按开机自启动的规矩恢复核心程序（规格 §1）
        com.kooo.evcam.recovery.Recovery.restore(this, "service-create");

        startCameraRepairLoop();
    }
    
    /**
     * 唤醒锁不在这里管了：它只属于「熄屏录制」（规格 §3.1，{@code ScreenOffRecording}），
     * 熄屏时在录像才拿、录像停了就放、到了用户设的时长就放。
     */

    /** 保活开着就注册 TIME_TICK 广播（每分钟触发，前台服务不在就拉起来）；无障碍服务在跑的话它自己有心跳。 */
    private void registerTimeTickIfNeeded() {
        if (new AppConfig(this).isKeepAliveEnabled() && !KeepAliveAccessibilityService.isRunning()
                && !KeepAliveReceiver.isTimeTickRegistered()) {
            KeepAliveReceiver.registerTimeTick(this);
        }
    }

    /** 用户已退出、这一次是被系统重启的：onCreate 什么都没起，onStartCommand 里停掉自己。 */
    private boolean exitedIdle;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (exitedIdle || UserExit.isExited(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // flags 里的 START_FLAG_RETRY / START_FLAG_REDELIVERY 直接说明
        // 这一次是不是系统在做 sticky 重启 —— 「START_STICKY 到底生不生效」看它。
        //
        // 只有那种才值一行。每分钟那一次例行叫醒不记：原来一律记，48KB 的导出只装得下
        // 三个小时；后来改成计数，睡眠唤醒那一轮研究完（平台笔记 §3.6）计数也没用了
        if (flags != 0 || intent == null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant(
                    "CameraForegroundService onStartCommand flags=" + flags
                            + (intent == null ? " intent=null(sticky重启)" : "")
                            + " startId=" + startId);
        }
        AppLog.d(TAG, "Service started");
        
        // 每次启动时检查并注册 TIME_TICK
        registerTimeTickIfNeeded();
                
        // 每次被启动都按开机自启动的规矩看一眼该恢复什么（例行的那种五分钟做一次）
        com.kooo.evcam.recovery.Recovery.restore(this, com.kooo.evcam.recovery.Recovery.WHY_ROUTINE);
        startCameraRepairLoop();

        // 从Intent获取通知内容，如果没有则使用默认内容
        String title = intent != null ? intent.getStringExtra("title") : null;
        String content = intent != null ? intent.getStringExtra("content") : null;

        if (title == null) {
            title = getString(R.string.notif_running);
        }
        if (content == null) {
            content = getString(R.string.notif_running_desc);
        }

        // 创建通知
        Notification notification = createNotification(title, content);

        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);

        // 标记就绪，执行所有等待的回调
        isForegroundReady = true;
        synchronized (pendingReadyCallbacks) {
            for (Runnable cb : pendingReadyCallbacks) {
                mainHandler.post(cb);
            }
            pendingReadyCallbacks.clear();
        }

        // 保活关着就不让系统重启我们（规格 §3：关 = 被杀了不回来）
        return new AppConfig(this).isKeepAliveEnabled() ? START_STICKY : START_NOT_STICKY;
    }

    /** 黑匣子心跳间隔：一分钟看一次。 */
    private static final long BLACK_BOX_TICK_MS = 60_000L;
    /** 状态没变的话，多久才值得再写一行。 */
    private static final long BLACK_BOX_QUIET_MS = 10 * 60_000L;

    /** 上一行心跳写的是什么状态，以及什么时候写的（开机起算，含深睡）。 */
    private boolean lastBeatRecording;
    private boolean lastBeatScreenOn;
    private long lastBeatAtMs;

    private final android.os.Handler blackBoxHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable blackBoxTick = new Runnable() {
        @Override
        public void run() {
            // 每分钟看一次，但只有「变了」或者「安静太久了」才写下来。
            //
            // 一分钟一行看着不多，一夜也就六十行 —— 可它一分钟并不止一行，
            // 连带前台服务那两行，三分之二的黑匣子都是这种什么也没发生的行。
            // 真正要看的是变化：什么时候开始录、什么时候熄的屏，以及
            // <b>两行之间 slept= 涨了多少</b>，那一段就是车机睡过去的时间。
            boolean recording = isRecordingNow();
            boolean screenOn = isScreenOnNow();
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastBeatAtMs == 0 || recording != lastBeatRecording || screenOn != lastBeatScreenOn
                    || now - lastBeatAtMs >= BLACK_BOX_QUIET_MS) {
                com.kooo.evcam.blackbox.BlackBox.note(
                        "心跳 recording=" + recording + " screenOn=" + screenOn);
                lastBeatRecording = recording;
                lastBeatScreenOn = screenOn;
                lastBeatAtMs = now;
            }
            // 熄屏录制拿着的锁，屏幕其实亮了就该放（亮屏广播漏了也不至于一直拿着）
            com.kooo.evcam.recording.ScreenOffRecording.checkScreenOn(CameraForegroundService.this);
            com.kooo.evcam.blackbox.BlackBox.flushCountsIfDue();
            blackBoxHandler.postDelayed(this, BLACK_BOX_TICK_MS);
        }
    };

    private void startBlackBoxHeartbeat() {
        blackBoxHandler.removeCallbacks(blackBoxTick);
        blackBoxHandler.postDelayed(blackBoxTick, BLACK_BOX_TICK_MS);
    }

    private boolean isRecordingNow() {
        try {
            com.kooo.evcam.camera.MultiCameraManager manager =
                    com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            return manager != null && manager.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isScreenOnNow() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onDestroy() {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("CameraForegroundService onDestroy");
        blackBoxHandler.removeCallbacks(blackBoxTick);
        AppLog.d(TAG, "Service destroyed - 尝试重启...");
        isForegroundReady = false;
        stopCameraRepairLoop();

        // 服务被杀时，发送延迟重启广播 —— 用户主动退出时不发：这正是以前「退不掉」的一条路
        if (!UserExit.isExited(this)) {
            scheduleServiceRestart();
        }

        super.onDestroy();
    }

    private void startCameraRepairLoop() {
        stopCameraRepairLoop();
        cameraRepairRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    com.kooo.evcam.camera.MultiCameraManager cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
                    if (cameraManager != null) {
                        int repaired = cameraManager.checkAndRepairCameras();
                        if (repaired > 0) {
                            AppLog.w(TAG, "Camera repair triggered for " + repaired + " cameras");
                        }
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera repair loop error: " + e.getMessage(), e);
                }
                mainHandler.postDelayed(this, CAMERA_REPAIR_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(cameraRepairRunnable, CAMERA_REPAIR_INTERVAL_MS);
    }

    private void stopCameraRepairLoop() {
        if (cameraRepairRunnable != null) {
            mainHandler.removeCallbacks(cameraRepairRunnable);
            cameraRepairRunnable = null;
        }
    }
    
    /**
     * 当用户从最近任务中滑动清除应用时调用
     * 这是保活的关键：在被清除时重新启动服务
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AppLog.d(TAG, "onTaskRemoved - 应用被从最近任务清除，尝试重启服务...");
        
        // 立即重启服务
        scheduleServiceRestart();
        
        super.onTaskRemoved(rootIntent);
    }
    
    /**
     * 调度服务重启
     * 使用 Handler 延迟重启，避免立即重启被系统拦截
     */
    private void scheduleServiceRestart() {
        try {
            // 方案1：使用 Handler 延迟重启
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    AppLog.d(TAG, "执行延迟重启...");
                    start(getApplicationContext(), getString(R.string.notif_background_title),
                            getString(R.string.notif_tap_to_return));
                } catch (Exception e) {
                    AppLog.e(TAG, "延迟重启失败: " + e.getMessage(), e);
                }
            }, RESTART_DELAY_MS);
            
            // 方案2：发送保活广播（备份）
            KeepAliveReceiver.sendKeepAliveCheck(getApplicationContext());
            
        } catch (Exception e) {
            AppLog.e(TAG, "调度重启失败: " + e.getMessage(), e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * 更新通知内容
     */
    public void updateNotification(String title, String content) {
        Notification notification = createNotification(title, content);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    /**
     * 静态方法：启动前台服务
     * @param context 上下文
     * @param title 通知标题
     * @param content 通知内容
     */
    public static void start(Context context, String title, String content) {
        // 保活广播、ContentProvider、无障碍服务都从这里启动前台服务 —— 用户退出后一律不启动
        if (UserExit.blocks(context, "CameraForegroundService.start")) {
            return;
        }
        Intent intent = new Intent(context, CameraForegroundService.class);
        intent.putExtra("title", title);
        intent.putExtra("content", content);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 13+ 对前台服务启动有限制，但录制时需要强制启动
            // 使用 startForegroundService 启动，服务必须在 5 秒内调用 startForeground
            try {
                context.startForegroundService(intent);
                AppLog.d(TAG, "Starting foreground service: " + title);
            } catch (Exception e) {
                // Android 12 起，后台启前台服务会被拦 —— 拦不拦、什么条件下拦，看这一行
                com.kooo.evcam.blackbox.BlackBox.noteImportant("startForegroundService 被拒: " + e.getClass().getSimpleName()
                        + " " + e.getMessage());
                AppLog.e(TAG, "启动前台服务失败: " + e.getMessage(), e);
                // 如果失败，尝试普通启动
                try {
                    context.startService(intent);
                    AppLog.d(TAG, "尝试普通服务启动");
                } catch (Exception e2) {
                    AppLog.e(TAG, "普通启动也失败: " + e2.getMessage(), e2);
                }
            }
        } else {
            context.startService(intent);
            AppLog.d(TAG, "Starting service: " + title);
        }
    }
    
    /**
     * 检查应用是否在前台
     */
    private static boolean isAppInForeground(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                if (processes != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                        if (process.processName.equals(context.getPackageName())) {
                            // 优先检查 IMPORTANCE_FOREGROUND 或 IMPORTANCE_VISIBLE
                            return process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                    || process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "检查应用前台状态失败: " + e.getMessage(), e);
        }
        return false;
    }

    /**
     * 静态方法：停止前台服务
     * @param context 上下文
     */
    public static void stop(Context context) {
        isForegroundReady = false;
        Intent intent = new Intent(context, CameraForegroundService.class);
        context.stopService(intent);
        AppLog.d(TAG, "Stopping foreground service");
    }
}
