package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 信息条放哪几格：宽的放全，窄的按优先级去掉；哪几格启用：非开发者只启用能用的。
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
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND);
        assertEquals(stripCells(), placed.size());
        assertEquals(InfoBarLayout.Cell.TURN_LEFT, placed.get(0).cell);
        assertEquals(InfoBarLayout.MARGIN, placed.get(0).x);
        InfoBarLayout.Placed last = placed.get(placed.size() - 1);
        assertEquals(InfoBarLayout.Cell.POSITION, last.cell);
        assertTrue("最后一格不能超出右边留白", last.x + last.cell.width <= SURROUND - InfoBarLayout.MARGIN);
    }

    /** 窄的流：里程、经纬度这种优先级低的先走，车速和转向灯留到最后。 */
    @Test
    public void aNarrowBarDropsLowPriorityCellsFirst() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(CABIN);
        assertTrue(placed.size() < stripCells());
        assertTrue(has(placed, InfoBarLayout.Cell.SPEED));
        assertTrue(has(placed, InfoBarLayout.Cell.TURN_LEFT));
        assertTrue(has(placed, InfoBarLayout.Cell.HAZARD));
        assertTrue(has(placed, InfoBarLayout.Cell.STEERING));
        assertFalse(has(placed, InfoBarLayout.Cell.POSITION));
        assertFalse(has(placed, InfoBarLayout.Cell.ODOMETER));
    }

    /**
     * 格子永远全放；非开发者只启用信号都能用的格，其余画斜杠；开发者激活后全部启用。
     * 方向盘、油门刹车、日行灯是先用着的（细节等 Lab），用户定的照样启用。
     */
    @Test
    public void everyCellIsPlacedButOnlyVerifiedOnesAreLiveByDefault() {
        assertEquals(stripCells(), InfoBarLayout.fit(SURROUND).size());
        InfoBar.Options byDefault = InfoBar.Options.usable();
        for (InfoBarLayout.Cell cell : new InfoBarLayout.Cell[]{
                InfoBarLayout.Cell.TURN_LEFT, InfoBarLayout.Cell.HAZARD, InfoBarLayout.Cell.TURN_RIGHT,
                InfoBarLayout.Cell.STEERING, InfoBarLayout.Cell.GEAR, InfoBarLayout.Cell.PEDALS,
                InfoBarLayout.Cell.SPEED, InfoBarLayout.Cell.STOCK_360, InfoBarLayout.Cell.CABIN,
                InfoBarLayout.Cell.AUTO_HOLD, InfoBarLayout.Cell.DRL, InfoBarLayout.Cell.BEAMS,
                InfoBarLayout.Cell.REAR_LAMPS, InfoBarLayout.Cell.ODOMETER,
                InfoBarLayout.Cell.POSITION}) {
            assertTrue(cell.name(), InfoBarLayout.live(cell, byDefault));
        }
        for (InfoBarLayout.Cell cell : new InfoBarLayout.Cell[]{
                InfoBarLayout.Cell.HANDS, InfoBarLayout.Cell.ACC, InfoBarLayout.Cell.LCC,
                InfoBarLayout.Cell.ASSIST, InfoBarLayout.Cell.HORN}) {
            assertFalse(cell.name(), InfoBarLayout.live(cell, byDefault));
        }
        for (InfoBarLayout.Cell cell : InfoBarLayout.Cell.values()) {
            assertTrue(cell.name(), InfoBarLayout.live(cell, InfoBar.Options.all()));
        }
    }

    private static int stripCells() {
        int n = 0;
        for (InfoBarLayout.Cell cell : InfoBarLayout.Cell.values()) {
            if (cell.onStrip) {
                n++;
            }
        }
        return n;
    }

    /** 手扶方向盘挨着驾驶辅助；喇叭不上信息条。 */
    @Test
    public void handsSitsNextToTheAssistBadgesAndTheHornStaysOffTheStrip() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND);
        int hands = -1;
        int assist = -1;
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).cell == InfoBarLayout.Cell.HANDS) {
                hands = i;
            }
            if (placed.get(i).cell == InfoBarLayout.Cell.ASSIST) {
                assist = i;
            }
        }
        assertEquals(assist - 1, hands);
        assertFalse(has(placed, InfoBarLayout.Cell.HORN));
    }

    /** 车辆状态面板：点名的那几格都在，互不重叠，都在面板里面。 */
    @Test
    public void thePanelHoldsTheRequestedCellsWithoutOverlap() {
        InfoBarLayout.Arrangement panel = InfoBarLayout.panel();
        for (InfoBarLayout.Cell cell : new InfoBarLayout.Cell[]{InfoBarLayout.Cell.CABIN, InfoBarLayout.Cell.PEDALS,
                InfoBarLayout.Cell.ASSIST, InfoBarLayout.Cell.HORN}) {
            assertTrue(cell.name(), has(panel.cells, cell));
        }
        for (InfoBarLayout.Placed a : panel.cells) {
            assertTrue(a.x >= 0 && a.x + a.cell.width <= panel.width);
            assertTrue(a.y >= 0 && a.y + InfoBar.HEIGHT <= panel.height);
            for (InfoBarLayout.Placed b : panel.cells) {
                if (a != b && a.y == b.y) {
                    assertTrue(a.x + a.cell.width <= b.x || b.x + b.cell.width <= a.x);
                }
            }
        }
    }

    @Test
    public void cellsNeverOverlapAndKeepTheGap() {
        for (int width : new int[]{SURROUND, 1920, CABIN, 640}) {
            List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(width);
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
        assertTrue(InfoBarLayout.fit(50).isEmpty());
    }
}
