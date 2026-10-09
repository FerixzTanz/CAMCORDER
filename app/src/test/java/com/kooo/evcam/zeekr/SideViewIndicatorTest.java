package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * {@link SideViewDecision#indicatorLit}：侧视窗角上的箭头跟着转向指示显示（左 0 / 1、右 0 / 2、双闪 0 / 3）一亮一暗。
 */
public class SideViewIndicatorTest {

    @Test
    public void leftWindowFollowsTheLeftLamp() {
        assertEquals(Boolean.TRUE, SideViewDecision.indicatorLit(1, LaneCycle.LEFT));
        assertEquals(Boolean.FALSE, SideViewDecision.indicatorLit(0, LaneCycle.LEFT));
        assertEquals("右灯不点亮左边的箭头", Boolean.FALSE, SideViewDecision.indicatorLit(2, LaneCycle.LEFT));
    }

    @Test
    public void rightWindowFollowsTheRightLamp() {
        assertEquals(Boolean.TRUE, SideViewDecision.indicatorLit(2, LaneCycle.RIGHT));
        assertEquals(Boolean.FALSE, SideViewDecision.indicatorLit(0, LaneCycle.RIGHT));
        assertEquals(Boolean.FALSE, SideViewDecision.indicatorLit(1, LaneCycle.RIGHT));
    }

    @Test
    public void hazardLightsBothSides() {
        assertEquals(Boolean.TRUE, SideViewDecision.indicatorLit(3, LaneCycle.LEFT));
        assertEquals(Boolean.TRUE, SideViewDecision.indicatorLit(3, LaneCycle.RIGHT));
    }

    @Test
    public void unknownMeansSteady() {
        assertNull(SideViewDecision.indicatorLit(null, LaneCycle.LEFT));
        assertNull("不是左右两边的窗不画箭头", SideViewDecision.indicatorLit(1, LaneCycle.FRONT));
    }
}
