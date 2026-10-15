package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link ScanCallbacks}：普查要听得见「定制功能」的值回调 —— 有的号只从那里给值。 */
public class ScanCallbacksTest {

    @Test
    public void valueCallbacksAreIdZoneValue() {
        assertTrue(ScanCallbacks.isValue(new Object[]{0x21020300, 0, 97}));
        assertTrue("浮点值", ScanCallbacks.isValue(new Object[]{0x21020300, 0x80000000, 0.5f}));
        assertFalse(ScanCallbacks.isValue(null));
        assertFalse("两个参数的是传感器", ScanCallbacks.isValue(new Object[]{0x00200200, 1}));
        assertFalse("值不是数", ScanCallbacks.isValue(new Object[]{1, 2, "x"}));
        assertFalse("号不是整数", ScanCallbacks.isValue(new Object[]{1L, 2, 3}));
    }

    /** 2.11.3 / 2.11.4 的普查丢掉的就是这一路。 */
    @Test
    public void theCustomizeValueCallbackIsHeard() {
        assertTrue(ScanCallbacks.isCustomizeValue("onCustomizeFunctionValueChanged", new Object[]{0x21020300, 0, 97.0f}));
    }

    @Test
    public void otherMethodsAreNotCustomizeValues() {
        assertFalse(ScanCallbacks.isCustomizeValue("onFunctionValueChanged", new Object[]{1, 0, 1}));
        assertFalse("形状不对的定制回调不当值记", ScanCallbacks.isCustomizeValue("onCustomizeFunctionValueChanged", new Object[]{1}));
        assertFalse(ScanCallbacks.isCustomizeValue("onCustomizeFunctionValueChanged", null));
    }

    @Test
    public void objectMethodsAreNotCarCallbacks() {
        assertTrue(ScanCallbacks.isObjectMethod("toString"));
        assertTrue(ScanCallbacks.isObjectMethod("hashCode"));
        assertTrue(ScanCallbacks.isObjectMethod("equals"));
        assertFalse(ScanCallbacks.isObjectMethod("onFunctionChanged"));
    }

    @Test
    public void shapeNamesTheMethodAndItsArgumentTypes() {
        assertEquals("onFunctionChanged(Integer)", ScanCallbacks.shape("onFunctionChanged", new Object[]{5}));
        assertEquals("onSupportedFunctionValueChanged(Integer,int[])",
                ScanCallbacks.shape("onSupportedFunctionValueChanged", new Object[]{5, new int[0]}));
        assertEquals("ping()", ScanCallbacks.shape("ping", null));
        assertEquals("x(null)", ScanCallbacks.shape("x", new Object[]{null}));
    }
}
