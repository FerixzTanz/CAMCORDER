package com.kooo.evcam.zeekr;

/**
 * HUD 上侧视画面的对比度曲线。
 *
 * <p>HUD 是投影：黑色是「不亮」，其余每一级灰都是亮光叠在路面上。摄像头的画面大半是中灰
 * （沥青、天边、车身反光），原样投上去就是一片发白、分不出层次（用户 2026-10-14：「太白、太淡」）。
 * 原厂 HUD 上的图标都是饱和色压在黑底上，就是这个缘故。</p>
 *
 * <p>所以画面投到 HUD 之前过一条直线：{@code out = k × in + (1 − k) × 枢轴}，k 是对比度 ÷ 100，
 * 枢轴取 {@link #PIVOT}（比中灰高）：比它暗的都往黑压，只有亮部提亮。100 是原画面；
 * 默认 {@link com.kooo.evcam.AppConfig#HUD_SIDE_DEFAULT_CONTRAST_PERCENT} 时 40% 灰落到 30%、路面大体沉下去，
 * 白色车身仍是白。</p>
 *
 * <p>纯 Java，不碰 Android：返回的是 {@code android.graphics.ColorMatrix} 那 4×5 的数组，见 {@code HudPictureToneTest}。</p>
 */
public final class HudPictureTone {

    /** 枢轴，0..255：这一级灰不动，比它暗的压暗、比它亮的提亮。 */
    public static final float PIVOT = 153f;   // 60%

    private HudPictureTone() {
    }

    /** 对比度 100 就是原画面：这时不必套曲线。 */
    public static boolean isIdentity(int contrastPercent) {
        return contrastPercent == 100;
    }

    /**
     * 4×5 颜色矩阵（行优先，R G B A 四行，每行 R G B A 偏移）。
     *
     * @param contrastPercent 100 原样，越大越硬；100 以下不收（调用方钳过了），给了也按公式算
     */
    public static float[] matrix(int contrastPercent) {
        float k = contrastPercent / 100f;
        float offset = (1f - k) * PIVOT;
        return new float[] {
                k, 0, 0, 0, offset,
                0, k, 0, 0, offset,
                0, 0, k, 0, offset,
                0, 0, 0, 1, 0,
        };
    }

    /** 一级灰（0..255）过曲线之后是多少，钳在 0..255：测试和说明用。 */
    public static float apply(int contrastPercent, float grey) {
        float k = contrastPercent / 100f;
        return Math.max(0f, Math.min(255f, k * grey + (1f - k) * PIVOT));
    }
}
