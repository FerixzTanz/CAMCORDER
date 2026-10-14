package com.kooo.evcam.zeekr;

/**
 * HUD 上侧视画面的边缘渐隐。
 *
 * <p>HUD 是投影，黑色不亮。画面四边各自从不透明渐隐到透明，露出底下的黑，
 * 投到路面上就是一块边缘柔和的画面，而不是一块亮的长方形。形状仍是设置里调的那个长方形，
 * 只是边缘不再是一刀切。</p>
 *
 * <p>渐隐宽度按短边的百分比算：画面再扁，四边看上去一样软。纯 Java，见 {@code HudEdgeFadeTest}。</p>
 */
public final class HudEdgeFade {

    private HudEdgeFade() {
    }

    /**
     * 渐隐带的宽度（像素）。
     *
     * @param percent 短边的百分比，0 是硬边
     * @return 0 表示不渐隐；最大是短边的一半（上下、左右两条带正好在中线相接）
     */
    public static float widthPx(int percent, int width, int height) {
        if (percent <= 0 || width <= 0 || height <= 0) {
            return 0f;
        }
        float shorter = Math.min(width, height);
        return Math.min(shorter / 2f, shorter * percent / 100f);
    }
}
