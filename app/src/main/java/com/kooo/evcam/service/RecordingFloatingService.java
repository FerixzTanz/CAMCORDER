package com.kooo.evcam.service;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import androidx.core.content.ContextCompat;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.overlay.FloatingAction;

/**
 * 悬浮按钮服务：整个应用只有这一个悬浮按钮。
 *
 * <h3>为什么类名还叫 Recording</h3>
 *
 * <p>0.45 之前有两个按钮：这一个管录制、另一个管打开应用。合并时留下了这一个
 * —— 录制那套「应用在前台 / 在后台 / 没运行」的分支是真正难的部分，另一个只是
 * 一句 startActivity。类名没跟着改：改名和合并放进同一个提交，diff 就没法看了。</p>
 *
 * <p>按钮的颜色说明现在是不是在录（录制红 + 呼吸，空闲是个空心圈）。单击和长按
 * 各做什么由设置决定，默认都是打开主界面（见 {@link FloatingAction}）。</p>
 */
public class RecordingFloatingService extends Service {
    private static final String TAG = "RecordingFloatingService";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_RECORDING_FLOATING";
    public static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_RECORDING_FLOATING";
    public static final String ACTION_UPDATE_SIZE = "com.kooo.evcam.action.UPDATE_RECORDING_FLOATING_SIZE";

    /** 大小、字号、透明度、时长显示改了，就地重贴一遍。 */
    public static final String ACTION_UPDATE_STYLE = "com.kooo.evcam.action.UPDATE_FLOATING_STYLE";
    /** 「重置悬浮窗布局」：大小和位置回默认，当场挪过去。 */
    public static final String ACTION_RESET_POSITION = "com.kooo.evcam.action.RESET_FLOATING_POSITION";

    /**
     * 录制状态变了，告诉按钮换颜色。
     *
     * <p>这个广播原来由已删掉的那个按钮的类发，本服务一直同时听着这两个 action
     * ——所以搬过来只是换个发信人，收信的规则一个字没动。</p>
     */
    public static final String ACTION_RECORDING_STATE_CHANGED = "com.kooo.evcam.RECORDING_STATE_CHANGED";
    public static final String EXTRA_IS_RECORDING = "is_recording";

    public static void sendRecordingStateChanged(Context context, boolean isRecording) {
        // 进程内直接告诉悬浮按钮，不再发广播：Android 12 上接收器挡不住别的应用（2.11.0 安全审查，见 InAppEvents）
        com.kooo.evcam.InAppEvents.recordingStateChanged(isRecording);
    }

    public static final String EXTRA_BUTTON_SIZE = "button_size";
    public static final String EXTRA_TEXT_SIZE = "text_size";

    /** 按住多久算长按。比系统默认的 500ms 略长一点：这个按钮是在车上按的。 */
    private static final long LONG_PRESS_MS = 600;

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private AppConfig appConfig;
    private WindowManager windowManager;

    // 长按：计时器要在 appConfig 之后声明，它读的是 appConfig
    private boolean longPressFired = false;
    /** 位置锁着时手指滑了一段：这一下既不是点击也不是长按。 */
    private boolean slidWhileLocked = false;
    private final Runnable longPressRunnable = () -> {
        longPressFired = true;
        perform(FloatingAction.fromKey(appConfig.getFloatingLongPressAction()));
    };

    // 视图组件
    private FrameLayout floatingContainer;
    private RecordingButtonView recordingButton;
    private TextView timeTextView;

    // 布局参数
    private WindowManager.LayoutParams layoutParams;
    private int screenWidth;
    private int screenHeight;

    // 拖动相关
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private static final int CLICK_THRESHOLD = 10;

    // 录制服务
    private CameraRecordingService recordingService;
    private boolean isServiceBound = false;

    // 录制状态
    private boolean isRecording = false;
    /**
     * 想录却录不起来、或者环视正在从出错里恢复（CAMCORDER 2026-10-07）：按钮改成极氪橙，看得出「出状况了，在重试」。
     * 实车卡死那两次，按钮只显示「没在录」，看不出应用正在一轮一轮地重试。
     */
    private final Runnable troubleCheck = new Runnable() {
        @Override
        public void run() {
            if (recordingButton != null) {
                recordingButton.setTrouble(cameraInTrouble());
            }
            // 按钮显示的状态和真的对一次：主界面重建时它可能被告知了过时的状态（实车 2026-10-08，审查）
            boolean actual = com.kooo.evcam.recording.RecordingCoordinator.get(RecordingFloatingService.this).isRecording();
            if (actual != isRecording) {
                updateRecordingState(actual);
            }
            if (mainHandler != null) {
                mainHandler.postDelayed(this, TROUBLE_CHECK_MS);
            }
        }
    };
    private static final long TROUBLE_CHECK_MS = 2_000L;
    private long recordingStartTime = 0;
    private Runnable timeUpdateRunnable;

    // 广播接收器

    public class LocalBinder extends Binder {
        public RecordingFloatingService getService() {
            return RecordingFloatingService.this;
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CameraRecordingService.LocalBinder binder = (CameraRecordingService.LocalBinder) service;
            recordingService = binder.getService();
            isServiceBound = true;

            // 之后的变化服务会发 ACTION_RECORDING_STATE_CHANGED 广播，本服务一直听着；这里只对一次当前状态
            updateRecordingState(recordingService.isRecording());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordingService = null;
            isServiceBound = false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:RecordingFloatingService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("后台服务 RecordingFloatingService onCreate");
        AppLog.d(TAG, "录制悬浮服务创建");

        mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(troubleCheck, TROUBLE_CHECK_MS);
        appConfig = new AppConfig(this);
        // 原厂画面（倒车、360、泊车）在时按钮让开：全透明、不接触摸（2.11.0 驾驶安全审查）
        com.kooo.evcam.zeekr.CarViewGate.get().addListener(this, carViewListener);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // 在后台线程绑定录制服务
        new Thread(() -> bindRecordingService()).start();

        // 注册大小更新广播接收器
        registerSizeUpdateReceiver();
        
        // 注册录制状态广播接收器
        registerRecordingStateReceiver();
    }

    /**
     * 以前在这里注册两个广播接收器（大小、录制状态）。Android 12 上它们对所有应用开放：
     * 谁都能让按钮显示「在录」，或者塞个读不出来的 extra 把进程弄崩（2.11.0 安全审查）。
     * 大小改成走 startService（ACTION_UPDATE_STYLE / ACTION_UPDATE_SIZE，服务不对外），状态走 {@link com.kooo.evcam.InAppEvents}。
     */
    private void registerSizeUpdateReceiver() {
    }

    private final com.kooo.evcam.InAppEvents.RecordingStateListener recordingStateListener =
            recording -> updateRecordingState(recording);

    private void registerRecordingStateReceiver() {
        com.kooo.evcam.InAppEvents.addRecordingStateListener(recordingStateListener);
    }

    private void updateFloatingSize(int buttonSizeDp, int textSizeSp) {
        if (floatingContainer == null || recordingButton == null || timeTextView == null) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;

        // 更新按钮大小
        if (buttonSizeDp > 0) {
            int buttonSize = (int) (buttonSizeDp * density);
            recordingButton.getLayoutParams().width = buttonSize;
            recordingButton.getLayoutParams().height = buttonSize;
            recordingButton.setButtonSize(buttonSize);
            recordingButton.requestLayout();
        }

        // 更新时间文字大小
        if (textSizeSp > 0) {
            timeTextView.setTextSize(textSizeSp);
            // 根据按钮大小调整padding
            int buttonSize = recordingButton.getLayoutParams().width;
            int padding = Math.max(8, buttonSize / 8);
            timeTextView.setPadding(padding, padding / 2, padding, padding / 2);
            timeTextView.requestLayout();
        }

        // 刷新窗口
        windowManager.updateViewLayout(floatingContainer, layoutParams);
        AppLog.d(TAG, "悬浮按钮大小已更新");
    }

    /**
     * 大小、字号、透明度、显示不显示时长：<b>就地</b>改，不重建视图。
     *
     * <h3>为什么不是关掉再开</h3>
     *
     * <p>0.45 里透明度和时长开关走的是「先发 HIDE 再发 SHOW」，两条指令各自
     * 起一个线程 —— 顺序没有保证。SHOW 先跑完、HIDE 后到，按钮就没了。
     * 「有时能调、有时按钮消失」和「关掉时长把整个按钮关掉」是同一个竞态。</p>
     *
     * <p>这里不碰生命周期，只把当前配置重新读一遍贴上去。</p>
     */
    private void applyStyle() {
        if (floatingContainer == null || recordingButton == null || timeTextView == null) {
            return;
        }
        updateFloatingSize(appConfig.getRecordingFloatingButtonSizeDp(),
                appConfig.getRecordingFloatingTimeTextSizeSp());
        floatingContainer.setAlpha(appConfig.getFloatingWindowAlpha() / 100f);
        timeTextView.setVisibility(isRecording && appConfig.isFloatingDurationVisible()
                ? View.VISIBLE : View.GONE);
        AppLog.d(TAG, "悬浮按钮外观已更新: 大小 "
                + appConfig.getRecordingFloatingButtonSizeDp() + "dp, 透明度 "
                + appConfig.getFloatingWindowAlpha() + "%, 时长 "
                + (appConfig.isFloatingDurationVisible() ? "显示" : "隐藏"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "RecordingFloatingService")) {
            // 用户已经退出：被系统重启也不起来
            stopSelf();
            return START_NOT_STICKY;
        }
        // 只记系统做 sticky 重启的那种（flags 里有 RETRY / REDELIVERY，或 intent 为空）：
        // 「START_STICKY 到底生不生效」看它。例行的启动不记，和前台服务一样
        if (flags != 0 || intent == null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("RecordingFloatingService onStartCommand flags=" + flags
                    + (intent == null ? " intent=null(sticky重启)" : "")
                    + " startId=" + startId);
        }
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_HIDE.equals(action)) {
                hideFloatingWindow();
            } else if (ACTION_UPDATE_STYLE.equals(action) || ACTION_UPDATE_SIZE.equals(action)) {
                // 只在已经显示时才有意义；没显示就什么都不做，
                // 尤其不能顺手 showFloatingWindow —— 那会把关掉的按钮又拉出来
                if (floatingContainer != null) {
                    mainHandler.post(this::applyStyle);
                }
            } else if (ACTION_RESET_POSITION.equals(action)) {
                // 同上：没显示就不管，下次显示时自然落在默认位置
                if (floatingContainer != null) {
                    mainHandler.post(this::resetPosition);
                }
            } else {
                showFloatingWindow();
            }
        }
        // 被杀了要不要重启：跟着「保持后台运行」开关（规格 §3）。悬浮按钮由 Recovery 按设置恢复，不用自己粘着
        return com.kooo.evcam.CameraForegroundService.stickiness(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("RecordingFloatingService onDestroy");
        com.kooo.evcam.zeekr.CarViewGate.get().removeListener(carViewListener);
        if (mainHandler != null) {
            mainHandler.removeCallbacks(troubleCheck);
        }
        super.onDestroy();
        hideFloatingWindow();

        if (isServiceBound) {
            unbindService(serviceConnection);
        }

        com.kooo.evcam.InAppEvents.removeRecordingStateListener(recordingStateListener);

        stopTimeUpdate();
    }

    // ========== 服务绑定 ==========

    private void bindRecordingService() {
        Intent intent = new Intent(this, CameraRecordingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // ========== 悬浮窗管理 ==========

    private void showFloatingWindow() {
        if (floatingContainer != null) {
            return; // 已经显示
        }

        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限");
            stopSelf();
            return;
        }

        createFloatingWindow();
    }

    private boolean carViewActive;
    private final com.kooo.evcam.zeekr.CarViewGate.Listener carViewListener = active -> {
        carViewActive = active;
        applyStandDown();
    };

    /** 原厂画面在时：按钮全透明、不接触摸；收了再回来。 */
    private void applyStandDown() {
        if (floatingContainer == null || layoutParams == null || windowManager == null) {
            return;
        }
        layoutParams.alpha = carViewActive ? 0f : 1f;
        if (carViewActive) {
            layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            windowManager.updateViewLayout(floatingContainer, layoutParams);
        } catch (Exception e) {
            AppLog.w(TAG, "悬浮按钮让开 / 回来失败: " + e.getMessage());
        }
    }

    private void hideFloatingWindow() {
        if (floatingContainer != null && windowManager != null) {
            try {
                windowManager.removeView(floatingContainer);
            } catch (IllegalArgumentException e) {
                // addView 当初就失败了（没有悬浮窗权限之类）：窗口不在，别为了摘它把进程（连同在录的录像）弄崩
                AppLog.w(TAG, "悬浮按钮窗口本来就不在: " + e.getMessage());
            }
            floatingContainer = null;
            recordingButton = null;
            timeTextView = null;
        }
        stopTimeUpdate();
    }

    /**
     * 放到上次拖到的位置；没存过（或刚被重置）就放到默认位置。
     *
     * <p>默认位置贴在右上角，离右边、离上边多远按 dp 记（实车调好后测得，见 AppConfig）。</p>
     */
    private void placeAtSavedOrDefault(int buttonSizePx) {
        int x = appConfig.getRecordingFloatingX();
        int y = appConfig.getRecordingFloatingY();
        if (x < 0 || y < 0) {
            float density = getResources().getDisplayMetrics().density;
            x = AppConfig.defaultFloatingX(screenWidth, buttonSizePx, density);
            y = AppConfig.defaultFloatingY(density);
        }
        layoutParams.x = Math.min(x, Math.max(0, screenWidth - buttonSizePx));
        layoutParams.y = Math.min(y, Math.max(0, screenHeight - buttonSizePx));
    }

    /**
     * 重置之后当场挪过去。
     *
     * <p>以前重置只清配置，按钮留在原地，要等下次重新显示才回去 —— 点了提示
     * 「已重置」、按钮纹丝不动，看起来就是没生效。</p>
     */
    private void resetPosition() {
        if (floatingContainer == null || recordingButton == null) {
            return;
        }
        applyStyle();
        placeAtSavedOrDefault(recordingButton.getLayoutParams().width);
        windowManager.updateViewLayout(floatingContainer, layoutParams);
        AppLog.i(TAG, "悬浮按钮已回到默认位置: " + layoutParams.x + "," + layoutParams.y);
    }

    private void createFloatingWindow() {
        // 获取配置的大小
        int buttonSizeDp = appConfig.getRecordingFloatingButtonSizeDp();
        int timeTextSizeSp = appConfig.getRecordingFloatingTimeTextSizeSp();
        float density = getResources().getDisplayMetrics().density;
        int buttonSize = (int) (buttonSizeDp * density);

        // 创建容器
        floatingContainer = new FrameLayout(this);
        // 透明度沿用原来那个按钮的设置项，合并之后它管这一个
        floatingContainer.setAlpha(appConfig.getFloatingWindowAlpha() / 100f);

        // 创建水平布局容器
        LinearLayout horizontalContainer = new LinearLayout(this);
        horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);
        horizontalContainer.setGravity(Gravity.CENTER_VERTICAL);

        // 创建录制按钮
        recordingButton = new RecordingButtonView(this, buttonSize);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        horizontalContainer.addView(recordingButton, buttonParams);

        // 创建时间显示
        timeTextView = new TextView(this);
        timeTextView.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        timeTextView.setTextSize(timeTextSizeSp);
        timeTextView.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        // 根据按钮大小调整padding
        int padding = Math.max(8, buttonSize / 8);
        timeTextView.setPadding(padding, padding / 2, padding, padding / 2);
        timeTextView.setVisibility(View.GONE); // 默认隐藏
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        timeParams.leftMargin = Math.max(4, buttonSize / 16); // 距离按钮
        horizontalContainer.addView(timeTextView, timeParams);

        // 将水平容器添加到浮动容器
        floatingContainer.addView(horizontalContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        // 设置布局参数
        int windowType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        // 恢复上次拖到的位置；没存过就用默认位置
        placeAtSavedOrDefault(buttonSize);

        // 设置触摸事件
        floatingContainer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        longPressFired = false;
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);

                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            // 位置锁上时手指照样能滑，只是按钮不跟着走 ——
                            // 但这一下已经不算「点一下」了，也不该触发长按
                            isDragging = !appConfig.isFloatingPositionLocked();
                            mainHandler.removeCallbacks(longPressRunnable);
                            if (!isDragging) {
                                slidWhileLocked = true;
                            }
                        }

                        if (isDragging) {
                            int newX = initialX + deltaX;
                            int newY = initialY + deltaY;

                            // 边界限制：只把按钮夹在屏幕内，不再额外限制活动范围。
                            //
                            // 原实现是：
                            //     int maxWidth = screenWidth - 200;
                            //     newX = Math.max(0, Math.min(newX, screenWidth - maxWidth));
                            // screenWidth - maxWidth 恒等于 200，等于把 X 锁死在 0..200 ——
                            // 无论屏幕多宽，按钮都只能在左边一小条里移动。这是个笔误，
                            // 本意应该是 Math.min(newX, maxWidth)。
                            newX = Math.max(0, Math.min(newX, screenWidth - buttonSize));
                            newY = Math.max(0, Math.min(newY, screenHeight - buttonSize));

                            layoutParams.x = newX;
                            layoutParams.y = newY;
                            windowManager.updateViewLayout(floatingContainer, layoutParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        mainHandler.removeCallbacks(longPressRunnable);
                        if (slidWhileLocked) {
                            slidWhileLocked = false;
                            return true;
                        }
                        if (!isDragging && !longPressFired) {
                            perform(FloatingAction.fromKey(appConfig.getFloatingTapAction()));
                        } else if (!isDragging) {
                            // 长按已经在计时器里做过了，抬手不再做第二件事
                            AppLog.d(TAG, "长按已处理，忽略这次抬手");
                        } else {
                            // 拖动结束才落盘，避免拖动过程中反复写 SharedPreferences
                            appConfig.setRecordingFloatingPosition(layoutParams.x, layoutParams.y);
                        }
                        return true;
                }
                return false;
            }
        });

        // 添加到窗口
        try {
            windowManager.addView(floatingContainer, layoutParams);
            // 在不在录只问协调器（和录制服务、前台通知同一个答案），之后的变化它会广播过来。
            // 以前靠主界面隔 500 ms 推它自己记的状态：主界面刚重建、还没对上时推的是「没在录」，
            // 录着像按钮却是灰的
            updateRecordingState(com.kooo.evcam.recording.RecordingCoordinator.get(this).isRecording());
            applyStyle();
            applyStandDown();
            AppLog.d(TAG, "录制悬浮窗创建成功");
        } catch (Exception e) {
            AppLog.e(TAG, "添加悬浮窗失败", e);
            stopSelf();
        }
    }

    // ========== 按钮动作 ==========

    /**
     * 单击 / 长按各做什么，见 {@link FloatingAction}。
     *
     * <p>录制那一条走原来那套（按应用在不在前台分三种情况），其余三条都是
     * 一句话的事。</p>
     */
    private void perform(FloatingAction action) {
        switch (action) {
            case TOGGLE_RECORDING:
                toggleRecording();
                return;
            case TAKE_PHOTO:
                takePhoto();
                return;
            case TOGGLE_MIRROR:
                toggleMirror();
                return;
            case OPEN_APP:
            default:
                openApp();
        }
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    /**
     * 拍一张：和主界面的拍照键走同一个入口（相机层的 {@code takePhoto}）。
     *
     * <p>主界面在不在前台、相机开没开都一样：相机没开就先开（登记表的规则），出了画面再拍，
     * 提示按真的存下了几路说。以前这里发广播给主界面、或者把主界面拉起来等 3 秒碰运气 ——
     * 相机没开时拍不到，却照样提示「已保存」。</p>
     */
    private void takePhoto() {
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(this)
                .takePhoto(new com.kooo.evcam.ui.PhotoFeedback(this));
    }

    private void toggleMirror() {
        boolean on = appConfig.isRearViewEnabled();
        if (!com.kooo.evcam.overlay.OverlayCoordinator.setRearViewEnabled(this, !on)) {
            Toast.makeText(this, R.string.msg_need_overlay,
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ========== 录制控制 ==========

    private void toggleRecording() {
        AppLog.d(TAG, "toggleRecording called, recordingService=" + recordingService + ", isRecording=" + isRecording);

        // 获取应用状态
        AppState appState = getAppState();
        AppLog.d(TAG, "App state: " + appState);

        // 根据应用状态选择录制方式
        switch (appState) {
            case FOREGROUND:
                // 应用在前台，通过 MainActivity 停止录制
                AppLog.d(TAG, "App is in foreground, asking MainActivity to toggle recording");
                if (com.kooo.evcam.InAppEvents.requestToggle()) {
                    return;
                }
                // 主界面其实不在（刚销毁）：按后台的走法来（往下落到 BACKGROUND）
                // fall through
            case BACKGROUND:
                // 应用在后台，通过服务启动/停止录制，不拉起 MainActivity
                AppLog.d(TAG, "App is in background, starting/stopping recording via service...");
                startRecordingViaService();
                return;
            case NOT_RUNNING:
                // 应用未运行，需要启动 MainActivity
                AppLog.d(TAG, "App not running, starting MainActivity with auto_start_recording...");
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra("auto_start_recording", true);
                startActivity(intent);
                Toast.makeText(this, R.string.msg_starting_recording,
                        Toast.LENGTH_SHORT).show();
                return;
        }
    }

    /**
     * 通过服务启动/停止录制（用于应用在后台时）
     */
    private void startRecordingViaService() {
        AppLog.d(TAG, "Recording via service... isRecording=" + isRecording);

        // 确保录制服务已启动
        Intent serviceIntent = new Intent(this, CameraRecordingService.class);
        startService(serviceIntent);

        // 如果服务未绑定，先绑定
        if (recordingService == null) {
            bindRecordingService();
        }

        // 使用递归检查确保服务绑定完成
        tryStartOrStopRecording(0);
    }

    /**
     * 尝试启动或停止录制（带重试机制）
     * @param retryCount 重试次数
     */
    private void tryStartOrStopRecording(int retryCount) {
        final int MAX_RETRIES = 5;
        final long RETRY_DELAY_MS = 500;

        if (recordingService != null) {
            // 服务已绑定，执行录制操作
            if (isRecording) {
                AppLog.d(TAG, "Stopping recording via service");
                // 在后台线程执行停止操作，避免阻塞主线程
                new Thread(() -> {
                    try {
                        recordingService.stopRecording();
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping recording in background", e);
                        // 确保状态重置
                        mainHandler.post(() -> updateRecordingState(false));
                    }
                }, "StopRecordingBg").start();
            } else {
                AppLog.d(TAG, "Starting recording via service");
                recordingService.startRecording();
            }
        } else if (retryCount < MAX_RETRIES) {
            // 服务未绑定，重试
            AppLog.w(TAG, "Recording service not bound yet, retrying... (" + (retryCount + 1) + "/" + MAX_RETRIES + ")");
            bindRecordingService();
            mainHandler.postDelayed(() -> tryStartOrStopRecording(retryCount + 1), RETRY_DELAY_MS);
        } else {
            // 重试次数耗尽，显示错误
            AppLog.e(TAG, "Failed to bind recording service after " + MAX_RETRIES + " retries");
            Toast.makeText(this, R.string.msg_recording_service_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 应用状态枚举
     */
    private enum AppState {
        FOREGROUND,     // 应用在前台运行
        BACKGROUND,     // 应用在后台运行
        NOT_RUNNING     // 应用未运行
    }

    /**
     * 获取应用当前状态
     */
    private AppState getAppState() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(10);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    if (task.baseActivity != null && 
                        task.baseActivity.getPackageName().equals(getPackageName())) {
                        // 应用的任务存在
                        String topActivity = task.topActivity.getClassName();
                        if (topActivity.equals("com.kooo.evcam.MainActivity")) {
                            return AppState.FOREGROUND;
                        } else {
                            return AppState.BACKGROUND;
                        }
                    }
                }
            }
        }
        return AppState.NOT_RUNNING;
    }

    /**
     * 出状况了：录像被打断 / 没开起来、正在等环视好了重试，或者环视正在从连着出错里恢复。
     * 人自己停的、盘满这类不重试的不算 —— 那些不是「在挣扎」，是停了。
     */
    private boolean cameraInTrouble() {
        com.kooo.evcam.recording.RecordingCoordinator coordinator =
                com.kooo.evcam.recording.RecordingCoordinator.get(this);
        com.kooo.evcam.recording.RecordingStops.Reason reason = coordinator.lastStopReason();
        boolean retrying = coordinator.isWaiting() && reason != null
                && com.kooo.evcam.recording.RecordingStops.resumesOnSurround(reason);
        com.kooo.evcam.camera.MultiCameraManager manager =
                com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        boolean recovering = manager != null && !manager.isReleased() && manager.surroundRecovering()
                && (coordinator.isWaiting() || coordinator.isRecording());
        return retrying || recovering;
    }

    private void updateRecordingState(boolean recording) {
        isRecording = recording;

        if (recordingButton != null) {
            recordingButton.setRecording(recording);
        }

        if (recording) {
            // 从这一段真正开录的时刻算（中途才显示出来的按钮也对得上），用开机以来的时钟：
            // 车机对时会把系统时钟往回拨（2026-10-08 实车拨回过 20 秒），按系统时钟算时长会变负
            long startedAt = com.kooo.evcam.recording.RecordingCoordinator.get(this).startedAtElapsedMs();
            recordingStartTime = startedAt > 0 ? startedAt : android.os.SystemClock.elapsedRealtime();
            startTimeUpdate();
            if (timeTextView != null) {
                // 时长是个开关：有人只要一个按钮，不要旁边那串数字
                timeTextView.setVisibility(appConfig.isFloatingDurationVisible()
                        ? View.VISIBLE : View.GONE);
            }
        } else {
            stopTimeUpdate();
            if (recordingButton != null) {
                // 环归零：下次开录从十二点开始，而不是接着上次那一段
                recordingButton.setProgress(0f);
            }
            if (timeTextView != null) {
                timeTextView.setVisibility(View.GONE);
                timeTextView.setText("00:00");
            }
        }
    }

    // ========== 时间更新 ==========

    private void startTimeUpdate() {
        stopTimeUpdate();

        timeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording && timeTextView != null) {
                    long duration = Math.max(0, android.os.SystemClock.elapsedRealtime() - recordingStartTime);
                    String timeStr = formatDuration(duration);
                    timeTextView.setText(timeStr);
                    updateRingProgress(duration);
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };

        mainHandler.post(timeUpdateRunnable);
    }

    private void stopTimeUpdate() {
        if (timeUpdateRunnable != null) {
            mainHandler.removeCallbacks(timeUpdateRunnable);
            timeUpdateRunnable = null;
        }
    }

    /**
     * 环走到哪儿了。
     *
     * <p>服务这边只知道整场录制是什么时候开始的，不知道当前这一段是什么时候切的
     * —— 但分段是等长的，所以「已录时长对分段时长取余」就是本段的进度。
     * 差别只会出现在中途改过分段长度的那一次。</p>
     */
    private void updateRingProgress(long elapsedMs) {
        if (recordingButton == null) {
            return;
        }
        long segment = com.kooo.evcam.profile.RecordSpecs.segmentMs(
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(this, "front").segmentMinutes);
        if (segment <= 0) {
            return;
        }
        recordingButton.setProgress((elapsedMs % segment) / (float) segment);
    }

    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        seconds = seconds % 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    // ========== 自定义录制按钮视图（iOS 扁平化风格）==========

    private static class RecordingButtonView extends View {
        /** 相机出状况、正在重试（见 {@code cameraInTrouble}）。 */
        private boolean trouble;
        private Paint backgroundPaint;
        private Paint iconPaint;
        /** 外面那一圈：待机是正文色（近黑 / 夜间近白）的空心圈，录制中是浅红的轨道。 */
        private Paint ringPaint;
        /** 录制中沿着轨道走的那一段，和主界面录制键的分段进度环同一个意思。 */
        private Paint progressPaint;
        private final android.graphics.RectF ringRect = new android.graphics.RectF();
        private boolean isRecording = false;
        /** 本段录到哪儿了，0–1。 */
        private float progress = 0f;
        private float centerX, centerY;
        private float radius;
        private int buttonSize;
        private float cornerRadius;

        public RecordingButtonView(Context context, int buttonSize) {
            super(context);
            this.buttonSize = buttonSize;
            init();
        }

        private void init() {
            // 背景画笔 - iOS 扁平化纯色
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setStyle(Paint.Style.FILL);

            // 中间那个点 / 方块是录制状态本身，所以它是红的；底色不是
            iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setColor(ContextCompat.getColor(getContext(), R.color.recording));

            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setColor(ContextCompat.getColor(getContext(), R.color.recording));
        }

        /** 本段录到哪儿了，0–1。只在录制中画。 */
        public void setProgress(float value) {
            float clamped = value < 0f ? 0f : (value > 1f ? 1f : value);
            if (Math.abs(clamped - progress) > 0.002f) {
                progress = clamped;
                invalidate();
            }
        }

        public void setRecording(boolean recording) {
            isRecording = recording;
            invalidate();
        }

        /** 相机出状况、正在重试：外圈和中间的点改成极氪橙。 */
        public void setTrouble(boolean trouble) {
            if (this.trouble == trouble) {
                return;
            }
            this.trouble = trouble;
            invalidate();
        }

        public void setButtonSize(int size) {
            this.buttonSize = size;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            centerX = w / 2f;
            centerY = h / 2f;
            radius = Math.min(w, h) / 2f - 6;
            // iOS 风格圆角 - 圆形按钮但带轻微圆角
            cornerRadius = radius * 0.25f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // 底色一直是车机按钮那块灰，录不录都一样（用户 2026-10-09：「录的时候只让圈和点变红」）。
            // 「正在录」只靠图标说：红圈（走着的进度）加红方块
            backgroundPaint.setColor(ContextCompat.getColor(getContext(), R.color.sunken));

            // 不画阴影：车机自己的悬浮按钮（截屏键、车辆按钮）都是平的，带个影子就显得是外来的
            // （实车照片 2026-10-09）

            // 绘制圆角矩形背景（iOS 扁平化风格）
            canvas.drawRoundRect(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius,
                    cornerRadius,
                    cornerRadius,
                    backgroundPaint
            );

            // 外面那一圈，和主界面录制键的分段进度环一个意思。
            //
            // 待机时是<b>空心圈</b>（正文色）—— 合并之前那个「打开主界面」的悬浮按钮
            // 就长这样，而这个按钮默认干的也正是那件事。红色留给「正在录」。
            //
            // 图标的大小和笔画照车机自己的按钮来（实车照片 2026-10-09）：图标占按钮四成宽、细线条（第二张照片后又缩了一档）。
            // 以前圈占四分之三、线粗，放在原厂截屏键旁边像另一套界面
            float stroke = radius * 0.08f;
            float ringRadius = radius * 0.4f;
            ringPaint.setStrokeWidth(stroke);
            progressPaint.setStrokeWidth(stroke);
            ringPaint.setColor(ContextCompat.getColor(getContext(),
                    trouble ? R.color.energy : isRecording ? R.color.recording_track : R.color.text_primary));
            canvas.drawCircle(centerX, centerY, ringRadius, ringPaint);

            if (isRecording) {
                // 轨道上走过的那一段：从十二点开始顺时针
                ringRect.set(centerX - ringRadius, centerY - ringRadius,
                        centerX + ringRadius, centerY + ringRadius);
                canvas.drawArc(ringRect, -90f, 360f * progress, false, progressPaint);

                // 停止方块
                float rectSize = radius * 0.28f;
                float iconCornerRadius = rectSize * 0.2f;
                canvas.drawRoundRect(
                        centerX - rectSize / 2,
                        centerY - rectSize / 2,
                        centerX + rectSize / 2,
                        centerY + rectSize / 2,
                        iconCornerRadius,
                        iconCornerRadius,
                        iconPaint
                );
            } else {
                // 待机：中间那个点留着，和外圈一样是正文色（近黑，夜间近白），跟车机自己的按钮图标同一个颜色。
                // 以前是最浅一级的灰，放在原厂按钮旁边像是不能点。
                //
                // 试过中间不画东西，只剩一个空圈 —— 太素，一眼认不出这是个按钮。
                // 红色仍然只在录制时出现：说明「正在录」的是颜色，不是有没有点。
                // 出状况在重试：点也是极氪橙，和外圈一起说「想录，正在重试」
                iconPaint.setColor(ContextCompat.getColor(getContext(),
                        trouble ? R.color.energy : R.color.text_primary));
                canvas.drawCircle(centerX, centerY, radius * 0.17f, iconPaint);
                iconPaint.setColor(ContextCompat.getColor(getContext(), R.color.recording));
            }
        }
    }
}
