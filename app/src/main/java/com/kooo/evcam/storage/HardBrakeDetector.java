package com.kooo.evcam.storage;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 急刹车：自动锁定的触发（2.10.8，项目拥有者 2026-10-08 定）。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>看最近 {@link #WINDOW_MS} 里的车速：从窗口开头到现在掉得够快 —— 踩着刹车时 ≥ {@link #DECEL_WITH_BRAKE_G}，
 *       没踩（自动紧急制动是车自己刹的）≥ {@link #DECEL_WITHOUT_BRAKE_G}。</li>
 *   <li>窗口开头的车速 ≥ {@link #MIN_START_KMH}：泊车、蠕行时的一顿不算。</li>
 *   <li>窗口里至少 {@link #MIN_SAMPLES} 个读数、一路在降（允许 {@link #NOISE_KMH} 的抖动）、
 *       至少 {@link #MIN_DROPS} 步真的在掉，而且没有一步快过 {@link #MAX_PLAUSIBLE_G}（车做不到，是读数坏了）：
 *       一个读数跳一下凑不出「持续减速」。</li>
 *   <li>一次刹车只算一次：触发后 {@link #COOLDOWN_MS} 内不再触发。</li>
 *   <li>车速读不到就当没有这个读数；隔得太久（{@link #STALE_MS}）的读数丢掉，免得断了一阵再接上时算出一个假的大减速。</li>
 * </ul>
 *
 * <p>正常的红灯停车大约 0.2–0.35 g、能量回收最多约 0.25 g，都够不着 0.5 g。车速每 200 ms 读一次。</p>
 *
 * <p>纯逻辑，时间由调用方传入，见 {@code HardBrakeDetectorTest}。</p>
 */
public final class HardBrakeDetector {

    static final double G = 9.81;
    static final double DECEL_WITH_BRAKE_G = 0.5;
    static final double DECEL_WITHOUT_BRAKE_G = 0.6;
    static final float MIN_START_KMH = 25f;
    static final long WINDOW_MS = 600L;
    static final int MIN_SAMPLES = 3;
    static final float NOISE_KMH = 0.5f;
    static final long COOLDOWN_MS = 15_000L;
    static final long STALE_MS = 1_000L;
    /** 两个相邻读数之间掉得比这还快，车做不到（干地急刹约 1 g）：是读数坏了，不算。 */
    static final double MAX_PLAUSIBLE_G = 1.5;
    /** 窗口里至少这么多次「真的在掉」（每次掉得多于 {@link #NOISE_KMH}）。 */
    static final int MIN_DROPS = 2;
    /** 只留这么久的读数。 */
    private static final long KEEP_MS = 2_000L;

    /** 一次急刹车。 */
    public static final class Event {
        /** 减速度，g。 */
        public final double g;
        /** 开始减速时的车速，km/h。 */
        public final float fromKmh;
        /** 踩着刹车没有（没踩 = 多半是自动紧急制动）。 */
        public final boolean braking;

        Event(double g, float fromKmh, boolean braking) {
            this.g = g;
            this.fromKmh = fromKmh;
            this.braking = braking;
        }
    }

    private static final class Sample {
        final long t;
        final float kmh;

        Sample(long t, float kmh) {
            this.t = t;
            this.kmh = kmh;
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastEventMs = Long.MIN_VALUE;

    /**
     * 来一组读数。
     *
     * @param nowMs    单调时钟
     * @param speedKmh 车速；null = 读不到
     * @param braking  刹车踩着没有（刹车开关或刹车深度）；null 当没踩
     * @return 这一刻算一次急刹车就返回它，否则 null
     */
    public Event update(long nowMs, Float speedKmh, Boolean braking) {
        if (speedKmh == null) {
            return null;
        }
        Sample last = samples.peekLast();
        if (last != null && (nowMs - last.t > STALE_MS || nowMs < last.t)) {
            samples.clear();
        }
        samples.addLast(new Sample(nowMs, speedKmh));
        while (!samples.isEmpty() && nowMs - samples.peekFirst().t > KEEP_MS) {
            samples.removeFirst();
        }
        if (lastEventMs != Long.MIN_VALUE && nowMs - lastEventMs < COOLDOWN_MS) {
            return null;
        }
        // 找最晚一个距现在已满一个窗口的读数做起点
        Sample start = null;
        for (Iterator<Sample> it = samples.descendingIterator(); it.hasNext(); ) {
            Sample s = it.next();
            if (nowMs - s.t >= WINDOW_MS) {
                start = s;
                break;
            }
        }
        if (start == null || start.kmh < MIN_START_KMH) {
            return null;
        }
        int count = 0;
        int drops = 0;
        Sample previous = null;
        for (Sample s : samples) {
            if (s.t < start.t) {
                continue;
            }
            if (previous != null) {
                if (s.kmh > previous.kmh + NOISE_KMH) {
                    return null;   // 中间又快了：不是一路在降
                }
                double dt = Math.max(1L, s.t - previous.t) / 1000.0;
                if ((previous.kmh - s.kmh) / 3.6 / dt / G > MAX_PLAUSIBLE_G) {
                    return null;   // 一步掉这么多车做不到：读数坏了
                }
                if (previous.kmh - s.kmh > NOISE_KMH) {
                    drops++;
                }
            }
            previous = s;
            count++;
        }
        if (count < MIN_SAMPLES || drops < MIN_DROPS) {
            return null;
        }
        double seconds = (nowMs - start.t) / 1000.0;
        double decel = (start.kmh - speedKmh) / 3.6 / seconds;
        double g = decel / G;
        boolean pressed = Boolean.TRUE.equals(braking);
        if (g >= (pressed ? DECEL_WITH_BRAKE_G : DECEL_WITHOUT_BRAKE_G)) {
            lastEventMs = nowMs;
            return new Event(g, start.kmh, pressed);
        }
        return null;
    }

    public void reset() {
        samples.clear();
        lastEventMs = Long.MIN_VALUE;
    }
}
