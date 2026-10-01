package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;

/**
 * 打转向灯弹出的侧视窗：只显示左或右那一路，不能拖、不能点，灯灭就收。
 *
 * <p>画法和超级后视镜一样（见 {@link RearViewMirrorView} 的类说明）：子视图是普通 TextureView，
 * 本容器在 {@code dispatchDraw} 里按 {@link RearViewGeometry#combinedSourceRect} 把那一路放大重画，
 * 不新建 GL 管线。侧视不镜像（{@link LaneCycle#isMirrored}）。</p>
 *
 * <p>窗口是正方形（每一路本来就是正方形，整幅都看得到），左灯靠屏幕左边、右灯靠右边。
 * {@code FLAG_NOT_TOUCHABLE}：点击穿过去，不挡底下的导航。</p>
 */
public class SideViewPopupView extends ViewGroup {

    private static final String TAG = "SideViewPopup";

    /** 边长：屏幕高度的这么多，但不超过屏幕宽度的这么多。 */
    private static final float SIZE_OF_HEIGHT = 0.55f;
    private static final float MAX_OF_WIDTH = 0.4f;
    private static final int MARGIN_PX = 40;

    private final WindowManager windowManager;
    private final AutoFitTextureView textureView;
    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();

    private WindowManager.LayoutParams params;
    private boolean attached;
    private CompositeStreamGeometry.Plan plan;
    private int lane = LaneCycle.LEFT;

    public SideViewPopupView(Context context) {
        super(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
    }

    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    /** 合成流的真实尺寸；不像合成流的忽略（同 {@link RearViewMirrorView#setSourceSize}）。 */
    public void setSourceSize(Size size) {
        if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return;
        }
        String id = StreamLayoutTable.compositeCameraId();
        if (!CompositeStreamGeometry.looksLikeComposite(id, size.getWidth(), size.getHeight())) {
            return;
        }
        plan = CompositeStreamGeometry.analyse(id, size.getWidth(), size.getHeight());
        invalidate();
    }

    public boolean isShowing() {
        return attached;
    }

    public int lane() {
        return lane;
    }

    /** 弹出来，显示 {@code side} 那一路；已经弹着就只换边。 */
    public void show(int side) {
        lane = side;
        int size = sideLength();
        if (attached) {
            params.x = xFor(side, size);
            try {
                windowManager.updateViewLayout(this, params);
            } catch (Exception e) {
                AppLog.w(TAG, "侧视窗换边失败: " + e);
            }
            invalidate();
            return;
        }
        params = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = xFor(side, size);
        params.y = MARGIN_PX;
        try {
            windowManager.addView(this, params);
            attached = true;
        } catch (Exception e) {
            AppLog.e(TAG, "侧视窗添加失败", e);
        }
    }

    public void hide() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            AppLog.w(TAG, "侧视窗移除失败: " + e);
        }
        attached = false;
    }

    private int sideLength() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return Math.round(Math.min(h * SIZE_OF_HEIGHT, w * MAX_OF_WIDTH));
    }

    private int xFor(int side, int size) {
        int w = getResources().getDisplayMetrics().widthPixels;
        return side == LaneCycle.LEFT ? MARGIN_PX : w - size - MARGIN_PX;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        textureView.layout(0, 0, getWidth(), getHeight());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        measureChildren(widthSpec, heightSpec);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (plan == null || !plan.isComposite()) {
            // 还不知道几何：不画整条合成流（那是四路挤在一起的怪图），黑着等第一帧
            return;
        }
        RearViewGeometry.Viewport viewport = RearViewGeometry.Viewport.forWindow(
                width, height, RearViewGeometry.DEFAULT_PAN);
        float[] rect = RearViewGeometry.combinedSourceRect(plan, lane, viewport);
        sourceRect.set(rect[0] * width, rect[1] * height,
                (rect[0] + rect[2]) * width, (rect[1] + rect[3]) * height);
        destRect.set(0, 0, width, height);
        drawMatrix.setRectToRect(sourceRect, destRect, Matrix.ScaleToFit.FILL);

        int save = canvas.save();
        if (LaneCycle.isMirrored(lane)) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }
        canvas.clipRect(destRect);
        canvas.concat(drawMatrix);
        drawChild(canvas, textureView, getDrawingTime());
        canvas.restoreToCount(save);
    }
}
