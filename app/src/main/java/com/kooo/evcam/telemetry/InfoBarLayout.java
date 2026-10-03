package com.kooo.evcam.telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 信息条上放哪几格、各放在哪。
 *
 * <h3>放哪几格：系统信息里勾的（项目所有者 2026-10-03）</h3>
 *
 * <p>设置 → 系统 → 系统信息，每个信号前面一个勾，勾上的上信息条（{@link InfoBar#selection}）。
 * 有图标的信号带出它所在的那一格 —— 一格管几个信号的，勾其中任意一个就带出整格，数据照旧全用；
 * 没有图标的画成文字格（名称 + 值，写法和系统信息页一样，见 {@link SignalText}）。
 * 经纬度不是车辆信号，单独一项 {@link #POSITION_ITEM}。默认勾的是 2.0.10 信息条上在用的那些（{@link #defaultSelection}）。</p>
 *
 * <h3>放在哪</h3>
 *
 * <p>图标格按声明的顺序在前，文字格按信号表的顺序跟在后面，从左往右排；放不下的那一格和它后面的都不放 ——
 * 超出信息条长度的部分不显示，不再按优先级挑。每一格宽度固定，高都是 {@link InfoBar#HEIGHT}。</p>
 *
 * <p>纯函数，{@code InfoBarLayoutTest} 里测。</p>
 */
public final class InfoBarLayout {

    /** 勾选清单里经纬度那一项：系统定位，不是车辆信号。 */
    public static final String POSITION_ITEM = "POSITION";

    /**
     * 有图标的格：宽度（逻辑像素）、靠哪几个信号。声明的顺序就是从左到右的顺序。
     */
    public enum Cell {
        /** 转向灯、双闪三格是一组：勾转向指示状态或左 / 右转向灯任意一个，三格一起上。 */
        TURN_LEFT(70, Signal.INDICATOR, Signal.TURN_LEFT, Signal.TURN_RIGHT),
        HAZARD(80, Signal.INDICATOR, Signal.TURN_LEFT, Signal.TURN_RIGHT),
        TURN_RIGHT(70, Signal.INDICATOR, Signal.TURN_LEFT, Signal.TURN_RIGHT),
        /** 方向盘随读数转；先用着，满舵几圈、最大读数多少等 Lab（{@link Signal#STEERING_DEGREES_PER_UNIT}）。 */
        STEERING(130, Signal.STEERING),
        GEAR(70, Signal.GEAR),
        /** 刹车（上）和油门（下）两根横条，左边带深度数字。 */
        PEDALS(170, Signal.BRAKE_DEPTH, Signal.THROTTLE_DEPTH),
        SPEED(210, Signal.SPEED),
        /** 自动驻车「正在驻车」：停下被接管时亮，起步灭（不是功能开关 0x20060400，那个开车全程都亮）。 */
        AUTO_HOLD(70, Signal.AUTO_HOLD_ACTIVE),
        /** 原厂 360 画面显示中：它占着相机，我们的录像会断。 */
        STOCK_360(80, Signal.STOCK_360),
        /** 哨兵模式：关 / 开 / 布防。放在原厂 360 右边（项目所有者 2026-10-03）；熄屏后能不能接着录看的就是它。 */
        SENTRY(80, Signal.SENTRY_MODE),
        /** 俯视的车：四扇门 + 五个座位的安全带（除主驾外都没验证：非开发者拿不到，画成没数据）。 */
        CABIN(170, Signal.DOOR_DRIVER, Signal.DOOR_PASSENGER, Signal.DOOR_REAR_LEFT,
                Signal.DOOR_REAR_RIGHT, Signal.BELT_DRIVER, Signal.BELT_PASSENGER,
                Signal.BELT_REAR_LEFT, Signal.BELT_REAR_CENTER, Signal.BELT_REAR_RIGHT),
        /** 日行灯：以 7X 正脸为底，整条里唯一带光晕的图标。画的是前灯带：日行灯或前位置灯亮就亮。 */
        DRL(130, Signal.DRL, Signal.FRONT_POSITION_LAMP),
        /**
         * 近光 + 远光一格：四道光线的方向说明开的是哪个 —— 都不亮灰色全斜向下，只近光全斜向下，
         * 只远光（含闪远光）全平直，同时开上两道平直、下两道斜向下。
         */
        BEAMS(90, Signal.LOW_BEAM, Signal.HIGH_BEAM, Signal.HIGH_BEAM_FLASH),
        /**
         * 后灯组：7X 车尾。贯穿尾灯暗红细条 = 后位置灯，亮红粗条 + 高位刹车灯 = 刹车灯；
         * 保险杠两侧各一盏两色灯，外侧亮红 = 后雾灯，内侧白 = 倒车灯，紧挨着，可以同时亮。
         */
        REAR_LAMPS(150, Signal.REAR_POSITION_LAMP, Signal.STOP_LAMP, Signal.REAR_FOG, Signal.REVERSE_LAMP),
        /** 六项安全辅助的开关：AEB、前碰预警、车道偏离、车道保持、盲区、后碰预警，各自一块，没数据的各自划掉。 */
        ASSIST(200, Signal.AEB, Signal.FCW, Signal.LDW, Signal.LKA, Signal.BSD, Signal.RCW),
        ODOMETER(160, Signal.ODOMETER),
        /** 经纬度（系统定位）：勾的是 {@link #POSITION_ITEM}。 */
        POSITION(170),
        /** 按喇叭（Lab 0.23.0：多半拿不到，五次测试都没有号跟着变）。只在车辆状态面板上。 */
        HORN(80, false),
        /** 闪远光：只在车辆状态面板上（信息条上它算进近光远光那一格）。 */
        FLASH(90, false, Signal.HIGH_BEAM_FLASH);

        public final int width;
        /** 信息条上放不放（不放的只在车辆状态面板上用）。 */
        public final boolean onStrip;
        private final Signal[] signals;

        Cell(int width, Signal... signals) {
            this(width, true, signals);
        }

        Cell(int width, boolean onStrip, Signal... signals) {
            this.width = width;
            this.onStrip = onStrip;
            this.signals = signals;
        }

        /** 这一格用到的信号。 */
        public List<Signal> signals() {
            return Collections.unmodifiableList(Arrays.asList(signals));
        }

        /** 勾了这些项，这一格上不上：用到的信号勾了任意一个就上（经纬度看它自己那一项）。 */
        boolean selectedBy(Set<String> selection) {
            if (this == POSITION) {
                return selection.contains(POSITION_ITEM);
            }
            for (Signal s : signals) {
                if (selection.contains(s.name())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 左右留白。 */
    public static final int MARGIN = 16;
    /** 格与格之间。 */
    public static final int GAP = 12;
    /** 文字格的宽：上面名称、下面值，宽度固定，值变了格子不跳。 */
    public static final int TEXT_WIDTH = 170;

    /** 放好的一格：图标格（{@link #cell}）或文字格（{@link #text}），左上角在哪，多宽。 */
    public static final class Placed {
        /** 图标格；文字格是 null。 */
        public final Cell cell;
        /** 文字格画的信号；图标格是 null。 */
        public final Signal text;
        public final int x;
        public final int y;
        public final int width;

        Placed(Cell cell, Signal text, int x, int y, int width) {
            this.cell = cell;
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    /** 一种摆法：哪几格放在哪，整块多大（逻辑像素，格子高都是 {@link InfoBar#HEIGHT}）。 */
    public static final class Arrangement {
        public final List<Placed> cells;
        public final int width;
        public final int height;

        Arrangement(List<Placed> cells, int width, int height) {
            this.cells = cells;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 车辆状态面板放哪几格，一行一组：喇叭、闪远光、双闪。
     * 用户 2026-10-03 定：只留这三项（哨兵模式在「熄屏持续录制」那一行、录制键上和信息条上）。
     */
    static final Cell[][] PANEL_ROWS = {
            {Cell.HORN, Cell.FLASH, Cell.HAZARD},
    };

    private InfoBarLayout() {
    }

    /** 车辆状态面板的摆法：每行居中，行与行之间、格与格之间都隔 {@link #GAP}。 */
    public static Arrangement panel() {
        int widest = 0;
        for (Cell[] row : PANEL_ROWS) {
            widest = Math.max(widest, rowWidth(row));
        }
        int width = widest + 2 * MARGIN;
        List<Placed> placed = new ArrayList<>();
        int y = 0;
        for (Cell[] row : PANEL_ROWS) {
            int x = (width - rowWidth(row)) / 2;
            for (Cell cell : row) {
                placed.add(new Placed(cell, null, x, y, cell.width));
                x += cell.width + GAP;
            }
            y += InfoBar.HEIGHT + GAP;
        }
        return new Arrangement(placed, width, y - GAP);
    }

    private static int rowWidth(Cell[] row) {
        int w = 0;
        for (Cell cell : row) {
            w += (w == 0 ? 0 : GAP) + cell.width;
        }
        return w;
    }

    /** 这个信号在信息条上有图标（属于信息条上的某一格）；没有的画成文字格。 */
    public static boolean hasIcon(Signal signal) {
        for (Cell cell : Cell.values()) {
            if (cell.onStrip && cell.signals().contains(signal)) {
                return true;
            }
        }
        return false;
    }

    /** 默认勾哪些：2.0.10 信息条上在用的 —— 能用的信号（驾驶辅助那格当时整格划掉，不算），加上经纬度。 */
    public static Set<String> defaultSelection() {
        Set<String> names = new LinkedHashSet<>();
        for (Cell cell : Cell.values()) {
            if (!cell.onStrip || cell == Cell.ASSIST) {
                continue;
            }
            for (Signal s : cell.signals) {
                if (s.usable()) {
                    names.add(s.name());
                }
            }
        }
        names.add(POSITION_ITEM);
        return names;
    }

    /**
     * @param width     信息条（= 视频）的宽度
     * @param selection 勾了哪些（信号名，加上 {@link #POSITION_ITEM}）
     * @return 放得下的格，按显示顺序，带位置
     */
    public static List<Placed> fit(int width, Set<String> selection) {
        List<Placed> placed = new ArrayList<>();
        int right = width - MARGIN;
        int x = MARGIN;
        for (Cell cell : Cell.values()) {
            if (!cell.onStrip || !cell.selectedBy(selection)) {
                continue;
            }
            if (x + cell.width > right) {
                return placed;
            }
            placed.add(new Placed(cell, null, x, 0, cell.width));
            x += cell.width + GAP;
        }
        for (Signal signal : Signal.values()) {
            if (!selection.contains(signal.name()) || hasIcon(signal)) {
                continue;
            }
            if (x + TEXT_WIDTH > right) {
                return placed;
            }
            placed.add(new Placed(null, signal, x, 0, TEXT_WIDTH));
            x += TEXT_WIDTH + GAP;
        }
        return placed;
    }
}
