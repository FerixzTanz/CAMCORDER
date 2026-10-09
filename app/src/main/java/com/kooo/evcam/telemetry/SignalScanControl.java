package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 信号普查开不开，按开关（开发者选项）来：开着且没到点就记，否则停。
 * 应用启动时调一次（进程重启后接着记），开关一动调一次。到点自己停时把开关关掉。
 */
public final class SignalScanControl {

    private SignalScanControl() {
    }

    public static void apply(Context context) {
        final Context app = context.getApplicationContext();
        AppConfig config = new AppConfig(app);
        long left = config.signalScanRemainingMs(SignalScan.MAX_RUN_MS);
        if (left > 0) {
            if (!SignalScan.isRunning()) {
                BlackBox.noteImportant("signal scan on, " + (left / 60000) + " min left");
            }
            SignalScan.start(app, left, () -> {
                new AppConfig(app).setSignalScanEnabled(false);
                BlackBox.noteImportant("signal scan reached its time limit, switched off");
            });
        } else {
            if (config.isSignalScanEnabled()) {
                config.setSignalScanEnabled(false);   // 到点了：开关跟着关
            }
            if (SignalScan.isRunning()) {
                BlackBox.noteImportant("signal scan off");
            }
            SignalScan.stop();
        }
    }
}
