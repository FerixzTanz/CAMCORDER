package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;

/**
 * 侧视画面投到 HUD 的那一路：窗口（{@link SideViewHudPanel}）、相机接口、盖板、收起的节奏都在这里，
 * 和主屏弹窗（{@link SideViewPopupService} 里那个）各管各的，所以可以只开一个，也可以两个同时开。
 *
 * <p>弹不弹由服务按 {@link SideViewDecision} 判，这里只管照做：HUD <b>不看车速门槛</b>（低速也出，
 * 只有主屏弹窗才等车速），原厂画面在、灯灭、熄屏这些照旧收。</p>
 *
 * <p>相机走自己的槽位（{@link SingleCamera#setHudSurface}，登记 {@link CameraNeeds.Holder#SIDE_HUD}），
 * 和主屏弹窗、超级后视镜用的那个互不相干 —— 后视镜开着时 HUD 照样出。
 * 画面新不新鲜也自己看：{@link SideViewPopupService#STALE_AFTER_MS} 内没有新帧就盖上，
 * 别把停住的那一帧当实时画面（2.11.0 驾驶安全审查）。</p>
 */
final class SideViewHudOutput {

    private static final String TAG = "SideViewHud";

    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;
    /** 环视在恢复、录像在开时，过多久再试着接相机。 */
    private static final long WAIT_FOR_CAMERA_MS = 3_000L;
    /** 显示时多久看一次帧还来不来。 */
    private static final long STALE_TICK_MS = 100L;
    /** HUD 窗口开不起来之后，这么久之内不再试（也不算「可用」，主屏弹窗顶上）。 */
    private static final long FAILED_RETRY_MS = 10_000L;
    /** 「HUD 屏在不在」多久重新看一次：信号每 200 ms 推一次，不必每次都问系统。 */
    private static final long PRESENT_CHECK_MS = 2_000L;

    private final Context context;
    private final AppConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SideViewHudPanel panel;
    private SingleCamera boundCamera;
    /** 交给相机的是哪一个 SurfaceTexture：放手时确认槽位还是自己的。 */
    private SurfaceTexture boundTexture;
    private int retryCount;
    private Runnable retryRunnable;
    private int lastLane = LaneCycle.LEFT;
    /** 上一次判定时该不该备着（窗口挂着、透明、相机照推）。 */
    private boolean lastReady;
    private long shownAtMs;
    private boolean firstFrameSeen;
    /** 最近一帧新画面的时刻（单调时钟）；这个窗口还一帧都没有是 0。 */
    private long lastFrameMs;
    private long failedUntilMs;
    private boolean presentKnown;
    private boolean present;
    private long presentCheckedMs;
    private boolean closePending;
    private boolean readyExpiryArmed;
    /** 窗口挂上后又很快被系统收掉的次数：连着几次就当开窗失败，别每 200 ms 重建一次。 */
    private int quickDismissals;
    private long attachedAtMs;
    private static final long QUICK_DISMISS_MS = 3_000L;
    private static final int MAX_QUICK_DISMISSALS = 3;

    SideViewHudOutput(Context context, AppConfig config) {
        this.context = context;
        this.config = config;
    }

    // ================================================================= 状态

    /** 找得到 HUD 屏、而且上一次开窗没有刚失败。 */
    boolean available() {
        long now = SystemClock.uptimeMillis();
        if (now < failedUntilMs) {
            return false;
        }
        if (!presentKnown || now - presentCheckedMs > PRESENT_CHECK_MS) {
            present = SideViewHudPanel.find(context) != null;
            presentKnown = true;
            presentCheckedMs = now;
        }
        return present;
    }

    boolean isShowing() {
        return panel != null && panel.isShowing();
    }

    /** 此刻显示着哪一路；没显示是 {@link SideViewDecision#NONE}。 */
    int showingLane() {
        return isShowing() ? panel.lane() : SideViewDecision.NONE;
    }

    /** 窗口角上的转向箭头跟着车上的灯一亮一暗（{@link SideViewDecision#indicatorLit}）。 */
    void followIndicator(Integer display) {
        if (panel != null) {
            panel.setIndicatorLit(SideViewDecision.indicatorLit(display, panel.lane()));
        }
    }

    /** 设置页改了东西：窗口套用新的大小位置拉直。 */
    void applyConfig() {
        if (panel != null) {
            panel.applyConfig();
        }
    }

    // ================================================================= 判定

    /**
     * 按这一刻的判定做事。
     *
     * @param want         该显示的那一路（{@link SideViewDecision#NONE} 是不该显示）
     * @param factory      原厂画面在屏幕上：立刻收
     * @param ready        该不该备着（D 档、「即时弹出」开着）
     * @param closeDelayMs 灯灭后再留多久
     */
    boolean update(int want, boolean factory, boolean ready, int closeDelayMs) {
        apply(want, factory, ready, closeDelayMs);
        // 开窗是在里面试的；失败了 available() 这一刻已经是 false，服务按它决定主屏要不要顶上
        return available();
    }

    private void apply(int want, boolean factory, boolean ready, int closeDelayMs) {
        lastReady = ready;
        if (want != SideViewDecision.NONE) {
            cancelDelayedClose();
            disarmReadyExpiry();
            if (showingLane() != want) {
                AppLog.i(TAG, "HUD：弹出「" + LaneCycle.labelOf(want) + "」路");
                show(want);
            }
            return;
        }
        if (isShowing()) {
            if (factory || closeDelayMs <= 0) {
                AppLog.i(TAG, "HUD 收起：" + (factory ? "原厂画面在" : "转向灯灭"));
                closeNow();
            } else if (!closePending) {
                closePending = true;
                handler.postDelayed(delayedClose, closeDelayMs);
            }
            return;
        }
        // 没在显示：管「备着」
        if (ready) {
            disarmReadyExpiry();
            if (panel == null || !panel.isAttached()) {
                attach(lastLane, false);
            }
        } else if (panel != null && panel.isAttached() && !closePending) {
            armReadyExpiry();
        }
    }

    /** 灯灭后再留几秒：到点收起。 */
    private final Runnable delayedClose = () -> {
        AppLog.i(TAG, "HUD 收起：灯灭后多留的时间到了");
        closeNow();
    };

    /** 离开 D 档宽限期满：拿掉备着的窗口。 */
    private final Runnable readyExpired = () -> {
        readyExpiryArmed = false;
        if (!lastReady && panel != null && !panel.isShowing()) {
            AppLog.i(TAG, "HUD：离开 D 档，不再备着");
            detach();
        }
    };

    /** 显示着的时候每 {@link #STALE_TICK_MS} 看一次：帧停了就盖上。 */
    private final Runnable staleTick = new Runnable() {
        @Override
        public void run() {
            if (panel == null || !panel.isShowing()) {
                return;
            }
            updateCover();
            handler.postDelayed(this, STALE_TICK_MS);
        }
    };

    /** 同主屏弹窗：「不再备着」的倒计时只排一次，别每推一次信号就重排。 */
    private void armReadyExpiry() {
        if (readyExpiryArmed) {
            return;
        }
        handler.removeCallbacks(readyExpired);
        handler.postDelayed(readyExpired, SideViewPopupService.READY_GRACE_MS);
        readyExpiryArmed = true;
    }

    private void disarmReadyExpiry() {
        handler.removeCallbacks(readyExpired);
        readyExpiryArmed = false;
    }

    private void cancelDelayedClose() {
        if (closePending) {
            closePending = false;
            handler.removeCallbacks(delayedClose);
        }
    }

    // ================================================================= 窗口

    private void show(int lane) {
        lastLane = lane;
        shownAtMs = SystemClock.uptimeMillis();
        firstFrameSeen = false;
        if (panel != null) {
            // 拨亮之前先定好盖不盖：备着时帧要是早就停了，拨亮的第一帧就不能是那张旧的
            panel.setCover(coverNow());
        }
        attach(lane, true);
        updateCover();
        handler.removeCallbacks(staleTick);
        handler.postDelayed(staleTick, STALE_TICK_MS);
    }

    private void attach(int lane, boolean show) {
        if (panel != null && !panel.isAttached()) {
            // 上一个窗口不是我们收的，是系统收的（屏没了、配置不对）：收干净，数一下
            if (attachedAtMs != 0 && SystemClock.uptimeMillis() - attachedAtMs < QUICK_DISMISS_MS) {
                quickDismissals++;
            } else {
                quickDismissals = 0;
            }
            detach();
            if (quickDismissals >= MAX_QUICK_DISMISSALS) {
                quickDismissals = 0;
                failedUntilMs = SystemClock.uptimeMillis() + FAILED_RETRY_MS;
                AppLog.w(TAG, "HUD 窗口一挂上就被系统收掉，连着 " + MAX_QUICK_DISMISSALS + " 次，这一阵不再试");
                BlackBox.note("HUD 侧视窗连着被系统收掉，暂停 " + (FAILED_RETRY_MS / 1000) + " 秒");
                return;
            }
        }
        if (panel != null && panel.isAttached()) {
            if (show) {
                panel.show(lane);
                rebindIfLost();
            } else {
                panel.attachHidden(lane);
            }
            return;
        }
        if (SystemClock.uptimeMillis() < failedUntilMs) {
            return;
        }
        Display hud = SideViewHudPanel.find(context);
        if (hud == null) {
            present = false;
            presentKnown = true;
            presentCheckedMs = SystemClock.uptimeMillis();
            return;
        }
        SideViewHudPanel created = new SideViewHudPanel(context, config, hud);
        lastFrameMs = 0;   // 新窗口：上一个窗口的帧不算
        created.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
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
                // 只放自己这一块：旧窗口的画面晚一步销毁时，别把新窗口接上的相机摘掉
                if (st == boundTexture || boundTexture == null) {
                    unbindCamera();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                onFrame();
            }
        });
        panel = created;
        attachedAtMs = SystemClock.uptimeMillis();
        if (show) {
            created.show(lane);
        } else {
            created.attachHidden(lane);
        }
        if (!created.isAttached()) {
            panel = null;
            failedUntilMs = SystemClock.uptimeMillis() + FAILED_RETRY_MS;
            AppLog.w(TAG, "HUD 窗口开不起来，这一阵不再试，主屏弹窗顶上");
        }
    }

    /** 收起：该备着就只拨暗（相机照推），不该就整个拿掉。 */
    private void closeNow() {
        cancelDelayedClose();
        if (panel == null || !panel.isShowing()) {
            return;
        }
        noteIfNoFrame();
        if (lastReady) {
            panel.conceal();
        } else {
            detach();
        }
    }

    /** 整个拿掉：放开相机、窗口关掉。 */
    void detach() {
        if (panel == null && boundCamera == null && !closePending && !readyExpiryArmed) {
            return;   // 没有什么可放的：别白白去碰前台服务的放手计时
        }
        cancelDelayedClose();
        disarmReadyExpiry();
        cancelRetry();
        handler.removeCallbacks(staleTick);
        if (panel != null && panel.isShowing()) {
            noteIfNoFrame();
        }
        unbindCamera();
        if (panel != null) {
            panel.detach();
            panel = null;
        }
    }

    // ================================================================= 盖板

    /**
     * 按帧来的时间该怎么盖：最近 {@link SideViewPopupService#STALE_AFTER_MS} 内来过新帧就不盖；
     * 没有的话，刚弹出那一小会儿只是黑着（冷启动等第一帧是正常的），再久就写「没有实时画面」。
     */
    private int coverNow() {
        long now = SystemClock.uptimeMillis();
        if (lastFrameMs > 0 && now - lastFrameMs <= SideViewPopupService.STALE_AFTER_MS) {
            return SideViewPopupView.COVER_NONE;
        }
        return now - shownAtMs < SideViewPopupService.STALE_AFTER_MS
                ? SideViewPopupView.COVER_BLACK : SideViewPopupView.COVER_NO_PICTURE;
    }

    private void updateCover() {
        SideViewHudPanel view = panel;
        if (view == null || !view.isShowing()) {
            return;
        }
        int cover = coverNow();
        if (cover == SideViewPopupView.COVER_NO_PICTURE && view.cover() != cover) {
            AppLog.w(TAG, "HUD：" + (lastFrameMs == 0 ? "一帧都还没来"
                    : (SystemClock.uptimeMillis() - lastFrameMs) + "ms 没有新帧") + "，盖上「没有实时画面」");
        }
        view.setCover(cover);
    }

    /** 显示过、到收起时一帧都没来：记下来，诊断报告里看得到。 */
    private void noteIfNoFrame() {
        if (firstFrameSeen) {
            return;
        }
        SingleCamera camera = boundCamera;
        BlackBox.note("HUD 侧视收起：显示了 " + (SystemClock.uptimeMillis() - shownAtMs) + "ms 一帧都没来；"
                + (camera == null ? "没接上相机" : "相机" + (camera.isCameraOpened() ? "开着" : "没开")
                + "，最近错误 " + camera.lastErrorName()));
    }

    /**
     * 每来一帧响一次。挂窗口时相机要是还没开过，那一刻它的预览尺寸是 null（开相机时才选），
     * 窗口就不知道该裁哪一块；帧来了说明相机已经开好，这时补上。
     */
    private void onFrame() {
        SideViewHudPanel view = panel;
        SingleCamera camera = boundCamera;
        if (view == null) {
            return;
        }
        lastFrameMs = SystemClock.uptimeMillis();
        if (view.isShowing()) {
            view.setCover(SideViewPopupView.COVER_NONE);
        }
        if (!view.hasGeometry() && camera != null) {
            view.setSourceSize(camera.getPreviewSize());
        }
        if (view.isShowing() && !firstFrameSeen) {
            firstFrameSeen = true;
            BlackBox.note("HUD 侧视出画面：打灯后 " + (SystemClock.uptimeMillis() - shownAtMs) + "ms，几何"
                    + (view.hasGeometry() ? "已知" : "未知"));
        }
    }

    // ================================================================= 相机

    /**
     * 备着的窗口再弹出来时，看一眼它还接着相机没有：相机层可能把画面出口摘掉了（录像时会话配置失败、
     * 画面出口被丢弃后恢复），或者 bindCamera 重试到头放弃了 —— 那样之后每次打灯都是黑的。
     */
    private void rebindIfLost() {
        if (panel == null || panel.getTextureView() == null) {
            return;
        }
        SurfaceTexture st = panel.getTextureView().getSurfaceTexture();
        if (st == null) {
            return;   // 还没画出来：onSurfaceTextureAvailable 会去接
        }
        SingleCamera camera = boundCamera;
        boolean lost = camera == null || boundTexture != st
                || camera.getHudSurfaceTexture() != boundTexture;
        if (lost) {
            AppLog.i(TAG, "HUD：窗口的相机画面丢了，重新接");
            cancelRetry();
            retryCount = 0;
            bindCamera(st);
        }
    }

    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (panel == null || !panel.isAttached() || surfaceTexture == null || ScreenState.dark()) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(context);
        }
        SingleCamera camera = manager != null ? manager.getCamera("front") : null;
        if (camera == null) {
            scheduleRetry(surfaceTexture);
            return;
        }
        // 后台的相机只有前台服务在才给；不录像时没人替 HUD 拉它，要在「等一会儿再接」之前拉（同主屏弹窗）
        CameraForegroundService.ensureRunning(context, "side-hud");
        // 环视正在从连着出错里恢复、或者录像正在开：别在这时候再开相机、再重建会话。过一会儿再接
        if (camera.isRecovering() || com.kooo.evcam.recording.RecordingCoordinator.get(context).isAboutToStart()) {
            cancelRetry();
            retryRunnable = () -> bindCamera(surfaceTexture);
            handler.postDelayed(retryRunnable, WAIT_FOR_CAMERA_MS);
            return;
        }
        Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            panel.setSourceSize(previewSize);
        }
        boundCamera = camera;
        boundTexture = surfaceTexture;
        restoreBufferSize(surfaceTexture);
        camera.setHudSurface(new Surface(surfaceTexture), surfaceTexture);
        boolean wasOpen = camera.isCameraOpened();
        if (wasOpen) {
            camera.recreateSession(false);
        } else {
            CameraForegroundService.whenReady(context, "side-hud-open", camera::openCamera);
        }
        retryCount = 0;
        CameraNeeds.current().claim(CameraNeeds.Holder.SIDE_HUD);
        BlackBox.note("HUD 侧视接相机：「" + LaneCycle.labelOf(panel.lane()) + "」路，"
                + (panel.isShowing() ? "显示中" : "备着") + "，相机" + (wasOpen ? "已开" : "未开，现在开")
                + "，预览尺寸 " + previewSize + "，几何" + (panel.hasGeometry() ? "已知" : "未知（等第一帧补）"));
    }

    /** 窗口尺寸一变 TextureView 会改缓冲区尺寸，拨回会话用的那个（同主屏弹窗）。 */
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
        CameraForegroundService.cancelWhenReady("side-hud-open");
        CameraNeeds.current().release(CameraNeeds.Holder.SIDE_HUD);
        // 录像不用、保活没开、主屏弹窗也不用，就让前台服务回去；有人在用就留着
        CameraForegroundService.releaseWhenIdle(context);
        if (boundCamera != null) {
            // 槽位要是已经被别的画面接走了，就别动它
            if (boundCamera.getHudSurfaceTexture() == boundTexture) {
                try {
                    boundCamera.setHudSurface(null, null);
                    boundCamera.recreateSession(false);
                } catch (Exception e) {
                    AppLog.w(TAG, "HUD 解绑相机失败: " + e);
                }
            }
            boundCamera = null;
            boundTexture = null;
        }
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "相机始终不可用，HUD 放弃绑定");
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
}
