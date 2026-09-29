package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 信息条放哪几格：宽的放全，窄的按优先级去掉，关掉的子项不放。
 *
 * <p>算错的表现是格子叠在一起或者超出画面右边 —— 录进视频里就改不了了，所以钉住。</p>
 */
public class InfoBarLayoutTest {

    private static final int SURROUND = 2560;
    private static final int CABIN = 1280;

    private static boolean has(List<InfoBarLayout.Placed> placed, InfoBarLayout.Cell cell) {
        for (InfoBarLayout.Placed p : placed) {
            if (p.cell == cell) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aSurroundWideBarHoldsEveryCellInDisplayOrder() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND, InfoBar.Options.all());
        assertEquals(InfoBarLayout.Cell.values().length, placed.size());
        assertEquals(InfoBarLayout.Cell.TURN_LEFT, placed.get(0).cell);
        assertEquals(InfoBarLayout.MARGIN, placed.get(0).x);
        InfoBarLayout.Placed last = placed.get(placed.size() - 1);
        assertEquals(InfoBarLayout.Cell.POSITION, last.cell);
        assertTrue("最后一格不能超出右边留白", last.x + last.cell.width <= SURROUND - InfoBarLayout.MARGIN);
    }

    /** 窄的流：里程、经纬度这种优先级低的先走，车速和转向灯留到最后。 */
    @Test
    public void aNarrowBarDropsLowPriorityCellsFirst() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(CABIN, InfoBar.Options.all());
        assertTrue(placed.size() < InfoBarLayout.Cell.values().length);
        assertTrue(has(placed, InfoBarLayout.Cell.SPEED));
        assertTrue(has(placed, InfoBarLayout.Cell.TURN_LEFT));
        assertTrue(has(placed, InfoBarLayout.Cell.HAZARD));
        assertTrue(has(placed, InfoBarLayout.Cell.STEERING));
        assertFalse(has(placed, InfoBarLayout.Cell.POSITION));
        assertFalse(has(placed, InfoBarLayout.Cell.ODOMETER));
    }

    @Test
    public void switchedOffCellsAreNotPlaced() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND,
                new InfoBar.Options(false, false, false));
        assertFalse(has(placed, InfoBarLayout.Cell.SPEED));
        assertFalse(has(placed, InfoBarLayout.Cell.THROTTLE));
        assertFalse(has(placed, InfoBarLayout.Cell.BRAKE));
        assertFalse(has(placed, InfoBarLayout.Cell.STEERING));
        assertTrue(has(placed, InfoBarLayout.Cell.GEAR));
        assertEquals(InfoBarLayout.Cell.values().length - 4, placed.size());
    }

    @Test
    public void cellsNeverOverlapAndKeepTheGap() {
        for (int width : new int[]{SURROUND, 1920, CABIN, 640}) {
            List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(width, InfoBar.Options.all());
            for (int i = 1; i < placed.size(); i++) {
                InfoBarLayout.Placed prev = placed.get(i - 1);
                InfoBarLayout.Placed next = placed.get(i);
                assertTrue("width " + width + ": " + prev.cell + " -> " + next.cell,
                        next.x >= prev.x + prev.cell.width + InfoBarLayout.GAP);
            }
            for (InfoBarLayout.Placed p : placed) {
                assertTrue(p.x + p.cell.width <= width - InfoBarLayout.MARGIN);
            }
        }
    }

    @Test
    public void tooNarrowForAnythingPlacesNothing() {
        assertTrue(InfoBarLayout.fit(50, InfoBar.Options.all()).isEmpty());
    }
}
