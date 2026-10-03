package com.kooo.evcam.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 一行字：车辆的哨兵模式此刻开没开（「哨兵模式：已开启 / 已布防 / 未开启」）。
 *
 * <p>熄屏后还能不能接着录看的就是哨兵模式，所以凡是和熄屏录制有关的地方都可以放它 ——
 * 现在在「设置 → 系统 → 熄屏持续录制」那一行的右边。读的是 {@link Telemetry#latest()} 里的
 * {@code sentry}（0 关、1 开、2 布防），和信息条、车辆状态面板是同一份。</p>
 *
 * <p>自己管自己（同 {@link VehicleStatusPanel}）：挂在窗口上而且窗口看得见时向 {@link Telemetry}
 * 登记（"sentry-status"），看不见就注销；没别人在用的话车辆信号就停下来。读数一变就换字。</p>
 */
public class SentryStatusView extends AppCompatTextView implements Telemetry.Listener {

    private static final String USER = "sentry-status";

    private boolean active;

    public SentryStatusView(Context context) {
        this(context, null);
    }

    public SentryStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        refresh();
    }

    private void refresh() {
        boolean shouldRun = isAttachedToWindow() && getWindowVisibility() == VISIBLE;
        if (shouldRun && !active) {
            active = true;
            Telemetry.get().addListener(this);
            Telemetry.get().acquire(getContext(), USER);
        } else if (!shouldRun) {
            stop();
        }
        show();
    }

    private void stop() {
        if (!active) {
            return;
        }
        active = false;
        Telemetry.get().removeListener(this);
        Telemetry.get().release(USER);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        show();
    }

    private void show() {
        Integer sentry = Telemetry.get().latest().sentry;
        int text;
        boolean on = false;
        if (sentry == null) {
            // 还没读过一轮是「读取中」；读过了还没有就是这台车机读不到
            text = Telemetry.get().readings().version > 0
                    ? R.string.sentry_status_unknown : R.string.sentry_status_reading;
        } else if (sentry == 2) {
            text = R.string.sentry_status_armed;
            on = true;
        } else if (sentry == 1) {
            text = R.string.sentry_status_on;
            on = true;
        } else {
            text = R.string.sentry_status_off;
        }
        setText(text);
        setTextColor(ContextCompat.getColor(getContext(), on ? R.color.energy : R.color.text_secondary));
    }
}
