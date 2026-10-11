package com.kooo.evcam.share;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 最近几次「发送到手机」的经过，进诊断报告。
 *
 * <p>2026-10-11 扫码一直「连接超时」，车上和报告里都看不出手机的请求到底有没有到车机 ——
 * 只能猜是网的问题还是应用的问题，猜了一个小时。现在每次分享记下：给了哪个地址（不含随机串）、
 * 有没有请求进来、从哪来、发完没有。</p>
 *
 * <p>只在内存里，进程重启就没了；最多 {@link #MAX} 行。</p>
 */
public final class ShareLog {

    static final int MAX = 40;
    private static final List<String> LINES = new ArrayList<>();

    private ShareLog() {
    }

    public static void add(String line) {
        String stamped = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        synchronized (LINES) {
            LINES.add(stamped);
            while (LINES.size() > MAX) {
                LINES.remove(0);
            }
        }
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }
}
