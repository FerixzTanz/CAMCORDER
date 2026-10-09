package com.kooo.evcam.playback;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「保存片段」：把某一刻前后的录像抄成一个普通的 MP4（2.10.8）。
 *
 * <p>不重新编码：按关键帧切、原样搬过去（MediaExtractor → MediaMuxer），几秒钟就好，画质和原片一样。
 * 关键帧每 3 秒一个，所以片段的开头会往前多出最多 3 秒。跨分段时一段接一段搬，时间戳接上；
 * 后一段的编码参数（csd）和第一段不一样的话就停在那儿 —— 拼上去会花屏。</p>
 *
 * <p>存到录像目录旁边的 {@link #DIR_NAME}：自动清理只管录像目录，片段不会被删。</p>
 */
public final class ClipExporter {

    private static final String TAG = "ClipExporter";

    /** 片段放在哪（和录像目录同级）。 */
    public static final String DIR_NAME = "EVCam_Clips";
    /** 前后各多少。 */
    public static final long HALF_MS = 15_000L;
    /** 自己选的一段最长多久：再长就是好几百 MB，发手机也发不动。 */
    public static final long MAX_RANGE_MS = 10 * 60_000L;

    /** 一段录像：文件、开始时刻（系统时间）、时长。 */
    public static final class Part {
        final File file;
        final long startEpochMs;
        final long durationMs;

        public Part(File file, long startEpochMs, long durationMs) {
            this.file = file;
            this.startEpochMs = startEpochMs;
            this.durationMs = durationMs;
        }
    }

    private ClipExporter() {
    }

    /** 片段要存到的目录：录像目录的同级。 */
    public static File clipsDir(File videoDir) {
        File parent = videoDir != null ? videoDir.getParentFile() : null;
        return parent != null ? new File(parent, DIR_NAME) : null;
    }

    /**
     * 抄出 [momentMs − {@link #HALF_MS}, momentMs + {@link #HALF_MS}] 这一段。会碰盘、要几秒，别在主线程上调。
     *
     * @param parts    这一路的录像，按时间先后
     * @param momentMs 中心时刻（系统时间）
     * @param outDir   存到哪
     * @param label    文件名里的那一路（surround 等）
     * @return 存好的文件；失败为 null
     */
    public static File export(List<Part> parts, long momentMs, File outDir, String label) {
        return exportRange(parts, momentMs - HALF_MS, momentMs + HALF_MS, momentMs, outDir, label);
    }

    /**
     * 抄出 [fromMs, toMs] 这一段（自己选的起点和终点，最长 {@link #MAX_RANGE_MS}）。
     * 文件名按起点。别在主线程上调。
     */
    public static File exportRange(List<Part> parts, long fromMs, long toMs, File outDir, String label) {
        return exportRange(parts, fromMs, Math.min(toMs, fromMs + MAX_RANGE_MS), fromMs, outDir, label);
    }

    private static File exportRange(List<Part> parts, long fromMs, long toMs, long nameMs, File outDir,
            String label) {
        if (parts == null || parts.isEmpty() || toMs <= fromMs
                || outDir == null || !(outDir.isDirectory() || outDir.mkdirs())) {
            return null;
        }
        String base = "clip_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(nameMs))
                + "_" + label;
        // 同一刻存两次不覆盖上一次的：加序号（审查 2026-10-08）
        File out = new File(outDir, base + ".mp4");
        for (int n = 2; out.exists() && n < 100; n++) {
            out = new File(outDir, base + "_" + n + ".mp4");
        }
        // 先写到 .part，写完再改名：失败了只删自己的半截，不会碰到已经存好的片段
        File part = new File(outDir, out.getName() + ".part");
        MediaMuxer muxer = null;
        boolean started = false;
        boolean wrote = false;
        try {
            muxer = new MediaMuxer(part.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            ByteBuffer firstCsd = null;
            long baseUs = Long.MIN_VALUE;
            long lastPtsUs = -1;
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (Part clip : parts) {
                long partEnd = clip.startEpochMs + clip.durationMs;
                if (partEnd <= fromMs || clip.startEpochMs >= toMs) {
                    continue;
                }
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(clip.file.getAbsolutePath());
                    int videoTrack = videoTrackOf(extractor);
                    if (videoTrack < 0) {
                        continue;
                    }
                    MediaFormat format = extractor.getTrackFormat(videoTrack);
                    ByteBuffer csd = format.containsKey("csd-0") ? format.getByteBuffer("csd-0") : null;
                    if (track < 0) {
                        track = muxer.addTrack(format);
                        muxer.start();
                        started = true;
                        firstCsd = csd;
                    } else if (firstCsd != null && csd != null && !firstCsd.equals(csd)) {
                        AppLog.w(TAG, "下一段编码参数变了，片段停在 " + clip.file.getName() + " 之前");
                        break;
                    }
                    extractor.selectTrack(videoTrack);
                    long startUs = Math.max(0L, (fromMs - clip.startEpochMs) * 1000L);
                    long endUs = (toMs - clip.startEpochMs) * 1000L;
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    long partOffsetUs = (clip.startEpochMs - fromMs) * 1000L;
                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            break;
                        }
                        long sampleUs = extractor.getSampleTime();
                        if (sampleUs > endUs) {
                            break;
                        }
                        long ptsUs = partOffsetUs + sampleUs;
                        if (baseUs == Long.MIN_VALUE) {
                            baseUs = ptsUs;   // 片段从第一帧（关键帧）算起
                        }
                        ptsUs -= baseUs;
                        if (ptsUs <= lastPtsUs) {
                            ptsUs = lastPtsUs + 1;
                        }
                        info.set(0, size, ptsUs, (extractor.getSampleFlags()
                                & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        muxer.writeSampleData(track, buffer, info);
                        lastPtsUs = ptsUs;
                        wrote = true;
                        extractor.advance();
                    }
                } catch (Exception e) {
                    // 后面某一段坏了：前面已经写进去的照样留着（审查 2026-10-08）
                    AppLog.w(TAG, "这一段读不出来，片段到此为止: " + clip.file.getName() + " " + e.getMessage());
                    if (wrote) {
                        break;
                    }
                } finally {
                    extractor.release();
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "保存片段失败: " + e.getMessage(), e);
        } finally {
            if (muxer != null) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (Exception e) {
                        AppLog.w(TAG, "收尾失败: " + e.getMessage());
                        wrote = false;
                    }
                }
                try {
                    muxer.release();
                } catch (Exception e) {
                    AppLog.w(TAG, "释放失败: " + e.getMessage());
                }
            }
        }
        if (!wrote || !part.renameTo(out)) {
            if (part.exists()) {
                part.delete();
            }
            return null;
        }
        AppLog.i(TAG, "片段已保存 " + out + "（" + (out.length() / 1024) + " KB）");
        return out;
    }

    private static int videoTrackOf(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }
}
