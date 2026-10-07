package com.kooo.evcam.recording;

/**
 * 录像跟着车走：换出 P 挡就开录，人下了车就停录 —— 不看屏幕。
 *
 * <h3>为什么不看屏幕（CAMCORDER，用户 2026-10-07）</h3>
 *
 * <p>熄屏那套（{@link ScreenOffPlan}）拿「熄屏」近似「人走了」。开着哨兵模式时这个近似靠不住：
 * 用户实车上关着「熄屏持续录制」、开着「启动自动录制」，下车走了几个小时录像还在录 ——
 * 哨兵模式让车机看起来又亮了，亮屏接回把录像又开了起来。挡位、主驾座位、哨兵模式都是车直接报的。</p>
 *
 * <h3>规则（用户 2026-10-07 定）</h3>
 *
 * <ul>
 *   <li><b>开</b>：挡位从 P 换到别的挡（D / R / N）。用户这一趟手动停过的不开。</li>
 *   <li><b>人走了</b>：在 P 挡，并且<b>主驾座位</b>空着满 {@link #LEAVE_AFTER_MS}，或者哨兵模式变成布防（2，锁了车）。
 *       只看主驾：副驾还坐着人也算走了。30 秒是给座椅传感器的 —— 用力踩踏板时人离开坐垫，会短暂读成没人。
 *       人坐在车里挂着 P 不停，坐多久都不停。</li>
 *   <li>人走了：在录就停；这一趟算结束（手动停过的记录作废，下一趟换出 P 挡照样自动开）。
 *       在挡位离开 P 之前，自动开录、亮屏接回、启动自动录制都不开（{@link #driverAway()}）。
 *       人回来坐下就不算「走了」了：这时手动开录，再下车照样会停。</li>
 *   <li><b>熄屏时不等 30 秒</b>：P 挡、屏幕已经黑了、主驾座位空着，就是人走了 —— 那 30 秒防的是踩踏板时座椅的误读，
 *       熄屏挂 P 时不会踩踏板。不开哨兵模式时车机熄屏六秒就深睡，录像会冻在半截、文件没收尾；
 *       所以熄屏那一刻由 {@link DriveSessionWatcher} 短暂拉住车机，在睡着之前把录像好好停掉（用户 2026-10-07）。</li>
 *   <li>时间按 {@code elapsedRealtime}（含深睡）算：万一还是睡着了，醒来第一眼就知道座位已经空了很久，立刻停。</li>
 *   <li>挡位或座位读不到：什么都不判，交回熄屏那套老规矩。</li>
 * </ul>
 *
 * <p>纯 Java，见 {@code DriveSessionTest}。</p>
 */
public final class DriveSession {

    /** 主驾座位空着多久算人走了。 */
    public static final long LEAVE_AFTER_MS = 30_000L;
    /** 哨兵模式「布防」：锁车之后（VehicleState.sentry）。 */
    public static final int SENTRY_ARMED = 2;

    /** 这一次判下来要做什么。 */
    public enum Action {
        NONE,
        /** 换出了 P 挡：开录。 */
        START,
        /** 人走了，正在录：停录（同时算这一趟结束）。 */
        STOP,
        /** 人走了，没在录：只是这一趟结束。 */
        LEFT
    }

    /** 一份输入：读不到的是 null。 */
    public static final class Input {
        public long nowElapsedMs;
        /** 挡位字母 P / R / N / D。 */
        public String gear;
        /** 主驾座位有人。 */
        public Boolean driverSeated;
        /** 哨兵模式 0 关、1 开、2 布防。 */
        public Integer sentry;
        public boolean recording;
        public boolean autoStart;
        public boolean autoStop;
        /** 这一趟用户手动停过（RecordingIntent）。 */
        public boolean stoppedByUser;
        /** 屏幕黑着。 */
        public boolean screenOff;
    }

    private String lastGear;
    /** 主驾座位从什么时候开始空着；-1 = 有人或不知道。 */
    private long seatEmptySinceMs = -1L;
    /** 人走了，还没换出 P 挡、也没人坐回来。 */
    private boolean away;

    public Action update(Input in) {
        if (in.driverSeated != null) {
            if (in.driverSeated) {
                seatEmptySinceMs = -1L;
                away = false;
            } else if (seatEmptySinceMs < 0) {
                seatEmptySinceMs = in.nowElapsedMs;
            }
        }
        if (in.gear == null) {
            return Action.NONE;
        }
        String previous = lastGear;
        lastGear = in.gear;

        if ("P".equals(previous) && !"P".equals(in.gear)) {
            away = false;
            if (in.autoStart && !in.recording && !in.stoppedByUser) {
                return Action.START;
            }
            return Action.NONE;
        }

        if ("P".equals(in.gear) && !away && left(in)) {
            away = true;
            return in.autoStop && in.recording ? Action.STOP : Action.LEFT;
        }
        return Action.NONE;
    }

    private boolean left(Input in) {
        boolean seatGone = seatEmptySinceMs >= 0
                && (in.screenOff || in.nowElapsedMs - seatEmptySinceMs >= LEAVE_AFTER_MS);
        // 布防只在座位没坐人时算数：人坐在车里从里面锁了车，照样是「人在车里」，不停
        boolean locked = in.sentry != null && in.sentry == SENTRY_ARMED && !Boolean.TRUE.equals(in.driverSeated);
        return seatGone || locked;
    }

    /** 人走了、还在 P：自动开录、亮屏接回、启动自动录制都不该开。 */
    public boolean driverAway() {
        return away && "P".equals(lastGear);
    }

    /** 座位已经空着、还差多少毫秒算人走了；不在等返回 -1。给定时再看一眼用。 */
    public long msUntilLeft(long nowElapsedMs) {
        if (away || seatEmptySinceMs < 0 || !"P".equals(lastGear)) {
            return -1L;
        }
        return Math.max(0L, LEAVE_AFTER_MS - (nowElapsedMs - seatEmptySinceMs));
    }

    /** 最近一次读到的挡位；读不到过是 null。 */
    public String gear() {
        return lastGear;
    }

    /** 诊断报告 / 黑匣子用的一行。 */
    public String describe() {
        return "gear=" + lastGear + " seatEmptySince=" + seatEmptySinceMs + " away=" + away;
    }
}
