package com.kooo.evcam.storage;

import java.util.ArrayList;
import java.util.List;

/** 行车日志（{@link DriveLog}）里的「一趟」和事件，以及解析。纯逻辑，见 {@code DriveRecordsTest}。 */
public final class DriveRecords {

    private DriveRecords() {
    }

    /** 一件事。 */
    public static final class Event {
        public static final char BRAKE = 'B';
        public static final char FLASH = 'F';

        public final char type;
        public final long epochMs;
        /** 急刹车的 g；别的为 0。 */
        public final double value;

        Event(char type, long epochMs, double value) {
            this.type = type;
            this.epochMs = epochMs;
            this.value = value;
        }
    }

    /** 一趟。 */
    public static final class Drive {
        public final long startMs;
        /** 结束时刻；没记到结束（进程没了、还在开）为 -1。 */
        public long endMs = -1L;
        public final Double startOdometerKm;
        public Double endOdometerKm;
        public final List<Event> events = new ArrayList<>();

        Drive(long startMs, Double startOdometerKm) {
            this.startMs = startMs;
            this.startOdometerKm = startOdometerKm;
        }

        /** 开了多远（km）；两头的里程有一头读不到为 null。 */
        public Double distanceKm() {
            if (startOdometerKm == null || endOdometerKm == null || endOdometerKm < startOdometerKm) {
                return null;
            }
            return endOdometerKm - startOdometerKm;
        }

        public boolean isOpen() {
            return endMs < 0;
        }
    }

    /**
     * 日志文本 → 一趟一趟，按时间先后。没结束的一趟遇到下一趟的开始就算在那儿结束了（结束时刻仍记 -1，
     * 由用的人决定按什么算）；一趟之外的事件丢掉。坏行跳过。
     */
    public static List<Drive> parse(String text) {
        List<Drive> drives = new ArrayList<>();
        if (text == null) {
            return drives;
        }
        Drive open = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2 || parts[0].length() != 1) {
                continue;
            }
            long when;
            try {
                when = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            Double number = parts.length >= 3 ? number(parts[2]) : null;
            switch (parts[0].charAt(0)) {
                case 'S':
                    open = new Drive(when, number);
                    drives.add(open);
                    break;
                case 'E':
                    if (open != null) {
                        open.endMs = when;
                        open.endOdometerKm = number;
                        open = null;
                    }
                    break;
                case 'B':
                case 'F':
                    if (open != null) {
                        open.events.add(new Event(parts[0].charAt(0), when, number == null ? 0 : number));
                    }
                    break;
                default:
                    break;
            }
        }
        return drives;
    }

    private static Double number(String text) {
        if ("-".equals(text)) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 最后一趟还没结束吗。 */
    static boolean lastIsOpen(List<Drive> drives) {
        return !drives.isEmpty() && drives.get(drives.size() - 1).isOpen();
    }

    /** 没结束的一趟开始超过这么久，就不再「接着算」：多半是进程没了、没记到下车。 */
    static final long OPEN_DRIVE_MAX_MS = 6L * 60 * 60 * 1000;

    /** 这一刻开起来的录像该不该接着最后那一趟。 */
    static boolean continuesLast(List<Drive> drives, long nowMs) {
        if (!lastIsOpen(drives)) {
            return false;
        }
        long started = drives.get(drives.size() - 1).startMs;
        return nowMs >= started && nowMs - started < OPEN_DRIVE_MAX_MS;
    }

}
