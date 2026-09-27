package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link CameraTaken} 的规则：别的程序占着相机时只慢慢试，否则按正常退避。
 */
public class CameraTakenTest {

    @Test
    public void retriesSlowlyWhileOthersHold() {
        assertEquals(CameraTaken.RETRY_WHILE_HELD_MS, CameraTaken.reconnectDelayMs(true, 500L));
    }

    @Test
    public void keepsTheBackoffWhenNobodyHolds() {
        assertEquals(500L, CameraTaken.reconnectDelayMs(false, 500L));
    }
}
