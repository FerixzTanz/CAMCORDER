package com.kooo.evcam.storage;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 行车日志：哪一趟从什么时候到什么时候、开了多远、中间出过什么事（2.10.8）。回放按它把录像分成「一趟一趟」。
 *
 * <h3>一趟</h3>
 *
 * <ul>
 *   <li>开始：录像开起来的那一刻。上车 / 换出 P 挡开的（{@code Why.DRIVE}）一定是新的一趟；
 *       别的原因（接回、亮屏接回、手动）开的，上一趟还没结束就接着算，结束了才另起一趟。</li>
 *   <li>结束：人下车了（{@code DriveSessionWatcher} 判的，录着没录着都算）。</li>
 *   <li>中间录像断了又接上、在车里坐着熄过屏，都还是这一趟。</li>
 * </ul>
 *
 * <h3>文件</h3>
 *
 * <p>和录像同一个目录：{@code DCIM/EVCam_Video/drives.txt}，一行一件事，时间是系统时间（和录像文件名同一个钟）：</p>
 * <pre>
 *   S 1791456067758 24814.0     一趟开始，里程表 km（读不到写 -）
 *   B 1791456123456 0.62        急刹车，减速度 g
 *   F 1791456200000             闪远光锁定
 *   E 1791458000000 24855.3     一趟结束
 * </pre>
 *
 * <p>只往后追加，几十字节一行。写在自己的线程上；解析和「一趟」的模型在 {@link DriveRecords}（纯函数，见 {@code DriveRecordsTest}）。</p>
 */
public final class DriveLog {

    private static final String TAG = "DriveLog";
    public static final String FILE_NAME = "drives.txt";
    /** 比这大就不读（一趟几十字节，一万趟也才几百 KB）。 */
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String HEADER = "# Zeekr Shortcut: drive log. S start, E end (epoch ms, odometer km); "
            + "B hard braking (g); F flash-to-pass lock.";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DriveLog");
        thread.setDaemon(true);
        return thread;
    });

    private DriveLog() {
    }

    // ================================================================= 读

    /** 录像目录里的日志；没有或读不出为空。会碰盘，别在主线程上调。 */
    public static List<DriveRecords.Drive> read(File dir) {
        File file = dir == null ? null : new File(dir, FILE_NAME);
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        if (file.length() > MAX_BYTES) {
            // 盘坏了、或者有人塞了个大文件：整个读进来会内存不够、进程崩，录像跟着停（安全审查 2026-10-08）
            AppLog.w(TAG, "行车日志太大，不读: " + file + " " + file.length());
            return Collections.emptyList();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return DriveRecords.parse(sb.toString());
        } catch (IOException e) {
            AppLog.w(TAG, "读不出行车日志 " + file + ": " + e);
            return Collections.emptyList();
        }
    }

    // ================================================================= 写

    /**
     * 录像开起来了（{@code RecordingCoordinator}，主线程）。
     *
     * @param gotIn 是上车 / 换出 P 挡开的：一定是新的一趟
     */
    public static void recordingStarted(Context context, boolean gotIn) {
        final Context app = context.getApplicationContext();
        final Double odo = odometer();
        final long now = System.currentTimeMillis();
        IO.execute(() -> {
            // 找录像目录会碰盘（查 U 盘、建目录）：放在自己的线程上，不卡主线程（审查 2026-10-08）
            final File dir = StorageHelper.getVideoDir(app);
            if (!gotIn && DriveRecords.continuesLast(read(dir), now)) {
                return;   // 接着上一趟
            }
            append(dir, "S " + now + " " + odoText(odo));
        });
    }

    /** 人下车了（{@code DriveSessionWatcher}，主线程）：这一趟结束。没有开着的一趟就什么都不写。 */
    public static void driverLeft(Context context) {
        final Context app = context.getApplicationContext();
        final Double odo = odometer();
        final long now = System.currentTimeMillis();
        IO.execute(() -> {
            final File dir = StorageHelper.getVideoDir(app);
            // 只结束「还算数」的那一趟：很久以前进程没了留下的那一趟没结束，这里补上结束会把它拉成好几天（审查 2026-10-08）。
            // 结束的门槛比「接着算」宽（24 小时）：开了 6 个小时以上的长途照样记得到结束、算得出里程
            if (DriveRecords.closesLast(read(dir), now)) {
                append(dir, "E " + now + " " + odoText(odo));
            }
        });
    }

    /** 一件事（急刹车、闪远光锁定）。没有开着的一趟时也写：读的时候会丢掉，写的时候不用先读。 */
    public static void event(Context context, char type, double value) {
        final Context app = context.getApplicationContext();
        final long now = System.currentTimeMillis();
        final String line = type == DriveRecords.Event.BRAKE
                ? String.format(Locale.US, "%c %d %.2f", type, now, value)
                : type + " " + now;
        IO.execute(() -> append(StorageHelper.getVideoDir(app), line));
    }

    private static Double odometer() {
        try {
            Float km = Telemetry.get().readings().number(Signal.ODOMETER);
            return km == null ? null : km.doubleValue();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String odoText(Double km) {
        return km == null ? "-" : String.format(Locale.US, "%.1f", km);
    }

    private static void append(File dir, String line) {
        if (dir == null || !(dir.isDirectory() || dir.mkdirs())) {
            return;
        }
        File file = new File(dir, FILE_NAME);
        boolean fresh = !file.exists();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (fresh) {
                writer.write(HEADER + "\n");
            }
            writer.write(line + "\n");
        } catch (IOException e) {
            AppLog.w(TAG, "行车日志写不进 " + file + ": " + e);
        }
    }
}
