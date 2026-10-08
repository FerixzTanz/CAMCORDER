package com.kooo.evcam.zeekr;

import com.kooo.evcam.storage.DriveRecords;

import java.util.List;

/**
 * 回放里「一趟」怎么分（2.10.8）：按行车日志（{@link com.kooo.evcam.storage.DriveLog}），日志里没有的录像按空隙分。
 *
 * <ul>
 *   <li>两段录像之间空不到 {@link RecordingTimeline#DEFAULT_MAX_GAP_MS}：一定接着（分段本来就是连着的）。</li>
 *   <li>前一段结束、后一段开始落在同一趟里：接着 —— 中间录像断过、停车熄过屏，都还是这一趟。</li>
 *   <li>落在不同的两趟里：分开，哪怕只隔了一会儿（下车买东西再上来）。</li>
 *   <li>日志里没有（以前的录像、日志写不进）：空不到 {@link #FALLBACK_GAP_MS} 就接着。</li>
 * </ul>
 *
 * <p>没记到结束的那一趟（进程没了）算到下一趟开始为止；最后一趟没结束就算到现在。纯逻辑，见 {@code DriveGroupingTest}。</p>
 */
public final class DriveGrouping implements RecordingTimeline.Joiner {

    /** 日志里没有的录像：空隙小于这个就算同一趟。 */
    public static final long FALLBACK_GAP_MS = 3L * 60 * 1000;

    private final List<DriveRecords.Drive> drives;

    public DriveGrouping(List<DriveRecords.Drive> drives) {
        this.drives = drives;
    }

    @Override
    public boolean joins(long previousEndEpochMs, long nextStartEpochMs) {
        long gap = nextStartEpochMs - previousEndEpochMs;
        if (gap <= RecordingTimeline.DEFAULT_MAX_GAP_MS) {
            return true;
        }
        int a = driveAt(previousEndEpochMs);
        int b = driveAt(nextStartEpochMs);
        if (a >= 0 || b >= 0) {
            return a == b;
        }
        return gap <= FALLBACK_GAP_MS;
    }

    /** 这一刻落在第几趟里；都不在为 -1。分段的结尾可能比「下车」晚几秒（收尾），放宽一个分段空隙。 */
    public int driveAt(long epochMs) {
        for (int i = drives.size() - 1; i >= 0; i--) {
            DriveRecords.Drive drive = drives.get(i);
            long end = endOf(i);
            if (epochMs >= drive.startMs - RecordingTimeline.DEFAULT_MAX_GAP_MS
                    && epochMs <= end + RecordingTimeline.DEFAULT_MAX_GAP_MS) {
                return i;
            }
        }
        return -1;
    }

    /** 第 i 趟到什么时候：记到的结束；没记到就到下一趟开始；最后一趟没结束就到永远。 */
    long endOf(int i) {
        DriveRecords.Drive drive = drives.get(i);
        if (!drive.isOpen()) {
            return drive.endMs;
        }
        return i + 1 < drives.size() ? drives.get(i + 1).startMs - 1 : Long.MAX_VALUE - RecordingTimeline.DEFAULT_MAX_GAP_MS;
    }

    /** 这一趟（{@link #driveAt} 给的下标）；没有为 null。 */
    public DriveRecords.Drive drive(int index) {
        return index >= 0 && index < drives.size() ? drives.get(index) : null;
    }
}
