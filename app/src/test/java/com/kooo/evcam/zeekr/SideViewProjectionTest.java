package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SideViewProjection}：转动虚拟相机后，画面中心落到鱼眼原图的哪里。 */
public class SideViewProjectionTest {

    private static final float EPS = 1e-4f;

    private static float[] center(float back, float up, int lane) {
        float[] out = new float[2];
        SideViewProjection.sourcePoint(0.5f, 0.5f, 90f, 1f, back, up, lane, out, 0);
        return out;
    }

    @Test
    public void notTurnedLooksAtTheFisheyeCentre() {
        float[] c = center(0f, 0f, LaneCycle.LEFT);
        assertEquals(0.5f, c[0], EPS);
        assertEquals(0.5f, c[1], EPS);
    }

    @Test
    public void ninetyDegreesBackIsTheLaneEdge() {
        // 偏 90° = 原图半径 0.5；只转 80° 封顶，所以是 80/90 × 0.5
        float[] left = center(90f, 0f, LaneCycle.LEFT);
        float[] right = center(90f, 0f, LaneCycle.RIGHT);
        float expected = 80f / 90f * 0.5f;
        assertEquals("左侧车尾在左边", 0.5f - expected, left[0], EPS);
        assertEquals("右侧车尾在右边", 0.5f + expected, right[0], EPS);
        assertEquals(0.5f, left[1], EPS);
    }

    @Test
    public void fortyFiveBackIsAQuarterOfTheWay() {
        float[] c = center(45f, 0f, LaneCycle.RIGHT);
        assertEquals(0.75f, c[0], EPS);
    }

    @Test
    public void upMovesTowardTheTop() {
        float[] up = center(0f, 30f, LaneCycle.LEFT);
        float[] down = center(0f, -30f, LaneCycle.LEFT);
        assertEquals(0.5f - 30f / 90f * 0.5f, up[1], EPS);
        assertEquals(0.5f + 30f / 90f * 0.5f, down[1], EPS);
    }

    @Test
    public void neverLeavesTheLane() {
        float[] out = new float[2];
        for (float u = 0f; u <= 1f; u += 0.25f) {
            for (float v = 0f; v <= 1f; v += 0.25f) {
                SideViewProjection.sourcePoint(u, v, 180f, 2.5f, 80f, 60f, LaneCycle.RIGHT, out, 0);
                assertTrue(out[0] >= 0f && out[0] <= 1f && out[1] >= 0f && out[1] <= 1f);
            }
        }
    }

    @Test
    public void sideEdgeAngleIsLinearInWidth() {
        // 柱面：横向边缘正好在 fov/2，180° 宽时右边缘是偏 90° = 这一路的边
        float[] out = new float[2];
        SideViewProjection.sourcePoint(1f, 0.5f, 180f, 2f, 0f, 0f, LaneCycle.RIGHT, out, 0);
        assertEquals(1f, out[0], EPS);
        assertEquals(0.5f, out[1], EPS);
    }

    @Test
    public void widerWindowSeesLessVertically() {
        // 同样的横向视野，窗口越扁，上边缘偏得越少
        float[] square = new float[2];
        float[] wide = new float[2];
        SideViewProjection.sourcePoint(0.5f, 0f, 120f, 1f, 0f, 0f, LaneCycle.RIGHT, square, 0);
        SideViewProjection.sourcePoint(0.5f, 0f, 120f, 2f, 0f, 0f, LaneCycle.RIGHT, wide, 0);
        assertTrue(wide[1] > square[1]);
    }

    @Test
    public void wideViewReachesFurtherOut() {
        float[] narrow = new float[2];
        float[] wide = new float[2];
        SideViewProjection.sourcePoint(1f, 0.5f, 60f, 1f, 0f, 0f, LaneCycle.RIGHT, narrow, 0);
        SideViewProjection.sourcePoint(1f, 0.5f, 120f, 1f, 0f, 0f, LaneCycle.RIGHT, wide, 0);
        assertTrue(wide[0] > narrow[0]);
    }
}
