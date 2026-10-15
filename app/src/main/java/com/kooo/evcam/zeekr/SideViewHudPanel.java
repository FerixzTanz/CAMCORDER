package com.kooo.evcam.zeekr;

import android.app.Presentation;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 打转向灯弹的侧视画面，投到 HUD 上而不是主屏。
 *
 * <p>画面、盖板、转向箭头都是 {@link SideViewPopupView} 那一套，这里只换窗口：
 * 在 HUD 那块屏上开一个透明的 {@link Presentation}（不要悬浮窗权限，但那块屏得是公开的「可投影」屏，诊断报告里 flags 带 0x8），
 * 画面按 {@link HudPanelPlacement} 放在里面。HUD 是投影，黑色就是不亮，所以黑底、盖板在 HUD 上都是「什么也没有」。
 * 画面本身过一条对比度曲线（{@link HudPictureTone}，设置里调），四边渐隐到黑（{@link HudEdgeFade}，设置里调），不画浅色外框：投影上中灰一片发白。</p>
 *
 * <p>备着（透明、相机照推）和显示的区别和主屏弹窗一样：备着时窗口 alpha 0，不用 INVISIBLE，
 * 理由见 {@link SideViewPopupView} 的类说明。</p>
 *
 * <p>系统在 HUD 那块屏消失时会自己把 {@link Presentation} 收掉，所以 {@link #isAttached()} 每次都问它一句，
 * 服务发现不挂着了就重新建（找不到 HUD 时退回主屏弹窗）。</p>
 */
public class SideViewHudPanel extends SideViewPopupView {

    private static final String TAG = "SideViewHud";

    /** 最近一次的情况，诊断报告里读。状态记号，不是给人读的句子（同 CameraNeeds.describe）。 */
    private static volatile String lastState = "never";

    private final Display display;
    private Presentation presentation;
    private FrameLayout root;
    /** 悬浮窗层级那条路：窗口上下文的 WindowManager 和挂上去的根布局（{@link #presentation} 为 null 时用）。 */
    private WindowManager overlayWm;
    private OverlayRoot overlayRoot;
    private WindowManager.LayoutParams overlayParams;
    /** 挂窗口时设置里的层级值：设置拨了就得重建，窗口类型加上去之后改不了。 */
    private boolean overlayUsed;
    private FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(1, 1);
    /** 画面过的颜色矩阵；对比度 100 时 null（原样）。 */
    private Paint tonePaint;
    private boolean night;

    public SideViewHudPanel(Context context, AppConfig appConfig, Display display) {
        super(context, appConfig);
        this.display = display;
        readTone();
    }

    /** 按这一刻是白天还是夜间读对比度曲线：夜里画面本来就暗，用夜间那档。 */
    private void readTone() {
        int contrast = appConfig.getHudSideContrastPercent(night);
        if (HudPictureTone.isIdentity(contrast)) {
            tonePaint = null;
            return;
        }
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(HudPictureTone.matrix(contrast))));
        tonePaint = paint;
    }

    @Override
    protected Paint pictureTone() {
        return tonePaint;
    }

    /** 车上是不是夜间模式：夜间换夜间对比度，再按设置的比例调暗（{@link HudNightDimming}）。服务每次读数变化时给。 */
    public void setNight(boolean night) {
        if (this.night != night) {
            this.night = night;
            readTone();
            applyWindowAlpha();
            invalidate();
        }
    }

    @Override
    protected int edgeFadePercent() {
        return appConfig.getHudSideFadePercent();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public void applyConfig() {
        readTone();
        applyWindowAlpha();
        super.applyConfig();
    }

    /** 诊断报告里的一行：HUD 窗口最近怎么样。 */
    public static String lastState() {
        return lastState;
    }

    /** HUD 那块屏（规则见 {@link HudDisplayLocator}）；找不到是 null。 */
    public static Display find(Context context) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        Display[] displays = dm.getDisplays();
        List<HudDisplayLocator.Candidate> candidates = new ArrayList<>();
        for (Display d : displays) {
            Point size = new Point();
            try {
                d.getRealSize(size);
            } catch (Exception ignored) {
                // 拿不到尺寸就当 0×0，不会被认成 HUD
            }
            // 关着的屏（车里把 HUD 关了，系统还留着这块逻辑屏）不算：往上画了等于哪里都没有
            if (d.getState() != Display.STATE_ON) {
                continue;
            }
            candidates.add(new HudDisplayLocator.Candidate(d.getDisplayId(), size.x, size.y));
        }
        int id = HudDisplayLocator.find(candidates);
        if (id == HudDisplayLocator.NONE) {
            return null;
        }
        for (Display d : displays) {
            if (d.getDisplayId() == id) {
                return d;
            }
        }
        return null;
    }

    @Override
    protected boolean readMirrored() {
        return appConfig.isHudSideMirrored();
    }

    @Override
    public boolean isAttached() {
        if (!attached) {
            return false;
        }
        if (presentation != null) {
            return presentation.isShowing();
        }
        // 悬浮窗那条路：系统把窗口收掉（屏没了）时根布局会被 detach，它自己记下来
        return overlayRoot != null && !overlayRoot.goneBySystem;
    }

    @Override
    protected void attachAt(int side, boolean show) {
        boolean sideChanged = side != lane;
        lane = side;
        if (isAttached() && overlayUsed != appConfig.isHudSideOverlay()) {
            // 层级设置拨过了：窗口类型只能在添加前定，收掉重建
            AppLog.i(TAG, "HUD 窗口层级设置变了，重建窗口");
            releasePresentation();
        }
        if (isAttached()) {
            boolean wasShown = windowShown();
            boolean visibilityChanged = show != visible;
            visible = show;
            if (sideChanged) {
                // 画面在打灯那一边（2.12.1）：右灯直接打成左灯，窗口要跟着挪过去，
                // 不然相机换成了左路、窗口还停在右边
                place(side);
            }
            if (show) {
                // 先挪好、画面先淡到近乎透明，再露出窗口：缓冲里留的上一边那张看不见（SideViewPopupView.IDLE_ALPHA）
                reveal(wasShown && !sideChanged);
            } else if (wasShown) {
                hideWithFade();
            } else {
                applyWindowAlpha();
            }
            if (sideChanged || visibilityChanged) {
                invalidate();
            }
            return;
        }
        releasePresentation();
        setAlpha(IDLE_ALPHA);
        boolean overlay = appConfig.isHudSideOverlay();
        // Presentation 的窗口类型改不了：Android 12 上 setType(2038) 被拒（Window type mismatch，
        // 2026-10-15 实车）。悬浮窗层级要走 createWindowContext(屏, 2038) 拿到的 WindowManager 直接挂。
        boolean useOverlay = overlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        try {
            place(side);
            if (useOverlay) {
                attachOverlay();
            } else {
                attachPresentation();
            }
            overlayUsed = overlay;
            visible = show;
            applyWindowAlpha();
            attached = true;
            if (show) {
                reveal(false);
            }
            lastState = (show ? "showing" : "ready") + " on display " + display.getDisplayId() + " (" + display.getName() + ")"
                    + (useOverlay ? " overlay" : overlay ? " presentation (overlay needs Android 11+)" : " presentation");
            BlackBox.note("HUD 侧视窗挂上：" + lastState);
        } catch (Throwable t) {
            AppLog.e(TAG, "HUD 窗口添加失败", t);
            lastState = "failed" + (useOverlay ? " (overlay)" : "") + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
            BlackBox.note("HUD 侧视窗添加失败：" + lastState);
            releasePresentation();
        }
    }

    /** 老路：透明 Presentation，普通应用窗口层级（原厂 HUD 画面在它之上）。 */
    private void attachPresentation() {
        Presentation p = new Presentation(getContext(), display);
        FrameLayout frame = new FrameLayout(p.getContext());
        frame.setBackgroundColor(0);
        frame.addView(this, panelParams);
        p.setContentView(frame);
        Window window = p.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setBackgroundDrawable(new ColorDrawable(0));
        }
        presentation = p;
        root = frame;
        p.show();
    }

    /**
     * 悬浮窗层级（TYPE_APPLICATION_OVERLAY）：在所有应用窗口（包括原厂 HUD 应用）之上。
     * 要悬浮窗权限，侧视服务起之前已查过。窗口上下文得带着这块屏和这个类型建，不然系统拒收。
     */
    private void attachOverlay() {
        Context windowContext = getContext().createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        WindowManager wm = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        OverlayRoot frame = new OverlayRoot(windowContext);
        frame.setBackgroundColor(0);
        frame.addView(this, panelParams);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.alpha = 0f;
        wm.addView(frame, lp);
        overlayWm = wm;
        overlayRoot = frame;
        overlayParams = lp;
        root = frame;
    }

    /** 悬浮窗那条路的根布局：系统收窗口时它会被 detach，记一笔给 {@link #isAttached()} 看。 */
    private static final class OverlayRoot extends FrameLayout {
        private boolean everAttached;
        boolean goneBySystem;

        OverlayRoot(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            everAttached = true;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (everAttached) {
                goneBySystem = true;
            }
        }
    }

    /** 收起来但窗口留着、相机照推（备着）。 */
    @Override
    public void conceal() {
        if (!isAttached() || !visible) {
            return;
        }
        visible = false;
        hideWithFade();
    }

    /** 整个拿掉。 */
    @Override
    public void detach() {
        if (presentation != null) {
            lastState = "dismissed";
        }
        releasePresentation();
    }

    /** 大小 / 位置：窗口大小跟着 HUD 的真实尺寸算；左灯靠左、右灯靠右（{@link HudPanelPlacement}）。 */
    @Override
    protected void place(int side) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        HudPanelPlacement.Rect rect = HudPanelPlacement.compute(metrics.widthPixels, metrics.heightPixels,
                appConfig.getHudSideSizePercent(), appConfig.getHudSideShapePercent(),
                appConfig.getHudSideLeftPercent(), appConfig.getHudSideVerticalPercent(),
                side == LaneCycle.RIGHT);
        panelParams = new FrameLayout.LayoutParams(rect.width, rect.height, Gravity.TOP | Gravity.START);
        panelParams.leftMargin = rect.left;
        panelParams.topMargin = rect.top;
        if (root != null && getParent() == root) {
            setLayoutParams(panelParams);
        }
    }

    @Override
    protected void update() {
        applyWindowAlpha();
    }

    /** 显示时按设置里的不透明度，备着时全透明。 */
    private void applyWindowAlpha() {
        float alpha = windowShown() ? HudNightDimming.opacity(
                appConfig.getHudSideOpacityPercent(), appConfig.getHudSideNightPercent(), night) : 0f;
        Presentation p = presentation;
        if (p != null) {
            Window window = p.getWindow();
            if (window == null) {
                return;
            }
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.alpha = alpha;
            window.setAttributes(attrs);
            return;
        }
        WindowManager wm = overlayWm;
        OverlayRoot frame = overlayRoot;
        WindowManager.LayoutParams lp = overlayParams;
        if (wm == null || frame == null || lp == null || lp.alpha == alpha) {
            return;
        }
        lp.alpha = alpha;
        try {
            wm.updateViewLayout(frame, lp);
        } catch (Exception e) {
            AppLog.w(TAG, "HUD 悬浮窗改不透明度失败: " + e);
        }
    }

    @Override
    protected void windowUp() {
        applyWindowAlpha();
    }

    @Override
    protected void windowDown() {
        applyWindowAlpha();
    }

    private void releasePresentation() {
        abortFade();
        Presentation p = presentation;
        FrameLayout frame = root;
        WindowManager wm = overlayWm;
        OverlayRoot overlayFrame = overlayRoot;
        presentation = null;
        root = null;
        overlayWm = null;
        overlayRoot = null;
        overlayParams = null;
        attached = false;
        visible = false;
        if (frame != null) {
            try {
                frame.removeView(this);
            } catch (Exception ignored) {
                // 已经不在里面了
            }
        }
        if (p != null) {
            try {
                p.dismiss();
            } catch (Exception e) {
                AppLog.w(TAG, "HUD 窗口收起失败: " + e);
            }
        }
        if (wm != null && overlayFrame != null) {
            try {
                wm.removeViewImmediate(overlayFrame);
            } catch (Exception e) {
                AppLog.w(TAG, "HUD 悬浮窗收起失败: " + e);
            }
        }
    }
}
