package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * {@link ClusterDisplayLocator} 的单元测试：只认 2560×536 的副屏，不认主屏、HUD，找不到不猜。
 */
public class ClusterDisplayLocatorTest {

    private static HudDisplayLocator.Candidate display(int id, int w, int h) {
        return new HudDisplayLocator.Candidate(id, w, h);
    }

    @Test
    public void findsTheClusterBySize() {
        assertEquals(2, ClusterDisplayLocator.find(Arrays.asList(
                display(0, 3200, 2000), display(2, 2560, 536), display(3, 1440, 480))));
    }

    @Test
    public void neverPicksTheMainScreenOrTheHud() {
        assertEquals(ClusterDisplayLocator.NONE, ClusterDisplayLocator.find(Arrays.asList(
                display(0, 2560, 536), display(3, 1440, 480))));
    }

    @Test
    public void noClusterMeansNone() {
        assertEquals(ClusterDisplayLocator.NONE, ClusterDisplayLocator.find(Collections.emptyList()));
        assertEquals(ClusterDisplayLocator.NONE, ClusterDisplayLocator.find(Arrays.asList(
                display(0, 3200, 2000), display(5, 2560, 400))));
    }
}
