package com.kooo.evcam.telemetry;

/**
 * 信号普查里，车机功能监听的回调长什么样（纯 Java，见 {@code ScanCallbacksTest}）。
 *
 * <p>监听是个动态代理，车机往里推的每个方法都走同一个入口，要自己按名字和参数认。
 * 带值的回调都是 (号, 区域, 值)；有两个方法名：普通的 {@code onFunctionValueChanged}，
 * 和「定制功能」的 {@code onCustomizeFunctionValueChanged}。</p>
 *
 * <p>定制功能那一路有的号<b>只</b>从这里给值、读函数一直是占位值（后备箱开度 0x21020300，Lab 0.10.0）。
 * 2.11.3 / 2.11.4 的普查只认前一个方法名，这一路整条没听 —— 盲区灯要是走这里，那两趟就一次也看不到。</p>
 */
final class ScanCallbacks {

    private ScanCallbacks() {
    }

    /** (号, 区域, 值)：带值的回调都是这个形状。 */
    static boolean isValue(Object[] args) {
        return args != null && args.length == 3
                && args[0] instanceof Integer && args[1] instanceof Integer && args[2] instanceof Number;
    }

    /** 定制功能的值回调。 */
    static boolean isCustomizeValue(String methodName, Object[] args) {
        return "onCustomizeFunctionValueChanged".equals(methodName) && isValue(args);
    }

    /** 代理上 Object 的三个方法：不是车机推来的。 */
    static boolean isObjectMethod(String methodName) {
        return "toString".equals(methodName) || "hashCode".equals(methodName) || "equals".equals(methodName);
    }

    /** 方法名加参数类型，比如 {@code onFunctionChanged(Integer)}：报告表头里认「车上有这么一路在推」。 */
    static String shape(String methodName, Object[] args) {
        StringBuilder sb = new StringBuilder(methodName).append('(');
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                sb.append(i > 0 ? "," : "").append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
        }
        return sb.append(')').toString();
    }
}
