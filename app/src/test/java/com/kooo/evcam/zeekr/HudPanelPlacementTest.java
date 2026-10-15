package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

/**
 * {@link HudPanelPlacement}：HUD 上画面的大小和位置。默认值（项目拥有者 2026-10-15 的设置）要摆出 1440×480 上 456×235 px、离边留约 108 px 的样子：
 * 左灯靠左、右灯靠右。
 */
public class HudPanelPlacementTest {

    private static HudPanelPlacement.Rect defaults(boolean rightSide) {
        return HudPanelPlacement.compute(1440, 480,
                AppConfig.HUD_SIDE_DEFAULT_SIZE_PERCENT, AppConfig.HUD_SIDE_DEFAULT_SHAPE_PERCENT,
                AppConfig.HUD_SIDE_DEFAULT_LEFT_PERCENT, AppConfig.HUD_SIDE_DEFAULT_VERTICAL_PERCENT, rightSide);
    }

    @Test
    public void defaultsAreTheOwnersFourFiftySixByTwoThirtyFive() {
        HudPanelPlacement.Rect r = defaults(false);
        assertEquals(235, r.height);
        assertEquals(456, r.width);
        assertEquals(108, r.left);
        assertEquals(51, r.top);
    }

    @Test
    public void rightSignalSitsTheSameDistanceFromTheRightEdge() {
        HudPanelPlacement.Rect left = defaults(false);
        HudPanelPlacement.Rect right = defaults(true);
        assertEquals(left.width, right.width);
        assertEquals(left.top, right.top);
        assertEquals(1440 - 456 - 108, right.left);
        assertEquals(left.left, 1440 - (right.left + right.width));
    }

    @Test
    public void fiftyIsTheMiddleFromEitherSide() {
        HudPanelPlacement.Rect left = HudPanelPlacement.compute(1440, 480, 50, 100, 50, 50, false);
        HudPanelPlacement.Rect right = HudPanelPlacement.compute(1440, 480, 50, 100, 50, 50, true);
        assertEquals(600, left.left);
        assertEquals(600, right.left);
    }

    @Test
    public void shapeWidensTheWindow() {
        HudPanelPlacement.Rect r = HudPanelPlacement.compute(1440, 480, 50, 200, 0, 0, false);
        assertEquals(240, r.height);
        assertEquals(480, r.width);
        assertEquals(0, r.left);
        assertEquals(0, r.top);
    }

    @Test
    public void positionPercentsRunFromEdgeToEdge() {
        HudPanelPlacement.Rect left = HudPanelPlacement.compute(1440, 480, 50, 100, 0, 0, false);
        HudPanelPlacement.Rect right = HudPanelPlacement.compute(1440, 480, 50, 100, 100, 100, false);
        assertEquals(0, left.left);
        assertEquals(0, left.top);
        assertEquals(1440 - 240, right.left);
        assertEquals(480 - 240, right.top);
    }

    @Test
    public void neverLargerThanTheHud() {
        HudPanelPlacement.Rect r = HudPanelPlacement.compute(1440, 480, 100, 300, 100, 100, true);
        assertEquals(480, r.height);
        assertEquals(1440, r.width);
        assertEquals(0, r.left);
        assertEquals(0, r.top);
    }

    @Test
    public void neverTinyAndPositionIsClamped() {
        HudPanelPlacement.Rect r = HudPanelPlacement.compute(1440, 480, 1, 100, 500, -20, false);
        assertEquals(HudPanelPlacement.MIN_SIZE_PX, r.height);
        assertTrue(r.left >= 0 && r.left + r.width <= 1440);
        assertEquals(0, r.top);
        HudPanelPlacement.Rect rr = HudPanelPlacement.compute(1440, 480, 1, 100, 500, -20, true);
        assertTrue(rr.left >= 0 && rr.left + rr.width <= 1440);
        assertEquals(0, rr.left);
    }
}
