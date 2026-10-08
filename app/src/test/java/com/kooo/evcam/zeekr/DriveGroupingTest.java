package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.storage.DriveRecords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** {@link DriveGrouping}：按行车日志分趟，日志里没有的按空隙分。 */
public class DriveGroupingTest {

    private static final long MIN = 60_000L;

    @Test
    public void aGapInsideOneDriveStaysTogether() {
        DriveGrouping g = new DriveGrouping(DriveRecords.parse("S 0 -\nE " + (60 * MIN) + " -\n"));
        // 中间录像断了 10 分钟（熄屏、相机掉线）
        assertTrue(g.joins(10 * MIN, 20 * MIN));
    }

    @Test
    public void twoDrivesAreSplitEvenIfClose() {
        DriveGrouping g = new DriveGrouping(DriveRecords.parse(
                "S 0 -\nE " + (30 * MIN) + " -\nS " + (31 * MIN) + " -\nE " + (60 * MIN) + " -\n"));
        assertFalse(g.joins(30 * MIN, 31 * MIN));
    }

    @Test
    public void consecutiveSegmentsAlwaysJoin() {
        DriveGrouping g = new DriveGrouping(DriveRecords.parse(
                "S 0 -\nE " + (30 * MIN) + " -\nS " + (30 * MIN + 1000) + " -\n"));
        assertTrue(g.joins(30 * MIN, 30 * MIN + 2000));
    }

    @Test
    public void withoutALogShortGapsJoinAndLongOnesSplit() {
        DriveGrouping g = new DriveGrouping(Collections.<DriveRecords.Drive>emptyList());
        assertTrue(g.joins(0, 2 * MIN));
        assertFalse(g.joins(0, 5 * MIN));
    }

    /** 没记到下车的那一趟算到下一趟开始。 */
    @Test
    public void anOpenDriveRunsUntilTheNextOne() {
        DriveGrouping g = new DriveGrouping(DriveRecords.parse("S 0 -\nS " + (90 * MIN) + " -\n"));
        assertTrue(g.joins(10 * MIN, 50 * MIN));
        assertFalse(g.joins(80 * MIN, 95 * MIN));
    }

    /** 和 RecordingTimeline 一起：一趟里断过的两段排成一条时间轴，空隙不占位置。 */
    @Test
    public void buildsOneSessionPerDrive() {
        List<RecordingTimeline.Source> sources = new ArrayList<>();
        sources.add(new RecordingTimeline.Source("a", 0, MIN, 1));
        sources.add(new RecordingTimeline.Source("b", 10 * MIN, MIN, 1));
        sources.add(new RecordingTimeline.Source("c", 100 * MIN, MIN, 1));
        DriveGrouping g = new DriveGrouping(DriveRecords.parse(
                "S 0 -\nE " + (20 * MIN) + " -\nS " + (99 * MIN) + " -\nE " + (110 * MIN) + " -\n"));
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources, g);
        assertEquals(2, sessions.size());
        assertEquals(2 * MIN, sessions.get(0).totalDurationMs);
    }
}
