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
    public void sensorTypeShape() {
        assertTrue("档位", SignalScan.isSensorType(0x00200200));
        assertTrue("车速", SignalScan.isSensorType(0x00100100));
        assertFalse("档位的取值（末字节不是 0）", SignalScan.isSensorType(0x00200201));
        assertFalse("功能号", SignalScan.isSensorType(0x28070200));
        assertFalse(SignalScan.isSensorType(0x00000100));
    }
}
