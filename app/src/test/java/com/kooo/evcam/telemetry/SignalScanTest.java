package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SignalScan#matchesHint}：报告里先列出名字像盲区 / 变道 / 开门预警的常量。 */
public class SignalScanTest {

    @Test
    public void blindSpotLikeNamesAreHinted() {
        assertTrue(SignalScan.matchesHint("IAdas.ADAS_FUNC_BSD_WARNING"));
        assertTrue(SignalScan.matchesHint("ISetting.SETTING_FUNC_LCA_STATUS"));
        assertTrue(SignalScan.matchesHint("x.blind_spot"));
        assertTrue(SignalScan.matchesHint("IBcm.BCM_FUNC_DOW_LEFT"));
    }

    @Test
    public void otherNamesAreNot() {
        assertFalse(SignalScan.matchesHint("IBcm.BCM_FUNC_DOOR"));
        assertFalse("WINDOW 里的 DOW 不算", SignalScan.matchesHint("IBcm.BCM_FUNC_WINDOW_POS"));
        assertFalse(SignalScan.matchesHint(null));
    }

    @Test
    public void sensorEventTypeShape() {
        assertTrue("档位", SignalScan.isSensorEventType(0x00200200));
        assertTrue("安全带", SignalScan.isSensorEventType(0x00201200));
        assertFalse("车速是浮点，不挂", SignalScan.isSensorEventType(0x00100100));
        assertFalse("电量是浮点，不挂", SignalScan.isSensorEventType(0x00404000));
        assertFalse("档位的取值（末字节不是 0）", SignalScan.isSensorEventType(0x00200201));
        assertFalse("功能号", SignalScan.isSensorEventType(0x28070200));
        assertFalse(SignalScan.isSensorEventType(0x00200000));
    }

    @Test
    public void placeholdersAreNotPolled() {
        assertTrue(SignalScan.isPlaceholder("255"));
        assertTrue(SignalScan.isPlaceholder("-65535"));
        assertTrue(SignalScan.isPlaceholder("-1.0"));
        assertFalse(SignalScan.isPlaceholder("0"));
        assertFalse(SignalScan.isPlaceholder("1"));
        assertFalse(SignalScan.isPlaceholder("abc"));
    }

    @Test
    public void blindSpotNamesComeFirst() {
        assertTrue(SignalScan.isBlindSpotName("IADAS.SETTING_FUNC_BLIND_SPOT_DETECTION_WARNING"));
        assertTrue(SignalScan.isBlindSpotName("IVehicle.SETTING_FUNC_BSD_SW_ONOFF_WARN_TYPE"));
        assertFalse(SignalScan.isBlindSpotName("IADAS.SETTING_FUNC_DOW_SYS_STS"));
        assertFalse(SignalScan.isBlindSpotName(null));
    }
}
