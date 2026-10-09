package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;
import com.kooo.evcam.R;

/**
 * 打转向灯弹出的侧视窗：只显示左或右那一路，不能拖、不能点。
 *
 * <p>画法和超级后视镜一样（见 {@link RearViewMirrorView} 的类说明）：子视图是普通 TextureView，
 * 本容器在 {@code dispatchDraw} 里按 {@link RearViewGeometry#combinedSourceRect} 把那一路放大重画，
 * 不新建 GL 管线；「拉直」开着时走同一套分片反投影（{@link FisheyeMesh}），
 * 只是虚拟相机先转了个角度（{@link SideViewProjection}）。侧视不镜像。</p>
 *
 * <h3>三种状态</h3>
 *
 * <ul>
 *   <li>没挂：没有窗口，不占相机。</li>
 *   <li><b>备着</b>（{@link #attachHidden}）：窗口挂着但完全透明（窗口 alpha 0），相机照常往里推帧 ——
 *       打灯时只要把 alpha 拨回 1，不用重建会话。<b>不用 INVISIBLE</b>：TextureView 不画就不取帧，
 *       共享流上一个不取帧的消费者可能把整条流（包括录像）拖住。</li>
 *   <li>显示：alpha 1，在对应那一边。</li>
 * </ul>
 *
 * <h3>盖板</h3>
 *
 * <p>TextureView 没有新帧时会一直留着最后那一帧。相机卡了、被拿走了，窗口照样「显示」着几秒甚至几分钟前的路面，
 * 看上去和实时的一样 —— 打灯并线时这比黑着更危险（2.11.0 驾驶安全审查）。所以服务按帧来的时间给一块盖板
 * （{@link #setCover}）：刚弹出还没有新帧时全黑，一直没有就写一句「没有实时画面」。</p>
 *
 * <p>位置：屏幕中线是两边的分界，左侧那一路的右边缘贴中线，右侧那一路的左边缘贴中线；
 * 默认偏宽的长方形，高度、形状（宽 ÷ 高）和上下位置在设置里调。显示时接点击（为什么见 {@link #applyVisibility}），
 * 备着时点击穿过去。</p>
 */
public class SideViewPopupView extends ViewGroup {

    private static final String TAG = "SideViewPopup";

    /** 盖板：不盖，画面照常。 */
    public static final int COVER_NONE = 0;
    /** 盖板：全黑（刚弹出，新帧还没来）。 */
    public static final int COVER_BLACK = 1;
    /** 盖板：全黑加一句「没有实时画面」。 */
    public static final int COVER_NO_PICTURE = 2;

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;
    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();
    private final FisheyeMesh mesh = new FisheyeMesh();
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;
    /** 外框：和车机原厂 360 画面的分格一样，圆角加一道细浅边（CAMCORDER 2026-10-08 界面审查）。 */
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frameRect = new RectF();
    private final float cornerRadius;
    private final Paint coverTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 新窗口还一帧都没有：先黑着，等服务按帧来的时间改。 */
    private int cover = COVER_BLACK;
    /**
     * 角上的转向箭头此刻亮不亮：跟着车上转向灯的闪（转向指示显示 0x2A091400）。
     * null = 读不到闪的节奏，一直亮着（2.11.2，用户 2026-10-09：「看得出在打哪边」）。
     */
    private Boolean indicatorLit;
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path arrowPath = new android.graphics.Path();
    /** 仪表盘转向灯的绿。 */
    private static final int ARROW_GREEN = 0xFF2BD15B;

    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean visible;
    private CompositeStreamGeometry.Plan plan;
    private int lane = LaneCycle.LEFT;
    private boolean straighten;
    private int viewFov;
    /** 这一帧窗口的宽 ÷ 高，画之前更新。 */
    private float aspect = 1f;
    private int yaw;
    private int pitch;
    private final FisheyeMesh.SourceMap turnedMap = (u, v, out) ->
            SideViewProjection.sourcePoint(u, v, viewFov, aspect, yaw, pitch, lane, out, 0);

    public SideViewPopupView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        // 原来是直角黑框，像一块贴上去的视频；车机自己的 360 画面每一格都是圆角、带一道浅色分隔
        cornerRadius = context.getResources().getDimension(R.dimen.corner_radius);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        setClipToOutline(true);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(2f * context.getResources().getDisplayMetrics().density);
        framePaint.setColor(context.getColor(R.color.surface));
        coverTextPaint.setColor(0xFFF2F2F3);
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowBackdropPaint.setStyle(Paint.Style.FILL);
        arrowBackdropPaint.setColor(0x80000000);
        coverTextPaint.setTextAlign(Paint.Align.CENTER);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
        readConfig();
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
            AppLog.d(TAG, "忽略非合成流尺寸 " + size);
            return;
        }
        plan = CompositeStreamGeometry.analyse(id, size.getWidth(), size.getHeight());
        AppLog.i(TAG, "侧视取景: " + plan + " 第 " + lane + " 路");
        invalidate();
    }

    /** 知不知道合成流的几何；不知道时画面是黑的。 */
    public boolean hasGeometry() {
        return plan != null && plan.isComposite();
    }

    /** 窗口挂着（备着或显示中）。 */
    public boolean isAttached() {
        return attached;
    }

    /** 正显示在屏幕上。 */
    public boolean isShowing() {
        return attached && visible;
    }

    public int lane() {
        return lane;
    }

    /** 盖不盖、怎么盖（{@link #COVER_NONE} 等）；服务在主线程上按帧来的时间改。 */
    public void setCover(int cover) {
        if (this.cover != cover) {
            this.cover = cover;
            invalidate();
        }
    }

    public int cover() {
        return cover;
    }

    /** 转向灯这一下亮着没有（null = 不知道，箭头常亮）；服务在主线程上跟着车辆信号改。 */
    public void setIndicatorLit(Boolean lit) {
        if (lit == null ? indicatorLit != null : !lit.equals(indicatorLit)) {
            indicatorLit = lit;
            invalidate();
        }
    }

    // ------------------------------------------------------------------ 窗口

    /** 挂上但不显示：给相机一个推帧的地方，打灯时马上能出画面。 */
    public void attachHidden(int side) {
        attachAt(side, false);
    }

    /** 显示 {@code side} 那一路；没挂就挂上，备着就拨亮，显示着就换边。 */
    public void show(int side) {
        attachAt(side, true);
    }

    /** 收起来但窗口留着、相机照推（备着）。 */
    public void conceal() {
        if (!attached || !visible) {
            return;
        }
        visible = false;
        applyVisibility(false);
        update();
    }

    /** 整个拿掉。 */
    public void detach() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            AppLog.w(TAG, "侧视窗移除失败: " + e);
        }
        attached = false;
        visible = false;
    }

    /** 设置页改了大小 / 位置 / 拉直：套用到挂着的窗口上。 */
    public void applyConfig() {
        readConfig();
        if (attached) {
            place(lane);
            update();
        }
        invalidate();
    }

    private void readConfig() {
        straighten = appConfig.isSidePopupStraighten();
        viewFov = appConfig.getSidePopupFov();
        yaw = appConfig.getSidePopupYaw();
        pitch = appConfig.getSidePopupPitch();
    }

    private void attachAt(int side, boolean show) {
        boolean sideChanged = side != lane;
        lane = side;
        if (attached) {
            boolean visibilityChanged = show != visible;
            visible = show;
            applyVisibility(show);
            if (sideChanged) {
                place(side);
            }
            if (sideChanged || visibilityChanged) {
                update();
            }
            if (sideChanged) {
                invalidate();
            }
            return;
        }
        params = new WindowManager.LayoutParams(
                1, 1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        applyVisibility(show);
        place(side);
        try {
            windowManager.addView(this, params);
            attached = true;
            visible = show;
        } catch (Exception e) {
            AppLog.e(TAG, "侧视窗添加失败", e);
        }
    }

    /**
     * 显示时不透明、接点击；备着时全透明、点击穿过去。
     *
     * <p><b>显示时不能带 FLAG_NOT_TOUCHABLE</b>：安卓 12 起，带这个标志的悬浮窗会被系统把不透明度
     * 压到 0.8（为了让点击能穿过去，b/218777508）—— 用户实车上看就是「有点透明」（2026-10-01）。
     * 代价是弹着的那一两秒里，窗口下面那块点不到。备着时 alpha 0，不受那条限制，照旧让点击穿过去，
     * 不然 D 档时屏幕上会有一块看不见又点不动的区域。</p>
     */
    private void applyVisibility(boolean show) {
        params.alpha = show ? 1f : 0f;
        if (show) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    /** 弹着时点在窗口上的手势吃掉，什么也不做：窗口不能拖、不能点。 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return true;
    }

    private void update() {
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.w(TAG, "侧视窗更新失败: " + e);
        }
    }

    /**
     * 大小和位置。高度按屏幕高度的百分比，宽度 = 高度 × 形状，最多半个屏幕宽（两边在中线相接，不能超过一半）；
     * 上下位置是剩余高度里的百分比，0 贴顶、100 贴底。
     *
     * <p>长方形（用户 2026-10-02）：正方形只能靠缩小视野、转来转去看两边，横着拉宽才是要的。</p>
     */
    private void place(int side) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int height = Math.round(Math.min(h * appConfig.getSidePopupSizePercent() / 100f, h));
        height = Math.max(AppConfig.REARVIEW_MIN_SIZE, height);
        int width = Math.round(Math.min(height * appConfig.getSidePopupShapePercent() / 100f, w / 2f));
        width = Math.max(AppConfig.REARVIEW_MIN_SIZE, width);
        int center = w / 2;
        params.width = width;
        params.height = height;
        params.x = side == LaneCycle.LEFT ? center - width : center;
        params.y = Math.round(Math.max(0, h - height) * appConfig.getSidePopupVerticalPercent() / 100f);
    }

    // ------------------------------------------------------------------ 布局与绘制

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
        if (!hasGeometry()) {
            // 还不知道几何：子视图照画（TextureView 不画就不取帧，onSurfaceTextureUpdated 也不来，
            // 服务就没机会补上尺寸 —— 窗口会一直黑），再盖成黑的，不露出四路挤在一起的整条合成流
            super.dispatchDraw(canvas);
            canvas.drawColor(0xFF000000);
            drawCover(canvas, width, height);
            drawFrame(canvas, width, height);
            return;
        }
        aspect = (float) width / height;
        int save = canvas.save();
        if (LaneCycle.isMirrored(lane)) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }
        if (straighten) {
            // 虚拟相机转过去（往后、往上）再拉直，见 SideViewProjection
            RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(
                    plan, lane, RearViewGeometry.Viewport.full());
            mesh.prepare(FisheyeProjection.MESH_DIVISIONS,
                    r.laneOffsetX * width, r.laneOffsetY * height,
                    r.laneScaleX * width, r.laneScaleY * height, turnedMap);
            mesh.draw(canvas, 0f, 0f, width, height, paintTexture);
        } else {
            // 不拉直：只能在原图里裁一块挪一挪（见 SideViewAim），角度折算成那边的百分比
            int zoomPercent = Math.round(180f / Math.min(viewFov, 180) * 100f);
            int backPercent = Math.round(yaw / SideViewProjection.MAX_YAW_DEGREES * 100f);
            int upPercent = Math.round(pitch / SideViewProjection.MAX_PITCH_DEGREES * 100f);
            RearViewGeometry.Viewport viewport = SideViewAim.viewport(lane, zoomPercent, backPercent, upPercent, aspect);
            float[] rect = RearViewGeometry.combinedSourceRect(plan, lane, viewport);
            sourceRect.set(rect[0] * width, rect[1] * height,
                    (rect[0] + rect[2]) * width, (rect[1] + rect[3]) * height);
            destRect.set(0, 0, width, height);
            drawMatrix.setRectToRect(sourceRect, destRect, Matrix.ScaleToFit.FILL);
            canvas.clipRect(destRect);
            canvas.concat(drawMatrix);
            drawChild(canvas, textureView, getDrawingTime());
        }
        canvas.restoreToCount(save);
        drawCover(canvas, width, height);
        drawFrame(canvas, width, height);
        drawIndicator(canvas, width, height);
    }

    /**
     * 打灯那一边的上角一个转向箭头：左边的窗在左上角、朝左，右边的在右上角、朝右，
     * 跟着车上的转向灯一亮一暗（读不到就常亮）。压在最上层、镜像之外，箭头方向不跟着画面翻。
     * 底下垫一块半透明黑，亮天、草地上也看得清。
     */
    private void drawIndicator(Canvas canvas, int width, int height) {
        boolean left = lane == LaneCycle.LEFT;
        if (!left && lane != LaneCycle.RIGHT) {
            return;
        }
        float size = Math.max(height * 0.16f, 24f * getResources().getDisplayMetrics().density);
        float margin = size * 0.35f;
        float cx = left ? margin + size / 2f : width - margin - size / 2f;
        float cy = margin + size / 2f;
        canvas.drawCircle(cx, cy, size * 0.62f, arrowBackdropPaint);

        // 朝右画好，朝左时左右翻一下：箭头 + 一截箭杆，和仪表盘上的转向灯一个样子
        float half = size / 2f;
        float shaftHalf = size * 0.16f;
        float headStart = size * 0.02f;
        arrowPath.reset();
        arrowPath.moveTo(-half, -shaftHalf);
        arrowPath.lineTo(headStart, -shaftHalf);
        arrowPath.lineTo(headStart, -half * 0.8f);
        arrowPath.lineTo(half, 0f);
        arrowPath.lineTo(headStart, half * 0.8f);
        arrowPath.lineTo(headStart, shaftHalf);
        arrowPath.lineTo(-half, shaftHalf);
        arrowPath.close();
        boolean lit = indicatorLit == null || indicatorLit;
        arrowPaint.setColor(lit ? ARROW_GREEN : 0x4D2BD15B);
        int save = canvas.save();
        canvas.translate(cx, cy);
        if (left) {
            canvas.scale(-1f, 1f);
        }
        canvas.scale(0.78f, 0.78f);
        canvas.drawPath(arrowPath, arrowPaint);
        canvas.restoreToCount(save);
    }

    /**
     * 盖板压在画面上、镜像之外（字不跟着翻）。子视图上面已经照画过：TextureView 不画就不取帧，
     * 新帧来不了，盖板就永远揭不掉。
     */
    private void drawCover(Canvas canvas, int width, int height) {
        if (cover == COVER_NONE) {
            return;
        }
        canvas.drawColor(0xFF000000);
        if (cover != COVER_NO_PICTURE) {
            return;
        }
        String text = getContext().getString(R.string.side_popup_no_picture);
        if (text.isEmpty()) {
            return;
        }
        coverTextPaint.setTextSize(Math.max(1f,
                Math.min(height * 0.12f, width / (text.length() * 0.6f))));
        Paint.FontMetrics fm = coverTextPaint.getFontMetrics();
        canvas.drawText(text, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, coverTextPaint);
    }

    /** 细浅边，压在画面最上层；线宽一半落在窗口外会被圆角裁掉，所以往里收半个线宽。 */
    private void drawFrame(Canvas canvas, int width, int height) {
        float inset = framePaint.getStrokeWidth() / 2f;
        frameRect.set(inset, inset, width - inset, height - inset);
        canvas.drawRoundRect(frameRect, cornerRadius - inset, cornerRadius - inset, framePaint);
    }

    private void drawTextureOnce(Canvas canvas) {
        drawChild(canvas, textureView, getDrawingTime());
    }
}
