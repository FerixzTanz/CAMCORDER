package com.kooo.evcam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link CameraYield} 的规则：原厂功能拿着相机就让，占太久就当残留不再让。
 */
public class CameraYieldTest {

    @Test
    public void yieldsWhileOthersHoldRecently() {
        assertTrue(CameraYield.shouldYield(1_000L, 1_000L + 60_000L));
    }

    @Test
    public void doesNotYieldWhenNobodyHolds() {
        assertFalse(CameraYield.shouldYield(0L, 5_000L));
    }

    /** 相机服务里没清掉的残留占用（平台笔记 §3.1.1）不能让我们永远不开相机。 */
    @Test
    public void staleHoldStopsBlockingAfterTheCap() {
        assertFalse(CameraYield.shouldYield(1_000L, 1_000L + CameraYield.MAX_YIELD_MS));
    }
}
