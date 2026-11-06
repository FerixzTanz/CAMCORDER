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
import android.view.Gravity;
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
 * 开发者选项「测试仪表盘」：在仪表盘那块屏（{@link ClusterDisplayLocator}）上放一张小测试卡片 {@link #SHOW_MS}，
 * 看普通应用能不能往那里画 —— 将来能不能把地图之类的东西放上去，先看这一步。
 *
 * <h3>只用普通办法</h3>
 *
 * <p>和 HUD 侧视画面（{@link SideViewHudPanel}）一样，开一个 {@link Presentation}：安卓给第二块屏准备的公开接口，
 * 不要 root、不要 ADB、不改任何系统设置。系统不让（屏是私有的、开窗抛异常、开了马上被收掉）就到此为止，报告结果。</p>
 *
 * <h3>只在 P 档</h3>
 *
 * <p>仪表盘上是车速和告警，开车时一点都不能挡：只在读到 P 档时测（读不到也不测）。卡片小、放在正中、
 * 只留 {@link #SHOW_MS}，到点自己收。结果弹一句提示，细节（找到哪块屏、flags、异常原文）记进黑匣子，
 * 诊断报告的「显示屏」一节也带上最近一次的结果（{@link #lastResult}）。</p>
 */
public final class ClusterDisplayTest {

    private static final String TAG = "ClusterDisplayTest";
    private static final String TELEMETRY_USER = "cluster-test";
    /** 卡片留多久。 */
    static final long SHOW_MS = 5_000L;
    /** 开了之后多久看一眼窗口还在不在（HUD 上见过开了马上被系统收掉的）。 */
    private static final long CHECK_MS = 1_000L;
    /** 等档位读数最多等多久（车辆信号刚拉起来时要一会儿）。 */
    private static final long GEAR_WAIT_MS = 2_000L;
    private static final long GEAR_POLL_MS = 200L;
    /** 卡片大小（像素，仪表盘是 2560×536）。 */
    private static final int CARD_WIDTH = 640;
    private static final int CARD_HEIGHT = 200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String lastResult = "not run";
    private static Presentation current;
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
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CARD_WIDTH, CARD_HEIGHT, Gravity.CENTER);
            frame.addView(new Card(p.getContext(),
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
            BlackBox.noteImportant("仪表盘测试：卡片开在 " + where + "，请看仪表盘上有没有");
            toast(app, R.string.dev_cluster_test_shown);
        }, CHECK_MS);
        MAIN.postDelayed(() -> {
            if (current != shown) {
                return;
            }
            current = null;
            try {
                shown.dismiss();
            } catch (Throwable ignored) {
                // 屏已经没了：系统已经收掉
            }
            Telemetry.get().release(TELEMETRY_USER);
            running = false;
            BlackBox.note("仪表盘测试：卡片收起");
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

    /** 测试卡片：黑底、橙色描边、居中一行字。 */
    private static final class Card extends View {
        private final String text;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Card(Context context, String text) {
            super(context);
            this.text = text;
            fill.setColor(0xE6000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(6f);
            stroke.setColor(0xFFFF7A1A);
            label.setColor(0xFFFFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(48f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = stroke.getStrokeWidth() / 2f;
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(rect, 28f, 28f, fill);
            canvas.drawRoundRect(rect, 28f, 28f, stroke);
            Paint.FontMetrics fm = label.getFontMetrics();
            canvas.drawText(text, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2f, label);
        }
    }
}
