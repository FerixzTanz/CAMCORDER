package com.kooo.evcam.zeekr;

import android.app.Presentation;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
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
    private FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(1, 1);
    /** 画面过的颜色矩阵；对比度 100 时 null（原样）。 */
    private Paint tonePaint;

    public SideViewHudPanel(Context context, AppConfig appConfig, Display display) {
        super(context, appConfig);
        this.display = display;
        readTone();
    }

    private void readTone() {
        int contrast = appConfig.getHudSideContrastPercent();
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
        return attached && presentation != null && presentation.isShowing();
    }

    @Override
    protected void attachAt(int side, boolean show) {
        boolean sideChanged = side != lane;
        lane = side;
        if (isAttached()) {
            boolean visibilityChanged = show != visible;
            visible = show;
            applyWindowAlpha();
            if (sideChanged || visibilityChanged) {
                invalidate();
            }
            return;
        }
        releasePresentation();
        try {
            Presentation p = new Presentation(getContext(), display);
            FrameLayout frame = new FrameLayout(p.getContext());
            frame.setBackgroundColor(0);
            place(side);
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
            visible = show;
            applyWindowAlpha();
            p.show();
            attached = true;
            lastState = (show ? "showing" : "ready") + " on display " + display.getDisplayId() + " (" + display.getName() + ")";
            BlackBox.note("HUD 侧视窗挂上：" + lastState);
        } catch (Throwable t) {
            AppLog.e(TAG, "HUD 窗口添加失败", t);
            lastState = "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            BlackBox.note("HUD 侧视窗添加失败：" + lastState);
            releasePresentation();
        }
    }

    /** 收起来但窗口留着、相机照推（备着）。 */
    @Override
    public void conceal() {
        if (!isAttached() || !visible) {
            return;
        }
        visible = false;
        applyWindowAlpha();
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
        Presentation p = presentation;
        Window window = p != null ? p.getWindow() : null;
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = visible ? appConfig.getHudSideOpacityPercent() / 100f : 0f;
        window.setAttributes(attrs);
    }

    private void releasePresentation() {
        Presentation p = presentation;
        FrameLayout frame = root;
        presentation = null;
        root = null;
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
    }
}
