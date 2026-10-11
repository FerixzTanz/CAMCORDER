package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 手机上那一页按文件类型走哪条：图片、视频直接显示，别的（诊断报告）给下载按钮。 */
public class FileShareServerTest {

    @Test
    public void picturesAndVideosAreShownInline() {
        assertTrue(FileShareServer.isMedia("20260926_100000_surround.mp4"));
        assertTrue(FileShareServer.isMedia("20260926_100000_surround.JPG"));
        assertTrue(FileShareServer.isMedia("a.png"));
    }

    @Test
    public void aReportIsADownload() {
        assertFalse(FileShareServer.isMedia("zeekr_diagnostics_20260926_100059.json"));
        assertEquals("application/json; charset=utf-8",
                FileShareServer.mimeTypeOf("zeekr_diagnostics_20260926_100059.json"));
        assertEquals("text/plain; charset=utf-8", FileShareServer.mimeTypeOf("evcam.log"));
        assertEquals("application/octet-stream", FileShareServer.mimeTypeOf("x.bin"));
    }

    /** 整份读完再关：算发完。 */
    @Test
    public void readingEverythingCountsAsDone() throws Exception {
        boolean[] result = new boolean[2];
        FileShareServer.Watched in = new FileShareServer.Watched(
                new java.io.ByteArrayInputStream(new byte[100]), 100, done -> {
                    result[0] = true;
                    result[1] = done;
                });
        byte[] buf = new byte[64];
        while (in.read(buf, 0, buf.length) > 0) {
            // 读完
        }
        in.close();
        assertTrue(result[0]);
        assertTrue(result[1]);
    }

    /** 没读完就关了（手机断了）：算断了；关两次只报一次。 */
    @Test
    public void closingEarlyCountsAsStoppedOnce() throws Exception {
        int[] calls = new int[1];
        boolean[] done = new boolean[1];
        FileShareServer.Watched in = new FileShareServer.Watched(
                new java.io.ByteArrayInputStream(new byte[100]), 100, d -> {
                    calls[0]++;
                    done[0] = d;
                });
        in.read(new byte[10], 0, 10);
        in.close();
        in.close();
        assertEquals(1, calls[0]);
        assertFalse(done[0]);
    }
}
