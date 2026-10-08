package com.kooo.evcam.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** {@link DriveRecords}：行车日志解析成一趟一趟。 */
public class DriveRecordsTest {

    @Test
    public void aDriveWithEventsAndDistance() {
        List<DriveRecords.Drive> drives = DriveRecords.parse("# header\n"
                + "S 1000 24814.0\n"
                + "B 2000 0.62\n"
                + "F 3000\n"
                + "E 9000 24855.3\n");
        assertEquals(1, drives.size());
        DriveRecords.Drive d = drives.get(0);
        assertEquals(1000, d.startMs);
        assertEquals(9000, d.endMs);
        assertEquals(41.3, d.distanceKm(), 0.01);
        assertEquals(2, d.events.size());
        assertEquals(DriveRecords.Event.BRAKE, d.events.get(0).type);
        assertEquals(0.62, d.events.get(0).value, 0.001);
        assertFalse(d.isOpen());
    }

    /** 没记到下车（进程没了）：下一趟开始时上一趟仍是「没结束」，由分组决定算到哪。 */
    @Test
    public void anUnfinishedDriveStaysOpen() {
        List<DriveRecords.Drive> drives = DriveRecords.parse("S 1000 -\nS 5000 -\nE 6000 -\n");
        assertEquals(2, drives.size());
        assertTrue(drives.get(0).isOpen());
        assertNull(drives.get(0).distanceKm());
        assertFalse(drives.get(1).isOpen());
    }

    @Test
    public void eventsOutsideADriveAndBadLinesAreIgnored() {
        List<DriveRecords.Drive> drives = DriveRecords.parse("B 500 0.7\ngarbage\nS x\nS 1000 10\nE 2000 12\nF 3000\n");
        assertEquals(1, drives.size());
        assertTrue(drives.get(0).events.isEmpty());
    }

    @Test
    public void continuingTheLastDriveOnlyWhileItIsRecentAndOpen() {
        List<DriveRecords.Drive> open = DriveRecords.parse("S 1000 -\n");
        assertTrue(DriveRecords.continuesLast(open, 1000 + 60_000));
        assertFalse(DriveRecords.continuesLast(open, 1000 + DriveRecords.OPEN_DRIVE_MAX_MS));
        assertFalse(DriveRecords.continuesLast(DriveRecords.parse("S 1000 -\nE 2000 -\n"), 3000));
    }
}
