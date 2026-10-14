package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link HudDisplayLocator}：7X 上哪一块屏是 HUD（诊断报告：主屏 id 0 是 3200x2000，id 2 是 2560x536，id 3 是 1440x480）。
 */
public class HudDisplayLocatorTest {

    private static List<HudDisplayLocator.Candidate> displays(int[]... rows) {
        List<HudDisplayLocator.Candidate> list = new ArrayList<>();
        for (int[] r : rows) {
            list.add(new HudDisplayLocator.Candidate(r[0], r[1], r[2]));
        }
        return list;
    }

    @Test
    public void picksTheHudOnTheSevenX() {
        assertEquals(3, HudDisplayLocator.find(displays(
                new int[] {0, 3200, 2000}, new int[] {2, 2560, 536}, new int[] {3, 1440, 480})));
    }

    @Test
    public void picksTheSizeEvenIfTheIdIsDifferent() {
        assertEquals(5, HudDisplayLocator.find(displays(
                new int[] {0, 3200, 2000}, new int[] {2, 2560, 536}, new int[] {5, 1440, 480})));
        assertEquals(5, HudDisplayLocator.findBySize(displays(
                new int[] {0, 3200, 2000}, new int[] {5, 1440, 480})));
    }

    @Test
    public void neverPicksTheMainScreen() {
        assertEquals(HudDisplayLocator.NONE, HudDisplayLocator.find(displays(new int[] {0, 1440, 480})));
    }

    @Test
    public void neverPicksTheInstrumentCluster() {
        assertEquals("没有 HUD 就是没有，不往仪表盘上画",
                HudDisplayLocator.NONE, HudDisplayLocator.find(displays(
                        new int[] {0, 3200, 2000}, new int[] {2, 2560, 536})));
    }

    @Test
    public void fallsBackToIdThreeWhenTheSizeIsOff() {
        assertEquals(3, HudDisplayLocator.find(displays(
                new int[] {0, 3200, 2000}, new int[] {2, 2560, 536}, new int[] {3, 1920, 720})));
        assertEquals(HudDisplayLocator.NONE, HudDisplayLocator.findBySize(displays(
                new int[] {0, 3200, 2000}, new int[] {3, 1920, 720})));
    }

    @Test
    public void idThreeFallbackSkipsTheClusterSize() {
        assertEquals(HudDisplayLocator.NONE, HudDisplayLocator.find(displays(
                new int[] {0, 3200, 2000}, new int[] {3, 2560, 536})));
    }

    @Test
    public void noExtraScreenMeansNone() {
        assertEquals(HudDisplayLocator.NONE, HudDisplayLocator.find(displays(new int[] {0, 3200, 2000})));
        assertEquals(HudDisplayLocator.NONE, HudDisplayLocator.find(new ArrayList<HudDisplayLocator.Candidate>()));
    }
}
