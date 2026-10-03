package com.kooo.evcam.recording;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

/**
 * 正在录的时候熄屏，这段录像会怎样。一份规矩，两处用：{@link RecordingCoordinator} 照它做，
 * 录制键上那行小字照它说 —— 界面上写的就是实际会发生的。
 *
 * <p>App 自己能决定的只有两件：熄屏后停不停（{@link #keepsRecording}），因熄屏停下的录像亮屏后接不接
 * （{@link #resumesOnScreenOn}）。车机睡不睡由车辆的哨兵模式决定：开着时车机一直醒着、只是黑屏，录像不断；
 * 没开的话车机睡着，录像停在那一刻，醒来接着录（lifecycle-spec §2.4）。开发者的「熄屏录制」拿唤醒锁拉住车机，
 * 哨兵模式开没开都接着录。</p>
 */
public enum ScreenOffPlan {

    /** 熄屏后继续录制。 */
    CONTINUE(R.string.record_screen_off_continue),
    /** 熄屏后停一阵，唤醒后恢复：车机睡着时录像停住，或熄屏停录、亮屏接回 —— 用户看到的是同一件事。 */
    PAUSE(R.string.record_screen_off_pause),
    /** 熄屏后停止录制，亮屏也不接。 */
    STOP(R.string.record_screen_off_stop),
    /** 熄屏持续录制开着，但读不到哨兵模式：接不接着录要看哨兵模式开没开。 */
    NEEDS_SENTRY(R.string.record_screen_off_needs_sentry);

    /** 录制键上那行小字。 */
    public final int text;

    ScreenOffPlan(int text) {
        this.text = text;
    }

    /** 熄屏后不停录：开发者「熄屏录制」或「熄屏持续录制」开着。 */
    static boolean keepsRecording(AppConfig config) {
        return config.isScreenOffRecordingEnabled() || config.isScreenOffKeepRecording();
    }

    /** 因熄屏停下的录像，亮屏后接回：「启动自动录制」开着（项目所有者 2026-09-27）。 */
    static boolean resumesOnScreenOn(AppConfig config) {
        return config.isAutoStartRecording();
    }

    /**
     * @param sentry 车辆哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     */
    public static ScreenOffPlan of(AppConfig config, Integer sentry) {
        return of(keepsRecording(config), config.isScreenOffRecordingEnabled(), resumesOnScreenOn(config), sentry);
    }

    /** 纯函数，测试直接调。 */
    static ScreenOffPlan of(boolean keeps, boolean holdsCarAwake, boolean resumes, Integer sentry) {
        if (!keeps) {
            return resumes ? PAUSE : STOP;
        }
        if (holdsCarAwake) {
            return CONTINUE;
        }
        if (sentry == null) {
            return NEEDS_SENTRY;
        }
        return sentry != 0 ? CONTINUE : PAUSE;
    }
}
