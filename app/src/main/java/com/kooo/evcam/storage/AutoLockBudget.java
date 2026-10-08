package com.kooo.evcam.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 自动锁定（急刹车）最多占多少：超了先放开最早的（2.10.8）。
 *
 * <p>锁定的影像不会被自动清理删掉；锁满了录像就停。手动锁的、闪远光锁的是人要的，不动；
 * 车自己判断锁的，误判了也只是多留几段，所以给它一个上限：录像空间的 {@link #SHARE}，
 * 超了从最早的开始放开（放开 = 变回普通录像，照常按时间清理）。这一次刚锁的不放 ——
 * 一次事件本身就超了上限时，宁可先超着。</p>
 *
 * <p>文件名以时间开头，按名字排就是按时间排。纯逻辑，见 {@code AutoLockBudgetTest}。</p>
 */
public final class AutoLockBudget {

    /** 自动锁定最多占录像空间的这一份。 */
    public static final double SHARE = 0.10;

    private AutoLockBudget() {
    }

    /** 上限（字节）：设了录像空间上限按它算，没设按盘的总容量算。 */
    public static long capBytes(int videoLimitGb, long diskTotalBytes) {
        long space = videoLimitGb > 0 ? videoLimitGb * 1024L * 1024L * 1024L : diskTotalBytes;
        return (long) (Math.max(0L, space) * SHARE);
    }

    /**
     * 该放开哪些。
     *
     * @param autoLocked 自动锁定的文件名
     * @param sizes      文件名 → 字节（盘上已经没有的不在里面，不占空间）
     * @param justLocked 这一次刚锁的，不放
     * @param capBytes   上限
     * @return 要放开的文件名，最早的在前
     */
    public static List<String> toRelease(Collection<String> autoLocked, Map<String, Long> sizes,
                                         Collection<String> justLocked, long capBytes) {
        long total = 0;
        for (String name : autoLocked) {
            Long size = sizes.get(name);
            if (size != null) {
                total += size;
            }
        }
        List<String> release = new ArrayList<>();
        for (String name : new TreeSet<>(autoLocked)) {
            if (total <= capBytes) {
                break;
            }
            if (justLocked.contains(name)) {
                continue;
            }
            Long size = sizes.get(name);
            release.add(name);
            total -= size == null ? 0 : size;
        }
        return release;
    }
}
