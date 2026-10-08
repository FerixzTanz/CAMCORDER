package com.kooo.evcam.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** {@link AutoLockBudget}：自动锁定超了上限，从最早的放开；刚锁的不放。 */
public class AutoLockBudgetTest {

    private static final long MB = 1024L * 1024L;

    private static Map<String, Long> sizes(String... names) {
        Map<String, Long> out = new HashMap<>();
        for (String name : names) {
            out.put(name, 100 * MB);
        }
        return out;
    }

    @Test
    public void underTheCapNothingIsReleased() {
        List<String> names = Arrays.asList("20261008_100000_surround.mp4", "20261008_100100_surround.mp4");
        assertTrue(AutoLockBudget.toRelease(names, sizes(names.toArray(new String[0])),
                Collections.<String>emptyList(), 300 * MB).isEmpty());
    }

    @Test
    public void overTheCapTheOldestGoFirst() {
        List<String> names = Arrays.asList("20261008_120000_surround.mp4",
                "20261008_100000_surround.mp4", "20261008_110000_surround.mp4");
        List<String> release = AutoLockBudget.toRelease(names, sizes(names.toArray(new String[0])),
                Collections.singletonList("20261008_120000_surround.mp4"), 150 * MB);
        assertEquals(Arrays.asList("20261008_100000_surround.mp4", "20261008_110000_surround.mp4"), release);
    }

    /** 一次事件本身就超了：刚锁的不放，宁可先超着。 */
    @Test
    public void justLockedAreNeverReleased() {
        List<String> names = Collections.singletonList("20261008_120000_surround.mp4");
        assertTrue(AutoLockBudget.toRelease(names, sizes(names.toArray(new String[0])),
                names, 10 * MB).isEmpty());
    }

    /** 盘上已经没有的不占空间。 */
    @Test
    public void missingFilesDoNotCount() {
        List<String> names = Arrays.asList("20261008_100000_surround.mp4", "20261008_110000_surround.mp4");
        assertTrue(AutoLockBudget.toRelease(names, sizes("20261008_110000_surround.mp4"),
                Collections.<String>emptyList(), 150 * MB).isEmpty());
    }

    @Test
    public void capIsTenPercentOfTheLimitOrTheDisk() {
        assertEquals((long) (25L * 1024 * MB * 0.10), AutoLockBudget.capBytes(25, 64L * 1024 * MB));
        assertEquals((long) (64L * 1024 * MB * 0.10), AutoLockBudget.capBytes(0, 64L * 1024 * MB));
    }
}
