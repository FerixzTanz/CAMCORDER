package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

/**
 * {@link HudPanelPlacement}：HUD 上画面的大小和位置。默认值要摆出 1440×480 上 355×355 px、左边留约 64 px 的样子。
 */
public class HudPanelPlacementTest {

    private static HudPanelPlacement.Rect defaults() {
        return HudPanelPlacement.compute(1440, 480,
                AppConfig.HUD_SIDE_DEFAULT_SIZE_PERCENT, AppConfig.HUD_SIDE_DEFAULT_SHAPE_PERCENT,
                AppConfig.HUD_SIDE_DEFAULT_LEFT_PERCENT, AppConfig.HUD_SIDE_DEFAULT_VERTICAL_PERCENT);
    }

    @Test
    public void defaultsAreAboutThreeFiftyFiveSquareWithSixtyFourOnTheLeft() {
        HudPanelPlacement.Rect r = defaults();
        assertEquals(355, r.height);
        assertEquals(355, r.width);
        assertEquals(65, r.left);
        assertEquals(63, r.top);
    }

    @Test
    public void shapeWidensTheWindow() {
        HudPanelPlacement.Rect r = HudPanelPlacement.compute(1440, 480, 50, 200, 0, 0);
        assertEquals(240, r.height);
        assertEquals(480, r.width);
        assertEquals(0, r.left);
        assertEquals(0, r.top);
    }

    @Test
    public void positionPercentsRunFromEdgeToEdge() {
        HudPanelPlacement.Rect left = HudPanelPlacement.compute(1440, 480, 50, 100, 0, 0);
        HudPanelPlacement.Rect right = HudPanelPlacement.compute(1440, 480, 50, 100, 100, 100);
        assertEquals(0, left.left);
        assertEquals(0, left.top);
        assertEquals(1440 - 240, right.left);
        assertEquals(480 - 240, right.top);
    }

    @Test
    public void neverLargerThanTheHud() {
        HudPanelPlacement.Rect r = HudPanelPlacement.compute(1440, 480, 100, 300, 100, 100);
        assertEquals(480, r.height);
        assertEquals(1440, r.width);
        assertEquals(0, r.left);
        assertEquals(0, r.top);
    }

    @Test
    public void neverTinyAndPositionIsClamped() {
        HudPanelPlacement.Rect r = HudPanelPlacement.compute(1440, 480, 1, 100, 500, -20);
        assertEquals(HudPanelPlacement.MIN_SIZE_PX, r.height);
        assertTrue(r.left >= 0 && r.left + r.width <= 1440);
        assertEquals(0, r.top);
    }
}
