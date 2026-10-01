package com.kooo.evcam.zeekr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 打转向灯弹侧视：听车辆信号，左灯弹左侧那一路、右灯弹右侧那一路，灯灭就收；原厂画面优先。
 * 规则在 {@link SideViewDecision}。
 *
 * <h3>相机</h3>
 *
 * <p>和超级后视镜走同一条路（见 {@link RearViewMirrorService}）：窗口的 Surface 交给
 * {@code SingleCamera.setMainFloatingSurface()}，当一路附加输出。两者共用这一个槽位，
 * 所以<b>超级后视镜开着时不弹</b>。只在弹着的时候接相机、登记 {@link CameraNeeds.Holder#SIDE_POPUP}，
 * 收起就摘掉、注销 —— 平时不多推一路流。代价是每次弹出要重建一次会话，画面比窗口晚到零点几秒
 * （和后视镜从贴边放回来一样）。</p>
 *
 * <h3>车辆信号</h3>
 *
 * <p>亮屏时向 {@link Telemetry} 登记，熄屏就注销：熄屏后车机六秒就深睡，不该留着监听过去。</p>
 */
public class SideViewPopupService extends Service {

    private static final String TAG = "SideViewPopupSvc";
    private static final String TELEMETRY_USER = "side-popup";

    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;

    private static volatile SideViewPopupService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private SideViewPopupView popup;
    private SingleCamera boundCamera;
    private boolean listening;
    private int retryCount;
    private Runnable retryRunnable;

    public static void start(Context context) {
        context.startService(new Intent(context, SideViewPopupService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SideViewPopupService.class));
    }

    /** 设置页改了车速门槛：下一次判定就用新的。 */
    public static void applyConfig() {
        SideViewPopupService svc = instance;
        if (svc != null) {
            svc.evaluate();
        }
    }

    private final Telemetry.Listener readingsListener = readings -> evaluate();

    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            stopListening();
        }

        @Override
        public void onScreenOn() {
            startListening();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        appConfig = new AppConfig(this);
        instance = this;
        ScreenState.addListener(screenListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "SideViewPopupService")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!appConfig.isSidePopupEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限，侧视弹窗无法显示");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ScreenState.dark()) {
            startListening();
        }
        return CameraForegroundService.stickiness(this);
    }

    // ================================================================= 信号

    private void startListening() {
        if (listening) {
            return;
        }
        listening = true;
        Telemetry.get().acquire(this, TELEMETRY_USER);
        Telemetry.get().addListener(readingsListener);
        AppLog.i(TAG, "侧视弹窗：开始听转向灯");
        evaluate();
    }

    private void stopListening() {
        if (!listening) {
            return;
        }
        listening = false;
        Telemetry.get().removeListener(readingsListener);
        Telemetry.get().release(TELEMETRY_USER);
        hidePopup();
        AppLog.i(TAG, "侧视弹窗：停止听转向灯");
    }

    /** 主线程上：按最新的读数决定弹 / 收 / 换边。 */
    private void evaluate() {
        if (!listening) {
            return;
        }
        Readings r = Telemetry.get().readings();
        VehicleState s = Telemetry.get().latest();
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = s.turnSignal;
        in.hazard = s.hazard;
        in.gear = r.text(Signal.GEAR);
        in.stock360Shown = r.bool(Signal.STOCK_360);
        in.stockPopupShown = r.bool(Signal.STOCK_POPUP);
        in.parkAssistOn = r.bool(Signal.PARK_ASSIST);
        in.speedKmh = s.speedKmh;
        in.minSpeedKmh = appConfig.getSidePopupMinSpeed();
        in.showing = popup != null && popup.isShowing() ? popup.lane() : SideViewDecision.NONE;

        int want = SideViewDecision.decide(in);
        if (want != SideViewDecision.NONE
                && (ScreenState.dark() || RearViewMirrorService.isRunning())) {
            want = SideViewDecision.NONE;
        }
        if (want == in.showing) {
            return;
        }
        if (want == SideViewDecision.NONE) {
            AppLog.i(TAG, SideViewDecision.factoryViewActive(in) ? "原厂画面在，侧视收起" : "转向灯灭，侧视收起");
            hidePopup();
        } else {
            AppLog.i(TAG, "转向灯：弹出「" + LaneCycle.labelOf(want) + "」路");
            showPopup(want);
        }
    }

    // ================================================================= 窗口与相机

    private void showPopup(int lane) {
        if (popup != null && popup.isShowing()) {
            popup.show(lane);   // 只换边，相机不动
            return;
        }
        popup = new SideViewPopupView(this);
        popup.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                bindCamera(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                restoreBufferSize(st);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                unbindCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        popup.show(lane);
    }

    private void hidePopup() {
        cancelRetry();
        unbindCamera();
        if (popup != null) {
            popup.hide();
            popup = null;
        }
    }

    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (popup == null || !popup.isShowing() || surfaceTexture == null || ScreenState.dark()) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(this);
        }
        SingleCamera camera = manager != null ? manager.getCamera("front") : null;
        if (camera == null) {
            scheduleRetry(surfaceTexture);
            return;
        }
        Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            popup.setSourceSize(previewSize);
        }
        boundCamera = camera;
        restoreBufferSize(surfaceTexture);
        camera.setMainFloatingSurface(new Surface(surfaceTexture), surfaceTexture);
        if (camera.isCameraOpened()) {
            camera.recreateSession(false);
        } else {
            CameraForegroundService.whenReady(this, camera::openCamera);
        }
        retryCount = 0;
        CameraNeeds.current().claim(CameraNeeds.Holder.SIDE_POPUP);
    }

    /** 窗口尺寸一变 TextureView 会改缓冲区尺寸，拨回会话用的那个（同后视镜）。 */
    private void restoreBufferSize(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || boundCamera == null) {
            return;
        }
        Size buffer = boundCamera.getPreviewBufferSize();
        if (buffer != null && buffer.getWidth() > 0 && buffer.getHeight() > 0) {
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
        }
    }

    private void unbindCamera() {
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.SIDE_POPUP);
        if (boundCamera != null) {
            try {
                boundCamera.setMainFloatingSurface(null, null);
                boundCamera.recreateSession(false);
            } catch (Exception e) {
                AppLog.w(TAG, "解绑相机失败: " + e);
            }
            boundCamera = null;
        }
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "相机始终不可用，侧视放弃绑定");
            return;
        }
        retryCount++;
        cancelRetry();
        retryRunnable = () -> bindCamera(surfaceTexture);
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            handler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        ScreenState.removeListener(screenListener);
        stopListening();
        hidePopup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
