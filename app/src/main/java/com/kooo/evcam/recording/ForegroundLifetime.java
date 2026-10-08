package com.kooo.evcam.recording;

/**
 * 相机前台服务（camera 类型）什么时候该留着（CAMCORDER 2026-10-08）。
 *
 * <p>相机在后台只有这个前台服务在的时候才稳稳给 —— 实车上侧视弹窗自己并不起它，
 * 只是排队等它就绪；以前它只在「保持后台运行」开着、或在录的时候在，于是一停录像它就被停掉，
 * 侧视弹窗跟着没了相机、一直是空白（2026-10-08 实测：行驶中关掉录像，侧视就不出画面，开回录像又好了）。
 * 现在窗口占着相机也算一条。</p>
 *
 * <p>纯 Java，见 {@code ForegroundLifetimeTest}。</p>
 */
public final class ForegroundLifetime {

    private ForegroundLifetime() {
    }

    /**
     * 停录 / 主界面收尾调 {@code CameraForegroundService.stop()} 时：服务该不该留着
     * （留着只是把通知换回「在后台运行」，不停）。
     *
     * @param exited             用户点了退出：什么都不留
     * @param keepAlive          「保持后台运行」开着
     * @param windowHoldsCamera  侧视弹窗此刻占着相机
     */
    public static boolean staysUpAfterStop(boolean exited, boolean keepAlive, boolean windowHoldsCamera) {
        return !exited && (keepAlive || windowHoldsCamera);
    }
}
