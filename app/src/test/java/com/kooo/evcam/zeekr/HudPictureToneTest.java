package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

/** HUD 画面的对比度曲线：100 原样，默认值把中灰压下去、白仍是白、黑仍是黑。 */
public class HudPictureToneTest {

    private static final float[] IDENTITY = {
            1, 0, 0, 0, 0,
            0, 1, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, 1, 0,
    };

    @Test
    public void hundredIsTheCamerasOwnPicture() {
        assertTrue(HudPictureTone.isIdentity(100));
        assertArrayEquals(IDENTITY, HudPictureTone.matrix(100), 1e-6f);
        assertEquals(102f, HudPictureTone.apply(100, 102f), 1e-6f);
    }

    @Test
    public void contrast150PushesMidGreyDownAndKeepsTheEnds() {
        int c = 150;   // 2.12.1 的默认值；曲线的数学用它来钉，不跟着默认值走（默认值现在是 220）
        assertFalse(HudPictureTone.isIdentity(c));
        // 40% 灰（路面）落到 30%
        assertEquals(76.5f, HudPictureTone.apply(c, 102f), 0.01f);
        // 枢轴不动，黑还是黑，白还是白（钳住）
        assertEquals(HudPictureTone.PIVOT, HudPictureTone.apply(c, HudPictureTone.PIVOT), 1e-6f);
        assertEquals(0f, HudPictureTone.apply(c, 0f), 1e-6f);
        assertEquals(255f, HudPictureTone.apply(c, 255f), 1e-6f);
    }

    @Test
    public void matrixIsTheSameLineAsApply() {
        float[] m = HudPictureTone.matrix(200);
        float grey = 120f;
        float viaMatrix = m[0] * grey + m[4];
        assertEquals(HudPictureTone.apply(200, grey), viaMatrix, 1e-4f);
        // 三个颜色通道一样，alpha 不动
        assertEquals(m[0], m[6], 1e-6f);
        assertEquals(m[0], m[12], 1e-6f);
        assertEquals(m[4], m[9], 1e-6f);
        assertEquals(m[4], m[14], 1e-6f);
        assertEquals(1f, m[18], 1e-6f);
        assertEquals(0f, m[19], 1e-6f);
    }

    @Test
    public void strongerIsDarkerBelowThePivotAndNeverFlips() {
        for (int c = AppConfig.HUD_SIDE_MIN_CONTRAST_PERCENT; c <= AppConfig.HUD_SIDE_MAX_CONTRAST_PERCENT; c += 50) {
            assertTrue(HudPictureTone.apply(c, 100f) <= 100f);
            assertTrue(HudPictureTone.apply(c, 200f) >= 200f);
            assertTrue(HudPictureTone.apply(c, 60f) <= HudPictureTone.apply(c, 140f));
        }
    }
}
