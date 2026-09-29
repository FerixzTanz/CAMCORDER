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
 * <p>试验项目（1.68.0），对所有人开放，默认关。格子永远全放；开了之后默认只有<b>能用</b>的格
 * 启用（{@link InfoBarLayout.Cell#usable()}：这一格用到的信号都是确认了的或先用着的），其余的画斜杠划掉；
 * 不能用的信号在映射前就滤掉（{@link Readings#usableOnly()}），不会把猜的东西画进录像。
 * 开发者模式里的「激活所有栏目信息」启用全部格、用全部读数 —— 用来验证各栏图标真不真、能不能用。</p>
 *
 * <p>开着信息条时录制走 MediaCodec 路径（要用 GL 拼画面，和四宫格同一条规则，
 * 见 {@code AppConfig.shouldUseCodecRecording}）。</p>
 */
public final class InfoBar {

    /** 信息条的高度（像素），和视频宽度无关。 */
    public static final int HEIGHT = 100;

    private InfoBar() {
    }

    /** 这次录制启用哪些格：只启用能用的，还是全部。 */
    public static final class Options {
        public final boolean all;

        public Options(boolean all) {
            this.all = all;
        }

        public static Options all() {
            return new Options(true);
        }

        public static Options usable() {
            return new Options(false);
        }
    }

    /** 这次录制要不要信息条；不要返回 null。 */
    public static Options forRecording(Context context) {
        AppConfig config = new AppConfig(context);
        if (!config.isInfoBarEnabled()) {
            return null;
        }
        return new Options(config.isInfoBarAllActive());
    }
}
