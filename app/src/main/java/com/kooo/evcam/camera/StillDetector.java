package com.kooo.evcam.camera;

/**
 * 车停稳了没有：录像停着的时候降到 {@link CodecVideoRecorder#STILL_FPS}、码率跟着降，开起来立刻回去（2.10.8）。
 *
 * <p>车速低于 {@link #STILL_KMH} 连续 {@link #STILL_AFTER_MS} 才算停稳 —— 红灯前那几秒的蠕行、
 * 一个读数抖到 0 都不算。一动就算开起来，不等：开起来那一刻正是要看清楚的时候。
 * 车速读不到（车辆信号没起来）按「在开」，宁可多录几帧。</p>
 *
 * <p>纯逻辑，时间由调用方传入，见 {@code StillDetectorTest}。</p>
 */
public final class StillDetector {

    /** 低于这个车速（km/h）算停着。 */
    static final float STILL_KMH = 1f;
    /** 停着这么久才算停稳。 */
    static final long STILL_AFTER_MS = 5_000L;

    private long slowSinceMs = -1L;
    private boolean still;

    /**
     * 来一个车速读数。
     *
     * @param nowMs    单调时钟
     * @param speedKmh 车速；null = 读不到
     * @return 此刻算不算停稳
     */
    public boolean update(long nowMs, Float speedKmh) {
        if (speedKmh == null || speedKmh >= STILL_KMH) {
            slowSinceMs = -1L;
            still = false;
            return false;
        }
        if (slowSinceMs < 0) {
            slowSinceMs = nowMs;
        }
        still = nowMs - slowSinceMs >= STILL_AFTER_MS;
        return still;
    }

    public boolean isStill() {
        return still;
    }

    public void reset() {
        slowSinceMs = -1L;
        still = false;
    }
}
