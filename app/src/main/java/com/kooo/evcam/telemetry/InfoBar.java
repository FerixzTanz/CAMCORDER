package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppConfig;

/**
 * 录像下方的行驶信息条：开不开、显示哪几项。
 *
 * <h3>一条规则</h3>
 *
 * <p>开着信息条的录像，画面比视频高 {@link #HEIGHT} 像素，最下面那一条是信息条
 * （{@code EncodeSize.withInfoBar}）。凡是按「四宫格」推算画面几何的地方
 * （回看的放大、鱼眼校正）都先把这一条减掉（{@code PlaybackViewport.infoBarInset}）。
 * 信息条本身画什么，看 {@link InfoBarRenderer}；数据从哪来，看 {@link Telemetry}。</p>
 *
 * <h3>显示哪几项</h3>
 *
 * <p>试验项目（1.68.0），对所有人开放，默认关。开了之后默认只放<b>实车验证过</b>的格
 * （{@link InfoBarLayout.Cell#verified()}：这一格用到的信号都在车上看到过跟着变），没验证的信号在
 * 映射前就滤掉（{@link Readings#verifiedOnly()}），不会把猜的东西画进录像。开发者模式里的
 * 「显示所有行车信息」放全部格，用全部读数。</p>
 *
 * <p>开着信息条时录制走 MediaCodec 路径（要用 GL 拼画面，和四宫格同一条规则，
 * 见 {@code AppConfig.shouldUseCodecRecording}）。</p>
 */
public final class InfoBar {

    /** 信息条的高度（像素），和视频宽度无关。 */
    public static final int HEIGHT = 100;

    private InfoBar() {
    }

    /** 这次录制放哪些格：只放验证过的，还是全部。 */
    public static final class Options {
        public final boolean all;

        public Options(boolean all) {
            this.all = all;
        }

        public static Options all() {
            return new Options(true);
        }

        public static Options verified() {
            return new Options(false);
        }
    }

    /** 这次录制要不要信息条；不要返回 null。 */
    public static Options forRecording(Context context) {
        AppConfig config = new AppConfig(context);
        if (!config.isInfoBarEnabled()) {
            return null;
        }
        return new Options(config.isInfoBarShowAll());
    }
}
