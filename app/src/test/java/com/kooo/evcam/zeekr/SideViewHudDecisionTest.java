package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * HUD 不看车速门槛，主屏弹窗才看：同一份读数，门槛传 0 的（HUD）低速也出，传设置值的（主屏）等车速。
 * 服务按这个分别判两路（见 {@code SideViewPopupService.evaluate}）。
 */
public class SideViewHudDecisionTest {

    private static SideViewDecision.Input leftSignalAt(float speedKmh, int minSpeedKmh) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = 1;
        in.hazard = false;
        in.gear = "D";
        in.speedKmh = speedKmh;
        in.minSpeedKmh = minSpeedKmh;
        return in;
    }

    @Test
    public void hudShowsAtLowSpeedWhileTheScreenWaits() {
        assertEquals("主屏等车速", SideViewDecision.NONE, SideViewDecision.decide(leftSignalAt(10f, 30)));
        assertEquals("HUD 低速也出", LaneCycle.LEFT, SideViewDecision.decide(leftSignalAt(10f, 0)));
    }

    @Test
    public void hudShowsStandingStill() {
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(leftSignalAt(0f, 0)));
    }

    @Test
    public void bothStillYieldToTheCarsOwnViews() {
        SideViewDecision.Input reverse = leftSignalAt(10f, 0);
        reverse.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(reverse));
        SideViewDecision.Input stock360 = leftSignalAt(10f, 0);
        stock360.stock360Shown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(stock360));
    }

    /**
     * 低速打灯原厂必弹侧方小窗（主屏上）。HUD 不让它：让了就等于也有车速门槛（2.12.0 实车：30 以下 HUD 不出）。
     * 主屏弹窗照旧让，它和小窗在同一块屏上。
     */
    @Test
    public void hudIgnoresTheCarsOwnSidePopupButTheScreenYields() {
        SideViewDecision.Input hud = leftSignalAt(10f, 0);
        hud.stockPopupShown = true;
        hud.yieldToStockPopup = false;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(hud));
        assertEquals(false, SideViewDecision.factoryViewActive(hud));

        SideViewDecision.Input screen = leftSignalAt(50f, 30);
        screen.stockPopupShown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(screen));
        assertEquals(true, SideViewDecision.factoryViewActive(screen));
    }

    /** 不让小窗只是不让小窗：R 档、原厂 360、泊车辅助 HUD 照样让。 */
    @Test
    public void hudStillYieldsToReverseStock360AndParkAssist() {
        SideViewDecision.Input stock360 = leftSignalAt(10f, 0);
        stock360.yieldToStockPopup = false;
        stock360.stock360Shown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(stock360));
        SideViewDecision.Input park = leftSignalAt(10f, 0);
        park.yieldToStockPopup = false;
        park.parkAssistOn = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(park));
        SideViewDecision.Input reverse = leftSignalAt(10f, 0);
        reverse.yieldToStockPopup = false;
        reverse.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(reverse));
    }

    @Test
    public void bothStayQuietOnHazards() {
        SideViewDecision.Input hazard = leftSignalAt(50f, 0);
        hazard.hazard = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(hazard));
    }
}
