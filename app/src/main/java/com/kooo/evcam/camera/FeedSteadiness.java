package com.kooo.evcam.camera;

/**
 * 环视这一路是不是真的在出画面（CAMCORDER 2026-10-08）。
 *
 * <p>以前问的是「两秒内来过一帧没有」。相机服务卡死的那种状态里，环视照样一秒一帧地往外爬
 * （导出时 0.8 fps，健康时 28–30 fps），于是一直答「有」：10-07 晚到 10-08 上午六次
 * 「环视正常了，开录」，每一次随后都是 START_FAILED（10-08 的两次日志里还留着
 * {@code Codec frames still unstable} 十轮、会话没配上）。这里改问「两秒内来了足够多帧没有」。</p>
 *
 * <p>只留最近 {@link #MIN_FRAMES} 帧的时刻：第 {@link #MIN_FRAMES} 新的那一帧
 * 还在窗口里，就说明窗口里至少有这么多帧。健康的 30 fps 要 0.2 秒攒够，
 * 爬着的 0.8 fps 要七秒多 —— 阈值落在两者中间，哪边都有很大余量。</p>
 *
 * <p>另外记「连着稳了多久」：开了又断、断了又开的相机每次都会稳上一两秒，
 * 那不算好了，要连着稳 {@link #RECOVERED_AFTER_MS} 才算（重连次数、冷却轮数才清零）。</p>
 *
 * <p>纯 Java，时刻由调用方给，见 {@code FeedSteadinessTest}。</p>
 */
public final class FeedSteadiness {

    /** 看多长的窗口。 */
    public static final long WINDOW_MS = 2_000L;
    /** 窗口里至少要这么多帧（≥3 fps）才算在出画面。 */
    public static final int MIN_FRAMES = 6;
    /** 连着稳这么久，才算相机真的好了。 */
    public static final long RECOVERED_AFTER_MS = 10_000L;

    private final long[] recent = new long[MIN_FRAMES];
    private int next;
    private int count;
    /** 这一段「稳」是从什么时候连着稳下来的；-1 = 此刻不稳。 */
    private long steadySinceMs = -1L;

    /** 记一帧（capture 完成）。 */
    public synchronized void onFrame(long nowMs) {
        recent[next] = nowMs;
        next = (next + 1) % MIN_FRAMES;
        if (count < MIN_FRAMES) {
            count++;
        }
        if (steadyAt(nowMs)) {
            if (steadySinceMs < 0) {
                steadySinceMs = nowMs;
            }
        } else {
            steadySinceMs = -1L;
        }
    }

    /** 此刻窗口里是不是至少有 {@link #MIN_FRAMES} 帧。 */
    public synchronized boolean steady(long nowMs) {
        return steadyAt(nowMs);
    }

    /** 连着稳了多久（毫秒）；此刻不稳返回 0。中间断过（窗口里不够帧）就重新算。 */
    public synchronized long steadyForMs(long nowMs) {
        if (steadySinceMs < 0 || !steadyAt(nowMs)) {
            return 0L;
        }
        return nowMs - steadySinceMs;
    }

    /** 连着稳满 {@link #RECOVERED_AFTER_MS} 了没有。 */
    public synchronized boolean recovered(long nowMs) {
        return steadyForMs(nowMs) >= RECOVERED_AFTER_MS;
    }

    /** 新的一轮（相机重开）：旧的帧不再算数。 */
    public synchronized void reset() {
        count = 0;
        next = 0;
        steadySinceMs = -1L;
    }

    private boolean steadyAt(long nowMs) {
        if (count < MIN_FRAMES) {
            return false;
        }
        // 环满了，next 指着最老的那一帧
        return nowMs - recent[next] < WINDOW_MS;
    }
}
