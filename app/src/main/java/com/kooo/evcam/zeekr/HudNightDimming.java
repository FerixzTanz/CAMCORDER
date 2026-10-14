package com.kooo.evcam.zeekr;

/**
 * HUD 上侧视画面夜间自动调暗。
 *
 * <p>白天调好的亮度，夜里投在路面上会刺眼。车上有昼夜信号（{@code Signal.DAY_NIGHT}），
 * 夜间模式时在设置的不透明度上再乘一个比例；白天、信号读不到时都不动。纯 Java，见 {@code HudNightDimmingTest}。</p>
 */
public final class HudNightDimming {

    private HudNightDimming() {
    }

    /**
     * 窗口最终的不透明度，0..1。
     *
     * @param opacityPercent 设置里的不透明度
     * @param nightPercent   夜间再乘的比例，100 是不调暗
     * @param night          车上是不是夜间模式（读不到按白天）
     */
    public static float opacity(int opacityPercent, int nightPercent, boolean night) {
        float base = opacityPercent / 100f;
        return night ? base * nightPercent / 100f : base;
    }
}
