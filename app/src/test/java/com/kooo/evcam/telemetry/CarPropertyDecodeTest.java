package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 车辆属性的原始值怎么变成快照里的值。
 *
 * <p>这些常量来自 {@code android.car}（VehicleGear / VehicleTurnSignal / VehicleLightState /
 * CruiseControlState / LaneCenteringAssistState），编译期没有那些类，数字是照文档抄的 ——
 * 抄错一位，信息条上左转灯就会画成右转，所以钉住。</p>
 */
public class CarPropertyDecodeTest {

    @Test
    public void gearsMapToTheirLetters() {
        assertEquals("N", CarPropertySource.gearLetter(0x1));
        assertEquals("R", CarPropertySource.gearLetter(0x2));
        assertEquals("P", CarPropertySource.gearLetter(0x4));
        assertEquals("D", CarPropertySource.gearLetter(0x8));
        assertEquals("前进档位算 D", "D", CarPropertySource.gearLetter(0x10));
        assertNull(CarPropertySource.gearLetter(0));
        assertNull(CarPropertySource.gearLetter(3));
    }

    /** VehicleTurnSignal 里 1 是右、2 是左 —— 和直觉相反，正是要钉住的地方。 */
    @Test
    public void turnSignalsAreNotSwapped() {
        assertEquals(Integer.valueOf(VehicleState.TURN_NONE), CarPropertySource.decodeTurnSignal(0));
        assertEquals(Integer.valueOf(VehicleState.TURN_RIGHT), CarPropertySource.decodeTurnSignal(1));
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), CarPropertySource.decodeTurnSignal(2));
        assertNull(CarPropertySource.decodeTurnSignal(9));
    }

    @Test
    public void lightStatesIncludeDaytimeRunningAsOn() {
        assertFalse(CarPropertySource.lightOn(0));
        assertTrue(CarPropertySource.lightOn(1));
        assertTrue(CarPropertySource.lightOn(2));
        assertNull(CarPropertySource.lightOn(7));
    }

    /** ACC 和车道居中的「已激活」码不一样；负数是错误码，算不知道。 */
    @Test
    public void adasStatesUseTheirOwnActivatedCode() {
        assertTrue(CarPropertySource.adasActive(2, CarPropertySource.ACC_ACTIVATED));
        assertFalse(CarPropertySource.adasActive(1, CarPropertySource.ACC_ACTIVATED));
        assertFalse(CarPropertySource.adasActive(2, CarPropertySource.LCC_ACTIVATED));
        assertTrue(CarPropertySource.adasActive(3, CarPropertySource.LCC_ACTIVATED));
        assertNull(CarPropertySource.adasActive(-1, CarPropertySource.ACC_ACTIVATED));
    }
}
