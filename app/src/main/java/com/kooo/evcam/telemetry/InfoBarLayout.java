package com.kooo.evcam.telemetry;

import java.util.ArrayList;
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
 * <p>纯函数，{@code InfoBarLayoutTest} 里测。</p>
 */
public final class InfoBarLayout {

    /** 一格：宽度（像素）、优先级（小的先保留）。声明的顺序就是从左到右的显示顺序。 */
    public enum Cell {
        TURN_LEFT(70, 1),
        HAZARD(80, 1),
        TURN_RIGHT(70, 1),
        STEERING(150, 2),
        GEAR(70, 3),
        THROTTLE(100, 4),
        BRAKE(100, 4),
        SPEED(230, 0),
        AUTO_HOLD(70, 8),
        ACC(90, 8),
        LCC(90, 8),
        DOORS(110, 5),
        BELTS(70, 5),
        DRL(70, 7),
        LOW_BEAM(70, 6),
        HIGH_BEAM(70, 6),
        FOG(70, 7),
        ODOMETER(190, 9),
        POSITION(330, 9);

        public final int width;
        public final int priority;

        Cell(int width, int priority) {
            this.width = width;
            this.priority = priority;
        }
    }

    /** 左右留白。 */
    public static final int MARGIN = 16;
    /** 格与格之间。 */
    public static final int GAP = 16;

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

    /** 这几个子开关关着的格不放。 */
    static boolean wanted(Cell cell, InfoBar.Options options) {
        switch (cell) {
            case SPEED:
                return options.speed;
            case THROTTLE:
            case BRAKE:
                return options.pedals;
            case STEERING:
                return options.steering;
            default:
                return true;
        }
    }

    /**
     * @param width 信息条（= 视频）的宽度
     * @return 放得下的格，按显示顺序，带位置
     */
    public static List<Placed> fit(int width, InfoBar.Options options) {
        List<Cell> candidates = new ArrayList<>();
        for (Cell cell : Cell.values()) {
            if (wanted(cell, options)) {
                candidates.add(cell);
            }
        }
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
