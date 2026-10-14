package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HudNightDimmingTest {

    @Test
    public void dayIsTheSettingUntouched() {
        assertEquals(0.8f, HudNightDimming.opacity(80, 60, false), 0.0001f);
    }

    @Test
    public void nightMultipliesTheSetting() {
        assertEquals(0.48f, HudNightDimming.opacity(80, 60, true), 0.0001f);
    }

    @Test
    public void hundredPercentTurnsDimmingOff() {
        assertEquals(1f, HudNightDimming.opacity(100, 100, true), 0.0001f);
    }
}
