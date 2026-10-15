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
     * 低速打灯原厂一弹，侧方小窗、360、泊车辅助三个信号同一毫秒一起变 1（2026-10-11 普查：26 km/h 右灯、
     * 10 km/h 左灯）。三样都只在主屏上：HUD 一样不让，让了就等于也有车速门槛
     * （2.12.0 实车：30 以下 HUD 不出；2.12.1 只放开了小窗，2026-10-15 实车 360 还在拦）。
     * 主屏弹窗照旧让，它和它们在同一块屏上。
     */
    @Test
    public void hudIgnoresTheCarsOwnScreenViewsButTheScreenYields() {
        SideViewDecision.Input hud = leftSignalAt(10f, 0);
        hud.stockPopupShown = true;
        hud.stock360Shown = true;
        hud.parkAssistOn = true;
        hud.yieldToScreenViews = false;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(hud));
        assertEquals(false, SideViewDecision.factoryViewActive(hud));

        SideViewDecision.Input screen = leftSignalAt(50f, 30);
        screen.stockPopupShown = true;
        screen.stock360Shown = true;
        screen.parkAssistOn = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(screen));
        assertEquals(true, SideViewDecision.factoryViewActive(screen));
    }

    /** 每一样单独亮也一样：HUD 不让，主屏让。 */
    @Test
    public void eachScreenViewAloneDoesNotStopTheHud() {
        SideViewDecision.Input stock360 = leftSignalAt(10f, 0);
        stock360.yieldToScreenViews = false;
        stock360.stock360Shown = true;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(stock360));
        SideViewDecision.Input park = leftSignalAt(10f, 0);
        park.yieldToScreenViews = false;
        park.parkAssistOn = true;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(park));
        SideViewDecision.Input popup = leftSignalAt(10f, 0);
        popup.yieldToScreenViews = false;
        popup.stockPopupShown = true;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(popup));
    }

    /** HUD 只让 R 档：倒车时原厂的画面是主角。 */
    @Test
    public void hudStillYieldsToReverse() {
        SideViewDecision.Input reverse = leftSignalAt(10f, 0);
        reverse.yieldToScreenViews = false;
        reverse.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(reverse));
        assertEquals(true, SideViewDecision.factoryViewActive(reverse));
    }

    @Test
    public void bothStayQuietOnHazards() {
        SideViewDecision.Input hazard = leftSignalAt(50f, 0);
        hazard.hazard = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(hazard));
    }
}
