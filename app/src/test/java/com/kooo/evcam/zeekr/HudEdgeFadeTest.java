package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HudEdgeFadeTest {

    @Test
    public void zeroPercentIsAHardEdge() {
        assertEquals(0f, HudEdgeFade.widthPx(0, 400, 200), 0f);
    }

    @Test
    public void widthFollowsTheShorterSide() {
        assertEquals(30f, HudEdgeFade.widthPx(15, 400, 200), 0.001f);
        assertEquals(30f, HudEdgeFade.widthPx(15, 200, 400), 0.001f);
    }

    @Test
    public void neverMoreThanHalfTheShorterSide() {
        assertEquals(100f, HudEdgeFade.widthPx(90, 400, 200), 0f);
    }

    @Test
    public void emptyOrNegativeInputsGiveNoFade() {
        assertEquals(0f, HudEdgeFade.widthPx(-5, 400, 200), 0f);
        assertEquals(0f, HudEdgeFade.widthPx(15, 0, 200), 0f);
        assertEquals(0f, HudEdgeFade.widthPx(15, 400, 0), 0f);
    }
}
