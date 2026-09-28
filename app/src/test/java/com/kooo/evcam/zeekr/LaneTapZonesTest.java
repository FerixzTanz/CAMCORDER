package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 按键模式的四块三角。
 *
 * <p>位置本身就是答案（上前、下后、左左、右右）：位置错了会让人凭肌肉记忆点错。</p>
 */
public class LaneTapZonesTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 400;

    @Test
    public void eachEdgeMidpointBelongsToItsDirection() {
        assertEquals(LaneCycle.FRONT, LaneTapZones.laneAt(400, 20, WIDTH, HEIGHT));
        assertEquals(LaneCycle.REAR, LaneTapZones.laneAt(400, 380, WIDTH, HEIGHT));
        assertEquals(LaneCycle.LEFT, LaneTapZones.laneAt(20, 200, WIDTH, HEIGHT));
        assertEquals(LaneCycle.RIGHT, LaneTapZones.laneAt(780, 200, WIDTH, HEIGHT));
    }

    /** 分界是窗口自己的对角线，不是一个正方形的：扁窗口里靠左一点、偏上的点仍然算前。 */
    @Test
    public void diagonalsFollowTheWindowShape() {
        // 800×400 里 (200, 90)：左上—右下那条线在 x=200 处 y=100，点在它上方；
        // 按 45° 的线算会落进「左」
        assertEquals(LaneCycle.FRONT, LaneTapZones.laneAt(200, 90, WIDTH, HEIGHT));
        assertEquals(LaneCycle.LEFT, LaneTapZones.laneAt(200, 110, WIDTH, HEIGHT));
        assertEquals(LaneCycle.RIGHT, LaneTapZones.laneAt(600, 110, WIDTH, HEIGHT));
        assertEquals(LaneCycle.REAR, LaneTapZones.laneAt(600, 310, WIDTH, HEIGHT));
    }

    @Test
    public void noSizeMeansNoZone() {
        assertEquals(-1, LaneTapZones.laneAt(1, 1, 0, 400));
    }

    /** 画出来的三角和点中的判定是同一块：三角的重心要落回同一路。 */
    @Test
    public void triangleCentroidHitsItsOwnLane() {
        int[] lanes = {LaneCycle.FRONT, LaneCycle.REAR, LaneCycle.LEFT, LaneCycle.RIGHT};
        for (int lane : lanes) {
            float[] t = LaneTapZones.triangle(lane, WIDTH, HEIGHT);
            float x = (t[0] + t[2] + t[4]) / 3f;
            float y = (t[1] + t[3] + t[5]) / 3f;
            assertEquals(lane, LaneTapZones.laneAt(x, y, WIDTH, HEIGHT));
        }
    }
}
