package com.kooo.evcam.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.kooo.evcam.R;

import java.util.Locale;

/**
 * 快门 / 录制键：中间一个会变形的圆盘（圆 ⇄ 圆角方块）套一圈分段进度环。
 * 2.13 起它是画面上的一个大圆（layout_camera_controls），没有字；原来动作栏上那块
 * 带两行字的卡片的 id（record_title / record_detail）仍然认，有就填，没有就算了。
 * 录制中另有一行小字说现在熄屏的话这段录像会怎样（{@link ScreenOffHintView}，
 * 可以不在同一棵树里，由 {@link #setHintView} 交进来，这里只管显隐）。
 *
 * <h3>两种模式</h3>
 *
 * <ul>
 *   <li><b>录像</b>：圆盘是红的 —— 红标的是「录」这件事。待机是圆，录制中收成方块，
 *       外圈按本段已录的比例走，走满一圈换下一段；换段的那一下圆盘淡一下再回来。
 *       没插 U 盘时圆盘是灰的。</li>
 *   <li><b>拍照</b>（{@link #setPhotoMode}）：圆盘是白的，和车机自带相机的快门一样；
 *       环不画。录制状态照样记着，切回录像模式时按它画。</li>
 * </ul>
 *
 * <h3>动效只用属性动画</h3>
 *
 * <p>圆 ⇄ 方是同一个 {@link GradientDrawable} 的圆角半径和视图缩放一起插值 ——
 * 缩放不触发重新布局，圆角半径只重画这一块。都是平台自带的
 * {@link ValueAnimator}，跟随系统的「动画时长缩放」设置，关了动画就直接跳到终态。
 * 这些都是状态本身，不算装饰，录制时也照常动（见 {@link MotionPolicy}）。</p>
 */
public final class RecordButtonUi {

    public enum State { IDLE, UNAVAILABLE, PREPARING, RECORDING }

    private static final long MORPH_MS = 220;
    private static final long PULSE_MS = 600;
    private static final long FLASH_MS = 300;

    private final View root;
    private final Context context;
    private final MaterialCardView card;
    private final CircularProgressIndicator ring;
    private final View dot;
    private final TextView title;
    private final TextView detail;
    private View hint;
    private final GradientDrawable dotShape;
    private final float density;
    /** 圆盘未缩放时的半径（dp）：按布局里给的宽高算，26dp 的小点和 56dp 的快门都对 */
    private final float dotRadiusDp;

    private State state;
    private boolean photoMode;
    /** 0 = 圆，1 = 方。 */
    private float morph;
    private int segmentMinutes = 1;

    private ValueAnimator morphAnimator;
    private ValueAnimator pulseAnimator;
    private ValueAnimator backgroundAnimator;
    private ValueAnimator flashAnimator;

    public RecordButtonUi(View root) {
        this.root = root;
        this.context = root.getContext();
        this.density = context.getResources().getDisplayMetrics().density;
        this.card = root instanceof MaterialCardView ? (MaterialCardView) root : null;
        this.ring = root.findViewById(R.id.record_ring);
        this.dot = root.findViewById(R.id.record_dot);
        this.title = root.findViewById(R.id.record_title);
        this.detail = root.findViewById(R.id.record_detail);
        this.hint = root.findViewById(R.id.record_hint);
        if (dot != null) {
            ViewGroup.LayoutParams params = dot.getLayoutParams();
            int sizePx = params != null && params.width > 0 ? params.width : Math.round(26 * density);
            dotRadiusDp = sizePx / density / 2f;
            dotShape = new GradientDrawable();
            dotShape.setColor(color(R.color.recording));
            dot.setBackground(dotShape);
            applyMorph(0f);
        } else {
            dotRadiusDp = 13f;
            dotShape = null;
        }
        render(State.IDLE, false);
    }

    /** 录制中那行「熄屏后…」的小字不在按钮里时，从外面交进来。 */
    public void setHintView(View hintView) {
        this.hint = hintView;
        if (hint != null) {
            hint.setVisibility(state == State.RECORDING && !photoMode ? View.VISIBLE : View.GONE);
        }
    }

    /** 拍照模式：白圆盘、不画环；录制状态照旧记着。 */
    public void setPhotoMode(boolean photo) {
        if (photoMode == photo) {
            return;
        }
        photoMode = photo;
        render(state, true);
    }

    public boolean isPhotoMode() {
        return photoMode;
    }

    /** 分段时长（分钟），用于「每段 N 分钟」和「本段 mm:ss / N 分钟」。 */
    public void setSegmentMinutes(int minutes) {
        segmentMinutes = Math.max(1, minutes);
        if (state == State.IDLE) {
            showIdleDetail();
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State next) {
        if (next != null && next != state) {
            render(next, true);
        }
    }

    /**
     * 本段已录多久。只在录制中生效。
     *
     * @param elapsedMs 本段开始到现在
     * @param segmentMs 一段有多长
     */
    public void setSegmentProgress(long elapsedMs, long segmentMs) {
        if (state != State.RECORDING || segmentMs <= 0) {
            return;
        }
        long clamped = Math.max(0, Math.min(elapsedMs, segmentMs));
        if (ring != null && !ring.isIndeterminate()) {
            ring.setProgressCompat((int) (clamped * 1000 / segmentMs), true);
        }
        if (detail != null) {
            detail.setText(context.getString(R.string.record_segment_detail,
                    mmss(clamped), segmentMinutes));
        }
    }

    /** 换段的一瞬：点淡下去再回来，环从零开始。 */
    public void flashSegment() {
        if (state != State.RECORDING || dot == null || photoMode) {
            return;
        }
        if (ring != null && !ring.isIndeterminate()) {
            ring.setProgressCompat(0, false);
        }
        if (flashAnimator != null) {
            flashAnimator.cancel();
        }
        flashAnimator = ValueAnimator.ofFloat(1f, 0.45f, 1f);
        flashAnimator.setDuration(FLASH_MS);
        flashAnimator.addUpdateListener(a -> dot.setAlpha((float) a.getAnimatedValue()));
        flashAnimator.start();
    }

    // ------------------------------------------------------------------ 状态

    private void render(State next, boolean animate) {
        state = next;
        if (photoMode) {
            renderPhoto(animate);
            return;
        }
        if (ring != null) {
            ring.setVisibility(View.VISIBLE);
        }
        if (hint != null) {
            hint.setVisibility(next == State.RECORDING ? View.VISIBLE : View.GONE);
        }
        int description = next == State.IDLE ? R.string.record_start
                : next == State.UNAVAILABLE ? R.string.record_unavailable
                : R.string.record_stop;
        root.setContentDescription(context.getString(description));
        if (dotShape != null) {
            dotShape.setColor(color(next == State.UNAVAILABLE ? R.color.text_tertiary : R.color.recording));
        }
        switch (next) {
            case PREPARING:
                setIndeterminate(true);
                morphTo(1f, animate);
                startPulse();
                backgroundTo(R.color.recording_quiet, animate);
                setTitle(R.string.record_preparing);
                setDetail("");
                break;
            case RECORDING:
                stopPulse();
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, false);
                }
                morphTo(1f, animate);
                backgroundTo(R.color.recording_quiet, animate);
                setTitle(R.string.record_stop);
                setDetail(context.getString(R.string.record_segment_detail, mmss(0), segmentMinutes));
                break;
            case UNAVAILABLE:
                stopPulse();
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, animate);
                }
                morphTo(0f, animate);
                backgroundTo(R.color.sunken, animate);
                setTitle(R.string.record_unavailable);
                setDetail(context.getString(R.string.record_unavailable_detail));
                break;
            case IDLE:
            default:
                stopPulse();
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, animate);
                }
                morphTo(0f, animate);
                backgroundTo(R.color.sunken, animate);
                setTitle(R.string.record_start);
                showIdleDetail();
                break;
        }
    }

    /** 拍照模式的样子：白圆盘，没有环，没有录制的小字。 */
    private void renderPhoto(boolean animate) {
        stopPulse();
        setIndeterminate(false);
        if (ring != null) {
            ring.setProgressCompat(0, false);
            ring.setVisibility(View.INVISIBLE);
        }
        if (hint != null) {
            hint.setVisibility(View.GONE);
        }
        root.setContentDescription(context.getString(R.string.record_take_photo));
        if (dotShape != null) {
            dotShape.setColor(color(R.color.white));
        }
        morphTo(0f, animate);
        backgroundTo(R.color.sunken, animate);
        setTitle(R.string.record_take_photo);
        setDetail("");
    }

    private void showIdleDetail() {
        setDetail(context.getString(R.string.record_idle_detail, segmentMinutes));
    }

    private void setTitle(int res) {
        if (title != null) {
            title.setText(res);
        }
    }

    private void setDetail(CharSequence text) {
        if (detail != null) {
            detail.setText(text);
        }
    }

    // ------------------------------------------------------------------ 动效

    /**
     * 环在「转圈」和「按比例走」之间切换。
     *
     * <p>先藏再换再露：有的版本在指示器可见时切模式会直接抛异常，
     * 与其赌版本，不如让它在切的那一瞬不可见。</p>
     */
    private void setIndeterminate(boolean on) {
        if (ring == null || ring.isIndeterminate() == on) {
            return;
        }
        int visibility = ring.getVisibility();
        ring.setVisibility(View.INVISIBLE);
        ring.setIndeterminate(on);
        ring.setVisibility(visibility);
    }

    private void morphTo(float target, boolean animate) {
        if (dotShape == null) {
            return;
        }
        if (morphAnimator != null) {
            morphAnimator.cancel();
        }
        if (!animate) {
            applyMorph(target);
            return;
        }
        morphAnimator = ValueAnimator.ofFloat(morph, target);
        morphAnimator.setDuration(MORPH_MS);
        // Material 的标准缓动（快出慢入），系统自带，不另引依赖
        morphAnimator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        morphAnimator.addUpdateListener(a -> applyMorph((float) a.getAnimatedValue()));
        morphAnimator.start();
    }

    /**
     * 0 = 整圆，1 = 缩到 0.77、圆角约为半径四分之一的方块。
     *
     * <p>尺寸用缩放而不是改宽高：缩放不触发重新布局。圆角半径写在未缩放的坐标里，
     * 所以按半径算：26dp 的小点终点约 5dp，56dp 的快门终点约 11dp。</p>
     */
    private void applyMorph(float t) {
        morph = t;
        float scale = 1f - 0.23f * t;
        dotShape.setCornerRadius((dotRadiusDp - dotRadiusDp * 0.5f * t) * density);
        dot.setScaleX(scale);
        dot.setScaleY(scale);
    }

    private void startPulse() {
        if (dot == null || pulseAnimator != null) {
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f);
        pulseAnimator.setDuration(PULSE_MS);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(a -> dot.setAlpha((float) a.getAnimatedValue()));
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (dot != null) {
            dot.setAlpha(1f);
        }
    }

    /**
     * 卡片底色。画面上的快门（layout_camera_controls）底色是透明的，
     * 不跟着状态变 —— 状态在圆盘和环上；带字的旧卡片才换底色。
     */
    private void backgroundTo(int colorRes, boolean animate) {
        if (card == null || card.getId() == R.id.btn_shutter) {
            return;
        }
        int to = color(colorRes);
        int from = card.getCardBackgroundColor().getDefaultColor();
        if (backgroundAnimator != null) {
            backgroundAnimator.cancel();
        }
        if (!animate || from == to) {
            card.setCardBackgroundColor(to);
            return;
        }
        backgroundAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        backgroundAnimator.setDuration(MORPH_MS);
        backgroundAnimator.addUpdateListener(
                a -> card.setCardBackgroundColor((int) a.getAnimatedValue()));
        backgroundAnimator.start();
    }

    // ------------------------------------------------------------------ 小工具

    private int color(int res) {
        return ContextCompat.getColor(context, res);
    }

    private static String mmss(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
