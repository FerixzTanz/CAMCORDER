package com.kooo.evcam.zeekr;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.settings.Languages;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发者选项「测试仪表盘」：在仪表盘那块屏（{@link ClusterDisplayLocator}）上铺一张测试图案 {@link #SHOW_MS}，
 * 看普通应用能不能往那里画 —— 将来能不能把地图之类的东西放上去，先看这一步。
 *
 * <h3>只用普通办法</h3>
 *
 * <p>和 HUD 侧视画面（{@link SideViewHudPanel}）一样，开一个 {@link Presentation}：安卓给第二块屏准备的公开接口，
 * 不要 root、不要 ADB、不改任何系统设置。系统不让（屏是私有的、开窗抛异常、开了马上被收掉）就到此为止，报告结果。</p>
 *
 * <h3>第二步：请车机投屏（2.15.3）</h3>
 *
 * <p>2.15.0 的小卡片车机收下了、仪表盘上却没有（用户 2026-11-06 实车）：ECARX 的仪表在安卓那一层上面盖着自己的表盘，
 * 要被告知「投屏」才露出来，而且 7X 的仪表只在右边一个小导航窗里露安卓的画面。所以这一版：图案铺满整块屏、
 * 左中右各写一个字，露出哪一块都认得出；窗口开好后再通过极氪 SDK 请车机投屏（{@link ClusterProjection}：
 * REQUEST → START，到点 STOP 收回），每一步的结果记进黑匣子和诊断报告。</p>
 *
 * <h3>只在 P 档</h3>
 *
 * <p>仪表盘上是车速和告警，开车时一点都不能挡：只在读到 P 档时测（读不到也不测）。图案只留 {@link #SHOW_MS}，
 * 到点自己收。结果弹一句提示，细节（找到哪块屏、flags、异常原文、投屏每一步）记进黑匣子，
 * 诊断报告的「显示屏」一节也带上最近一次的结果（{@link #lastResult}）。</p>
 */
public final class ClusterDisplayTest {

    private static final String TAG = "ClusterDisplayTest";
    private static final String TELEMETRY_USER = "cluster-test";
    /** 图案留多久（连仪表交互接口要 1 秒上下，投屏请求再等一会儿）。 */
    static final long SHOW_MS = 8_000L;
    /** REQUEST 发出去之后隔多久发 START。 */
    private static final long START_AFTER_REQUEST_MS = 500L;
    /** 开了之后多久看一眼窗口还在不在（HUD 上见过开了马上被系统收掉的）。 */
    private static final long CHECK_MS = 1_000L;
    /** 等档位读数最多等多久（车辆信号刚拉起来时要一会儿）。 */
    private static final long GEAR_WAIT_MS = 2_000L;
    private static final long GEAR_POLL_MS = 200L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String lastResult = "not run";
    private static Presentation current;
    /** 这一次测试的投屏请求；连不上是 null。 */
    private static ClusterProjection projection;
    /** 从点下到收起都算一次在跑：等档位那两秒里再点不会起第二份（第二份会把第一份的收起计时顶掉，卡片就留在仪表盘上了）。 */
    private static boolean running;

    private ClusterDisplayTest() {
    }

    /** 诊断报告里的一行：最近一次测试的结果（英文）。 */
    public static String lastResult() {
        return lastResult;
    }

    /** 设置页点了「测试仪表盘」。主线程。 */
    public static void run(Context context) {
        if (running) {
            return;
        }
        running = true;
        Context app = context.getApplicationContext();
        Telemetry.get().acquire(app, TELEMETRY_USER);
        waitForGear(app, System.currentTimeMillis());
    }

    private static void waitForGear(Context app, long startedMs) {
        String gear = Telemetry.get().readings().text(Signal.GEAR);
        if (gear == null && System.currentTimeMillis() - startedMs < GEAR_WAIT_MS) {
            MAIN.postDelayed(() -> waitForGear(app, startedMs), GEAR_POLL_MS);
            return;
        }
        if (!"P".equals(gear)) {
            Telemetry.get().release(TELEMETRY_USER);
            running = false;
            finish(app, "refused: gear " + gear, R.string.dev_cluster_test_need_park);
            return;
        }
        show(app);
    }

    private static void show(Context app) {
        Display display = find(app);
        if (display == null) {
            Telemetry.get().release(TELEMETRY_USER);
            running = false;
            finish(app, "no " + ClusterDisplayLocator.CLUSTER_WIDTH + "x" + ClusterDisplayLocator.CLUSTER_HEIGHT
                    + " display that is on", R.string.dev_cluster_test_none);
            return;
        }
        String where = "display " + display.getDisplayId() + " (" + display.getName()
                + ", flags=0x" + Integer.toHexString(display.getFlags()) + ")";
        Presentation p;
        try {
            p = new Presentation(app, display);
            FrameLayout frame = new FrameLayout(p.getContext());
            frame.setBackgroundColor(0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            frame.addView(new Pattern(p.getContext(),
                    Languages.localized(app).getString(R.string.dev_cluster_test_card)), lp);
            p.setContentView(frame);
            Window window = p.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setBackgroundDrawable(new ColorDrawable(0));
            }
            p.show();
        } catch (Throwable t) {
            AppLog.e(TAG, "仪表盘测试窗口打不开", t);
            Telemetry.get().release(TELEMETRY_USER);
            running = false;
            finish(app, "refused on " + where + ": " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    R.string.dev_cluster_test_refused);
            return;
        }
        current = p;
        final Presentation shown = p;
        final Point size = new Point();
        try {
            display.getRealSize(size);
        } catch (Exception ignored) {
            size.set(ClusterDisplayLocator.CLUSTER_WIDTH, ClusterDisplayLocator.CLUSTER_HEIGHT);
        }
        // 窗口开着了：另起线程连仪表交互接口（约 1 秒），连上了回主线程请车机投屏
        new Thread(() -> {
            StringBuilder reason = new StringBuilder();
            ClusterProjection cp = ClusterProjection.connect(app, reason);
            MAIN.post(() -> {
                if (current != shown) {
                    if (cp != null) {
                        cp.close();
                    }
                    return;
                }
                if (cp == null) {
                    BlackBox.noteImportant("仪表盘投屏：连不上仪表交互接口（" + reason + "）");
                    lastResult = lastResult + "; projection unavailable: " + reason;
                    return;
                }
                projection = cp;
                cp.listen(state -> { });
                cp.send(ClusterProjection.ACTION_REQUEST, 0, 0, size.x, size.y);
                MAIN.postDelayed(() -> {
                    if (current == shown && projection == cp) {
                        cp.send(ClusterProjection.ACTION_START, 0, 0, size.x, size.y);
                    }
                }, START_AFTER_REQUEST_MS);
            });
        }, "ClusterDisplayTest-dim").start();
        MAIN.postDelayed(() -> {
            if (current != shown) {
                return;
            }
            if (!shown.isShowing()) {
                current = null;
                Telemetry.get().release(TELEMETRY_USER);
                running = false;
                finish(app, "opened on " + where + " but closed by the system within 1 s",
                        R.string.dev_cluster_test_refused);
                return;
            }
            lastResult = "showing on " + where;
            BlackBox.noteImportant("仪表盘测试：图案铺在 " + where + "，请看仪表盘上露出哪一块");
            toast(app, R.string.dev_cluster_test_shown);
        }, CHECK_MS);
        MAIN.postDelayed(() -> {
            if (current != shown) {
                return;
            }
            current = null;
            ClusterProjection cp = projection;
            projection = null;
            if (cp != null) {
                // 先请车机收回投屏，再收窗口
                cp.send(ClusterProjection.ACTION_STOP, 0, 0, size.x, size.y);
                lastResult = lastResult + "; projection: " + cp.log();
                cp.close();
            }
            try {
                shown.dismiss();
            } catch (Throwable ignored) {
                // 屏已经没了：系统已经收掉
            }
            Telemetry.get().release(TELEMETRY_USER);
            running = false;
            BlackBox.noteImportant("仪表盘测试：收起；结果 " + lastResult);
        }, SHOW_MS);
    }

    /** 仪表盘那块屏（亮着的才算）；找不到是 null。所有屏的尺寸记进黑匣子。 */
    private static Display find(Context app) {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        Display[] displays = dm.getDisplays();
        List<HudDisplayLocator.Candidate> candidates = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (Display d : displays) {
            Point size = new Point();
            try {
                d.getRealSize(size);
            } catch (Exception ignored) {
                // 拿不到尺寸就当 0×0，不会被认成仪表盘
            }
            all.append(" [").append(d.getDisplayId()).append(' ').append(size.x).append('x').append(size.y)
                    .append(" state=").append(d.getState()).append(" flags=0x")
                    .append(Integer.toHexString(d.getFlags())).append(']');
            if (d.getState() == Display.STATE_ON) {
                candidates.add(new HudDisplayLocator.Candidate(d.getDisplayId(), size.x, size.y));
            }
        }
        BlackBox.note("仪表盘测试：车机上的屏" + all);
        int id = ClusterDisplayLocator.find(candidates);
        for (Display d : displays) {
            if (d.getDisplayId() == id) {
                return d;
            }
        }
        return null;
    }

    private static void finish(Context app, String result, int messageRes) {
        lastResult = result;
        BlackBox.noteImportant("仪表盘测试：" + result);
        toast(app, messageRes);
    }

    private static void toast(Context app, int messageRes) {
        Toast.makeText(app, Languages.localized(app).getString(messageRes), Toast.LENGTH_LONG).show();
    }

    /** 测试图案：铺满整块屏，半透明黑底、橙色外框、三等分竖线，左中右各写 LEFT / CENTER / RIGHT 和那行字。 */
    private static final class Pattern extends View {
        private static final String[] ZONES = {"LEFT", "CENTER", "RIGHT"};
        private final String text;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Pattern(Context context, String text) {
            super(context);
            this.text = text;
            fill.setColor(0xB0000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(8f);
            stroke.setColor(0xFFFF7A1A);
            label.setColor(0xFFFFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(96f);
            label.setFakeBoldText(true);
            small.setColor(0xFFFF7A1A);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(44f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float inset = stroke.getStrokeWidth() / 2f;
            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRect(rect, fill);
            canvas.drawRect(rect, stroke);
            float third = w / 3f;
            canvas.drawLine(third, 0f, third, h, stroke);
            canvas.drawLine(2f * third, 0f, 2f * third, h, stroke);
            Paint.FontMetrics fm = label.getFontMetrics();
            float mid = h / 2f - (fm.ascent + fm.descent) / 2f;
            for (int i = 0; i < 3; i++) {
                float cx = third * i + third / 2f;
                canvas.drawText(ZONES[i], cx, mid, label);
                canvas.drawText(text, cx, mid + 80f, small);
            }
        }
    }
}
