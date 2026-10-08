package com.kooo.evcam;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程里自己跟自己说话（2.11.0 安全审查）：录制状态变了、悬浮按钮要切换录制。
 *
 * <p>以前这两件事走的是广播。动态注册的接收器在 Android 13 以前<b>一律对外开放</b>
 * （RECEIVER_NOT_EXPORTED 从 API 33 才算数，车机是 Android 12），而应用用的是公开的测试签名，
 * 签名级权限也挡不住 —— 车机上随便哪个应用发一条广播，就能悄悄停掉录像、让悬浮按钮显示错的状态，
 * 或者塞个读不出来的 extra 把整个进程（连同在录的录像）弄崩。都在一个进程里，直接调就够了。</p>
 *
 * <p>回调一律在主线程上。</p>
 */
public final class InAppEvents {

    /** 录制状态变了。 */
    public interface RecordingStateListener {
        void onRecordingStateChanged(boolean recording);
    }

    /** 悬浮按钮要求切换录制（主界面在前台时由它来做）。 */
    public interface ToggleListener {
        void onToggleRecording();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<RecordingStateListener> STATE = new CopyOnWriteArrayList<>();
    private static volatile ToggleListener toggle;

    private InAppEvents() {
    }

    public static void addRecordingStateListener(RecordingStateListener listener) {
        if (listener != null && !STATE.contains(listener)) {
            STATE.add(listener);
        }
    }

    public static void removeRecordingStateListener(RecordingStateListener listener) {
        STATE.remove(listener);
    }

    /** 告诉所有在听的：录着 / 没录。哪个线程调都行。 */
    public static void recordingStateChanged(boolean recording) {
        MAIN.post(() -> {
            for (RecordingStateListener listener : STATE) {
                listener.onRecordingStateChanged(recording);
            }
        });
    }

    /** 主界面登记自己来处理「切换录制」；传 null 注销（只注销自己登记的那一个）。 */
    public static void setToggleListener(ToggleListener listener) {
        toggle = listener;
    }

    public static void clearToggleListener(ToggleListener listener) {
        if (toggle == listener) {
            toggle = null;
        }
    }

    /** @return 有没有人接（主界面不在时没人接，调用方自己处理） */
    public static boolean requestToggle() {
        ToggleListener listener = toggle;
        if (listener == null) {
            return false;
        }
        MAIN.post(listener::onToggleRecording);
        return true;
    }
}
