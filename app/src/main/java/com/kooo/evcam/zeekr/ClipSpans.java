package com.kooo.evcam.zeekr;

import android.os.SystemClock;

import com.kooo.evcam.blackbox.BlackBox;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 不打开文件就知道时长的那些录像分段：照文件名和文件时间算。取自 dts88 4.4.1-alpha（ClipSpans）；
 * 这里两处扫描（回看网格 VideoScan、播放器 TimelinePlayerActivity）都走 {@link #durations}。
 *
 * <h3>凭什么</h3>
 *
 * <p>文件名里只有开录时刻。结束时刻文件系统替我们记着：文件的修改时间，就是录完封好的那一下。</p>
 *
 * <p>实车量过（2026-11-14，4.4.0 的诊断，781 个文件）：拿「修改时间 − 开录时刻」估的时长和打开文件读出来的，
 * 差的中位数 0.37 秒；777 个里差 3 秒以上的只有 1 个，估不出的 1 个。全部打开读一遍要 37.7 秒。</p>
 *
 * <h3>怎么算</h3>
 *
 * <p>同一路里按开录时刻排，一个文件看它自己和它后面那一个：</p>
 * <ul>
 *   <li>封好的那一下挨着下一段的开录时刻（差不到 {@link #TOLERANCE_MS}）：它一直录到了下一段开头，
 *       时长算作两段开录时刻的差；</li>
 *   <li>封好得更早：它自己停的（一条录制的最后一段、中间停过），时长是「修改时间 − 开录时刻」。</li>
 * </ul>
 *
 * <h3>算不了的不给，调用方打开读（{@link ClipDurations}）</h3>
 *
 * <ul>
 *   <li>这一路最新的那一个：它可能还在录，没封好的文件放不出来 —— 打开看一眼才知道；</li>
 *   <li>文件时间用不上：没有修改时间、比开录时刻还早、离开录时刻比一个分段还长、下一段都开录了它才「封好」
 *       （后来被动过）、两个文件同一刻开录。</li>
 * </ul>
 *
 * <h3>和读出来的差在哪</h3>
 *
 * <p>算出来的比文件里的画面长一点：到下一段开头的，长一个换段的空；自己停的，长一个封文件的工夫（都是零点几秒）。
 * 进度条上这一段就占这么长，放完接下一段时位置往前跳这一点。分条的界线照样是「前一段结束到后一段开始超过 5 秒」，
 * 只是「结束」换成了封好的那一下。</p>
 *
 * <p>断电、掉盘留下的半截文件，读时长时读不出来、原来是跳过的；照文件时间算它有时长，会排进时间轴，
 * 放到它时播放器打不开、接着放下一段（回放本来就这样处理放不出来的片段）。</p>
 *
 * <p>文件时间不对的盘上（修改时间被别的东西改过、时钟乱过）这里会算错；dts88 那边留了一个开发者选项改回逐个读，
 * 这里没搬。</p>
 */
public final class ClipSpans {

    /** 封好的那一下和下一段的开录时刻差不到这么多，就算这一段录到了下一段开头。 */
    static final long TOLERANCE_MS = 2_000L;
    /** 一个文件不会比这还长：最长的分段是 10 分钟，宽一点。 */
    static final long LIMIT_MS = 15 * 60_000L;

    /** 一路里的一个文件：名字里的开录时刻、文件系统记的修改时间（没有是 0）。 */
    static final class Timed {
        final String path;
        final long startEpochMs;
        final long modifiedMs;

        Timed(String path, long startEpochMs, long modifiedMs) {
            this.path = path;
            this.startEpochMs = startEpochMs;
            this.modifiedMs = modifiedMs;
        }
    }

    private ClipSpans() {
    }

    /**
     * 这些录像文件各自的时长（路径 → 毫秒）：能照文件名和文件时间算的直接算，算不了的打开读
     * （{@link ClipDurations}，慢）。不是本应用的录像、空文件、读也读不出时长的，不在结果里。
     * 会碰盘，在后台线程上调。
     */
    public static Map<String, Long> durations(File[] files) {
        Map<String, Long> out = new HashMap<>();
        if (files == null) {
            return out;
        }
        long began = SystemClock.elapsedRealtime();
        // 每一路的文件各自排着看（环视也算一路）
        Map<String, List<Timed>> lanes = new HashMap<>();
        List<File> clips = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                continue;
            }
            String slot = RecordingTimeline.parseCameraSlot(f.getName());
            long start = RecordingTimeline.parseStartEpochMs(f.getName());
            if (slot == null || start < 0 || f.length() <= 0) {
                continue;
            }
            String key = slot.toLowerCase(Locale.US);
            List<Timed> lane = lanes.get(key);
            if (lane == null) {
                lane = new ArrayList<>();
                lanes.put(key, lane);
            }
            lane.add(new Timed(f.getAbsolutePath(), start, f.lastModified()));
            clips.add(f);
        }
        Map<String, Long> known = new HashMap<>();
        for (List<Timed> lane : lanes.values()) {
            known.putAll(known(lane));
        }
        long listedMs = SystemClock.elapsedRealtime() - began;
        int read = 0;
        for (File f : clips) {
            Long span = known.get(f.getAbsolutePath());
            if (span == null) {
                read++;
                long measured = ClipDurations.of(f);
                if (measured <= 0) {
                    continue;
                }
                span = measured;
            }
            out.put(f.getAbsolutePath(), span);
        }
        BlackBox.note("回放扫描：" + clips.size() + " 个录像文件，时长照文件名和修改时间算出来的 " + known.size()
                + " 个、打开读的 " + read + " 个；列目录 " + listedMs + " ms，全部 "
                + (SystemClock.elapsedRealtime() - began) + " ms");
        return out;
    }

    /**
     * 这一路里不用打开文件就知道时长的那些：路径 → 时长（毫秒）。不在里面的要打开读。
     *
     * @param lane 同一路的全部文件（次序随便）
     */
    static Map<String, Long> known(List<Timed> lane) {
        List<Timed> sorted = new ArrayList<>(lane);
        Collections.sort(sorted, (a, b) -> Long.compare(a.startEpochMs, b.startEpochMs));
        Map<String, Long> out = new HashMap<>();
        // 走到倒数第二个为止：最新的那一个可能还在录，不给
        for (int i = 0; i + 1 < sorted.size(); i++) {
            Timed clip = sorted.get(i);
            Timed next = sorted.get(i + 1);
            long byTime = clip.modifiedMs - clip.startEpochMs;
            long toNext = next.startEpochMs - clip.startEpochMs;
            if (clip.modifiedMs <= 0 || byTime <= 0 || byTime > LIMIT_MS || toNext <= 0) {
                continue;
            }
            long sealedBeforeNext = next.startEpochMs - clip.modifiedMs;
            if (sealedBeforeNext < -TOLERANCE_MS) {
                continue;  // 下一段都开录了它才封好：这个时间是后来改的
            }
            out.put(clip.path, sealedBeforeNext <= TOLERANCE_MS ? toNext : byTime);
        }
        return out;
    }
}
