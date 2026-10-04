package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 信息条放哪几格：系统信息里勾的上，有图标的带出整格，没图标的是文字格；放不下的截掉。
 *
 * <p>算错的表现是格子叠在一起或者超出画面右边 —— 录进视频里就改不了了，所以钉住。</p>
 */
public class InfoBarLayoutTest {

    private static final int SURROUND = 2560;
    private static final int CABIN = 1280;

    private static Set<String> pick(Object... items) {
        Set<String> names = new LinkedHashSet<>();
        for (Object item : items) {
            names.add(item instanceof Signal ? ((Signal) item).name() : String.valueOf(item));
        }
        return names;
    }

    private static Set<String> everything() {
        Set<String> names = new LinkedHashSet<>();
        for (Signal s : Signal.values()) {
            names.add(s.name());
        }
        names.add(InfoBarLayout.POSITION_ITEM);
        return names;
    }

    private static boolean has(List<InfoBarLayout.Placed> placed, InfoBarLayout.Cell cell) {
        return indexOf(placed, cell) >= 0;
    }

    private static int indexOf(List<InfoBarLayout.Placed> placed, InfoBarLayout.Cell cell) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).cell == cell) {
                return i;
            }
        }
        return -1;
    }

    private static List<InfoBarLayout.Cell> cellsOf(List<InfoBarLayout.Placed> placed) {
        List<InfoBarLayout.Cell> cells = new ArrayList<>();
        for (InfoBarLayout.Placed p : placed) {
            cells.add(p.cell);
        }
        return cells;
    }

    /** 默认勾的就是 2.0.10 在用的那些：环视一条放得下，按原来的顺序；驾驶辅助不在默认里。 */
    @Test
    public void theDefaultSelectionIsWhatWasInUseAndFitsTheSurround() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND, InfoBarLayout.defaultSelection());
        assertEquals(InfoBarLayout.Cell.TURN, placed.get(0).cell);
        assertEquals(InfoBarLayout.MARGIN, placed.get(0).x);
        InfoBarLayout.Placed last = placed.get(placed.size() - 1);
        assertEquals(InfoBarLayout.Cell.POSITION, last.cell);
        assertTrue(last.x + last.width <= SURROUND - InfoBarLayout.MARGIN);
        for (InfoBarLayout.Cell cell : InfoBarLayout.Cell.values()) {
            boolean expected = cell.onStrip && cell != InfoBarLayout.Cell.ASSIST;
            assertEquals(cell.name(), expected, has(placed, cell));
        }
        for (InfoBarLayout.Placed p : placed) {
            assertNull("默认没有文字格", p.text);
        }
    }

    /** 一格管几个信号的，勾任意一个就带出整格。 */
    @Test
    public void anyOneSignalOfAnIconBringsTheWholeIcon() {
        assertEquals("前视也画近远光：勾近光带出前视和近光远光那一格",
                Arrays.asList(InfoBarLayout.Cell.DRL, InfoBarLayout.Cell.BEAMS),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.LOW_BEAM))));
        assertEquals("闪远光在信息条上算进前视和近光远光那一格（单独那格只在面板上）",
                Arrays.asList(InfoBarLayout.Cell.DRL, InfoBarLayout.Cell.BEAMS),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.HIGH_BEAM_FLASH))));
        assertEquals("转向灯、双闪合成一格（双闪单独那格只在面板上）",
                Collections.singletonList(InfoBarLayout.Cell.TURN),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.TURN_RIGHT))));
        assertEquals(Collections.singletonList(InfoBarLayout.Cell.TURN),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.INDICATOR))));
        assertEquals(Collections.singletonList(InfoBarLayout.Cell.ASSIST),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.AEB))));
        assertEquals(Collections.singletonList(InfoBarLayout.Cell.POSITION),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(InfoBarLayout.POSITION_ITEM))));
    }

    /** 转向灯、双闪合成一格（项目所有者 2026-10-04）：默认照样勾着三个转向信号；双闪那格只在面板上。 */
    @Test
    public void theTurnCellCarriesTheTurnSignalsAndTheHazardCellStaysOnThePanel() {
        List<Signal> turn = Arrays.asList(Signal.INDICATOR, Signal.TURN_LEFT, Signal.TURN_RIGHT);
        assertEquals(turn, InfoBarLayout.Cell.TURN.signals());
        assertEquals(turn, InfoBarLayout.Cell.HAZARD.signals());
        assertTrue(InfoBarLayout.Cell.TURN.onStrip);
        assertFalse(InfoBarLayout.Cell.HAZARD.onStrip);
        Set<String> defaults = InfoBarLayout.defaultSelection();
        for (Signal s : turn) {
            assertTrue(s.name(), defaults.contains(s.name()));
            assertTrue(s.name(), InfoBarLayout.hasIcon(s));
        }
    }

    /**
     * 前视画日行灯，也画近远光（项目所有者 2026-10-04）：近光、远光、闪远光也列在前视那一格，勾哪个都带出前视；
     * 近光远光那一格先留着；只勾日行灯 / 前位置灯不带出近光远光那一格。前视和后视一样宽。
     */
    @Test
    public void theFrontViewCarriesTheBeamsAndTheBeamsCellStays() {
        assertEquals(Arrays.asList(Signal.DRL, Signal.FRONT_POSITION_LAMP, Signal.LOW_BEAM, Signal.HIGH_BEAM,
                Signal.HIGH_BEAM_FLASH), InfoBarLayout.Cell.DRL.signals());
        assertEquals(Arrays.asList(Signal.LOW_BEAM, Signal.HIGH_BEAM, Signal.HIGH_BEAM_FLASH),
                InfoBarLayout.Cell.BEAMS.signals());
        assertTrue(InfoBarLayout.Cell.BEAMS.onStrip);
        assertEquals(106, InfoBarLayout.Cell.DRL.width);
        assertEquals(InfoBarLayout.Cell.REAR_LAMPS.width, InfoBarLayout.Cell.DRL.width);
        assertEquals(Arrays.asList(InfoBarLayout.Cell.DRL, InfoBarLayout.Cell.BEAMS),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.HIGH_BEAM))));
        assertEquals(Collections.singletonList(InfoBarLayout.Cell.DRL),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.DRL))));
        assertEquals(Collections.singletonList(InfoBarLayout.Cell.DRL),
                cellsOf(InfoBarLayout.fit(SURROUND, pick(Signal.FRONT_POSITION_LAMP))));
    }

    /** 没有图标的信号画成文字格，排在图标格后面，按信号表的顺序。 */
    @Test
    public void signalsWithoutAnIconBecomeTextCellsAfterTheIcons() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND,
                pick(Signal.TEMP_OUTSIDE, Signal.SPEED, Signal.BATTERY));
        assertEquals(3, placed.size());
        assertEquals(InfoBarLayout.Cell.SPEED, placed.get(0).cell);
        assertEquals(Signal.BATTERY, placed.get(1).text);
        assertEquals(Signal.TEMP_OUTSIDE, placed.get(2).text);
        assertEquals(InfoBarLayout.TEXT_WIDTH, placed.get(1).width);
        assertFalse(InfoBarLayout.hasIcon(Signal.BATTERY));
        assertTrue(InfoBarLayout.hasIcon(Signal.SPEED));
    }

    /** 没勾的不放；什么都不勾就是空的。 */
    @Test
    public void nothingTickedPlacesNothing() {
        assertTrue(InfoBarLayout.fit(SURROUND, new HashSet<String>()).isEmpty());
        assertFalse(has(InfoBarLayout.fit(SURROUND, pick(Signal.SPEED)), InfoBarLayout.Cell.GEAR));
    }

    /** 放不下的截掉，不再按优先级挑：窄的那一路放的是宽的那一路的前几格。 */
    @Test
    public void whatDoesNotFitIsCutOffInOrder() {
        for (Set<String> selection : Arrays.asList(InfoBarLayout.defaultSelection(), everything())) {
            List<InfoBarLayout.Placed> wide = InfoBarLayout.fit(SURROUND * 4, selection);
            List<InfoBarLayout.Placed> narrow = InfoBarLayout.fit(CABIN, selection);
            assertTrue(narrow.size() < wide.size());
            for (int i = 0; i < narrow.size(); i++) {
                assertEquals(wide.get(i).cell, narrow.get(i).cell);
                assertEquals(wide.get(i).text, narrow.get(i).text);
                assertEquals(wide.get(i).x, narrow.get(i).x);
            }
        }
    }

    /** 哨兵模式紧挨在原厂 360 右边（项目所有者 2026-10-03）。 */
    @Test
    public void sentrySitsRightOfTheStock360() {
        List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(SURROUND, InfoBarLayout.defaultSelection());
        assertEquals(indexOf(placed, InfoBarLayout.Cell.STOCK_360) + 1, indexOf(placed, InfoBarLayout.Cell.SENTRY));
    }

    /** 车辆状态面板：点名的那几格都在，互不重叠，都在面板里面。 */
    @Test
    public void thePanelHoldsTheRequestedCellsWithoutOverlap() {
        InfoBarLayout.Arrangement panel = InfoBarLayout.panel();
        assertEquals("用户 2026-10-03 定：只留喇叭、闪远光、双闪", 3, panel.cells.size());
        for (InfoBarLayout.Cell cell : new InfoBarLayout.Cell[]{InfoBarLayout.Cell.HORN, InfoBarLayout.Cell.FLASH,
                InfoBarLayout.Cell.HAZARD}) {
            assertTrue(cell.name(), has(panel.cells, cell));
        }
        for (InfoBarLayout.Placed a : panel.cells) {
            assertTrue(a.x >= 0 && a.x + a.width <= panel.width);
            assertTrue(a.y >= 0 && a.y + InfoBar.HEIGHT <= panel.height);
            for (InfoBarLayout.Placed b : panel.cells) {
                if (a != b && a.y == b.y) {
                    assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x);
                }
            }
        }
    }

    @Test
    public void cellsNeverOverlapAndKeepTheGap() {
        for (Set<String> selection : Arrays.asList(InfoBarLayout.defaultSelection(), everything())) {
            for (int width : new int[]{SURROUND, 1920, CABIN, 640}) {
                List<InfoBarLayout.Placed> placed = InfoBarLayout.fit(width, selection);
                for (int i = 1; i < placed.size(); i++) {
                    InfoBarLayout.Placed prev = placed.get(i - 1);
                    InfoBarLayout.Placed next = placed.get(i);
                    assertTrue("width " + width + " @" + i, next.x >= prev.x + prev.width + InfoBarLayout.GAP);
                }
                for (InfoBarLayout.Placed p : placed) {
                    assertTrue(p.x + p.width <= width - InfoBarLayout.MARGIN);
                }
            }
        }
    }

    @Test
    public void tooNarrowForAnythingPlacesNothing() {
        assertTrue(InfoBarLayout.fit(50, InfoBarLayout.defaultSelection()).isEmpty());
    }
}
