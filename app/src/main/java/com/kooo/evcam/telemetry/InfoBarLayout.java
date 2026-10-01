package com.kooo.evcam.telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 信息条上放哪几格、各放在哪：按视频宽度定。
 *
 * <p>每一格有固定的宽（高都是 100）和一个优先级。宽度不够时从优先级低的开始去掉
 * （里程、经纬度先走，车速、转向灯最后走），留下的按固定的顺序从左排起。
 * 环视（2560 宽）放得下全部；座舱那种窄一点的流放得下多少放多少 ——
 * 同一条规则，不按流分别配。</p>
 *
 * <p>每一格还写明它靠哪几个信号（{@link Signal}）：信号都{@linkplain Signal#usable() 能用}的格才算能用。
 * 格子永远全放；不能用的格默认不启用，画成没数据（斜杠划掉），开发者「激活所有栏目信息」后
 * 才启用（{@link #live}）。</p>
 *
 * <p>纯函数，{@code InfoBarLayoutTest} 里测。</p>
 */
public final class InfoBarLayout {

    /** 没有车上信号的格：能不能用直接写死。 */
    public enum Verdict {
        /** 来源不是车辆接口（定位），车上看到过它在动 */
        YES,
        /** 还没找到读数来源，先画着 */
        NO
    }

    /**
     * 一格：宽度（像素）、优先级（小的先保留）、靠哪几个信号。声明的顺序就是从左到右的显示顺序。
     */
    public enum Cell {
        TURN_LEFT(70, 1, Signal.INDICATOR),
        HAZARD(80, 1, Signal.INDICATOR),
        TURN_RIGHT(70, 1, Signal.INDICATOR),
        /** 方向盘随读数转；先用着，满舵几圈、最大读数多少等 Lab（{@link Signal#STEERING_DEGREES_PER_UNIT}）。 */
        STEERING(130, 2, Signal.STEERING),
        /** 驾驶员手在不在方向盘上（还没找到车上的读数，先画着）。 */
        HANDS(80, 2, Verdict.NO),
        GEAR(70, 3, Signal.GEAR),
        /** 刹车（上）和油门（下）两根横条，左边带深度数字。先按 0–100 画，踩到底是多少等 Lab。 */
        PEDALS(170, 4, Signal.BRAKE_DEPTH, Signal.THROTTLE_DEPTH),
        SPEED(210, 0, Signal.SPEED),
        /** 自动驻车「正在驻车」：停下被接管时亮，起步灭（不是功能开关 0x20060400，那个开车全程都亮）。 */
        AUTO_HOLD(70, 8, Signal.AUTO_HOLD_ACTIVE),
        ACC(80, 8, Verdict.NO),
        LCC(80, 8, Signal.LCC),
        /** 原厂 360 画面显示中：它占着相机，我们的录像会断，所以优先级高。 */
        STOCK_360(80, 3, Signal.STOCK_360),
        /** 俯视的车：四扇门 + 五个座位的安全带（看四扇门和主驾安全带；其余安全带没验证前滤掉）。 */
        CABIN(170, 5, Signal.DOOR_DRIVER, Signal.DOOR_PASSENGER, Signal.DOOR_REAR_LEFT,
                Signal.DOOR_REAR_RIGHT, Signal.BELT_DRIVER),
        /** 日行灯：以 7X 正脸为底的那一格，整条里唯一带光晕的图标，所以宽一些。画的是前灯带：日行灯或前位置灯亮就亮。 */
        DRL(130, 7, Signal.DRL, Signal.FRONT_POSITION_LAMP),
        LOW_BEAM(80, 6, Signal.LOW_BEAM),
        HIGH_BEAM(80, 6, Signal.HIGH_BEAM),
        /** 雾灯：只看后雾灯（前雾灯这台车多半没装）。 */
        FOG(80, 7, Signal.REAR_FOG),
        /** 六项安全辅助的开关：AEB、前碰预警、车道偏离、车道保持、盲区、后碰预警。 */
        ASSIST(200, 6, Signal.AEB, Signal.FCW, Signal.LDW, Signal.LKA, Signal.BSD, Signal.RCW),
        ODOMETER(160, 9, Signal.ODOMETER),
        /** 经纬度来自系统定位，1.72.0 车上看到过。 */
        POSITION(170, 9, Verdict.YES);

        public final int width;
        public final int priority;
        private final Signal[] signals;
        private final Verdict verdict;

        Cell(int width, int priority, Signal... signals) {
            this.width = width;
            this.priority = priority;
            this.signals = signals;
            this.verdict = null;
        }

        Cell(int width, int priority, Verdict verdict) {
            this.width = width;
            this.priority = priority;
            this.signals = new Signal[0];
            this.verdict = verdict;
        }

        /** 这一格用到的信号都能用（没有车上信号的格按写死的结论）。 */
        public boolean usable() {
            if (verdict != null) {
                return verdict == Verdict.YES;
            }
            for (Signal s : signals) {
                if (!s.usable()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 左右留白。 */
    public static final int MARGIN = 16;
    /** 格与格之间。 */
    public static final int GAP = 12;

    /** 放好的一格：哪一格、左边缘在哪。 */
    public static final class Placed {
        public final Cell cell;
        public final int x;

        Placed(Cell cell, int x) {
            this.cell = cell;
            this.x = x;
        }
    }

    private InfoBarLayout() {
    }

    /**
     * 这一格启不启用：能用的都启用；不能用的只有开发者「激活所有栏目信息」之后才启用。
     * 没启用的格照样放在条上，画成没数据（斜杠划掉）。
     */
    public static boolean live(Cell cell, InfoBar.Options options) {
        return options.all || cell.usable();
    }

    /**
     * @param width 信息条（= 视频）的宽度
     * @return 放得下的格，按显示顺序，带位置
     */
    public static List<Placed> fit(int width) {
        List<Cell> candidates = Arrays.asList(Cell.values());
        // 先按优先级挑（同级按显示顺序），再按显示顺序摆
        List<Cell> byPriority = new ArrayList<>(candidates);
        Collections.sort(byPriority, new Comparator<Cell>() {
            @Override
            public int compare(Cell a, Cell b) {
                if (a.priority != b.priority) {
                    return a.priority - b.priority;
                }
                return a.ordinal() - b.ordinal();
            }
        });
        int available = width - 2 * MARGIN;
        int used = 0;
        List<Cell> kept = new ArrayList<>();
        for (Cell cell : byPriority) {
            int need = cell.width + (kept.isEmpty() ? 0 : GAP);
            if (used + need > available) {
                continue;
            }
            used += need;
            kept.add(cell);
        }
        List<Placed> placed = new ArrayList<>();
        int x = MARGIN;
        for (Cell cell : candidates) {
            if (!kept.contains(cell)) {
                continue;
            }
            placed.add(new Placed(cell, x));
            x += cell.width + GAP;
        }
        return placed;
    }
}
