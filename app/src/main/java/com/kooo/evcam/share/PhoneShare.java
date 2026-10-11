package com.kooo.evcam.share;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.util.List;

/**
 * 「发送到手机」：起一个临时的局域网服务，把二维码摆出来。
 *
 * <h3>为什么是扫码而不是配套 App</h3>
 *
 * <p>手机上不用装任何东西：扫码 → 浏览器打开 → 长按保存。
 * 代价是保存这一步要用户自己做，换来的是零安装。</p>
 *
 * <h3>服务活多久</h3>
 *
 * <p>只在这个对话框开着的时候。关掉就停 —— 一个能被局域网访问的文件服务，
 * 没有理由在用户已经不看它的时候继续开着。所以对话框里写明了
 * 保存完成前不要关闭（{@code share_phone_keep_open}）。</p>
 */
public final class PhoneShare {

    private static final String TAG = "PhoneShare";
    /** 分享最多开着多久。 */
    private static final long MAX_OPEN_MS = 10L * 60 * 1000;
    private static final int QR_SIZE_PX = 420;

    private PhoneShare() {
    }

    /**
     * 弹出发送对话框。
     *
     * @param file 要发送的那一个文件；为 null 或不存在时只提示，不弹窗
     */
    public static void show(Activity activity, File file) {
        show(activity, file, null);
    }

    /**
     * @param extraNote 额外的一句说明，显示在操作步骤下面；不需要时传 null。
     *                  视频用它讲清楚「只发当前这一段」。
     */
    public static void show(Activity activity, File file, String extraNote) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (file == null || !file.isFile() || file.length() <= 0) {
            toast(activity, activity.getString(R.string.share_phone_no_file));
            return;
        }

        // 只给手机可能连得上的地址（热点、无线、USB 共享），车机内部那一串不列（见 LocalNetwork.usable）
        List<LocalNetwork.Endpoint> endpoints = LocalNetwork.usable(LocalNetwork.enumerate());
        if (endpoints.isEmpty()) {
            // 没有可用地址就不是「失败」，是前提没满足 —— 说清楚该做什么
            com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.share_phone_need_hotspot_title)
                    .setMessage(activity.getString(R.string.share_phone_need_hotspot_msg)
                            + "\n\n" + activity.getString(R.string.share_phone_network_hint))
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }

        FileShareServer server = new FileShareServer(activity);
        try {
            server.start();
            server.share(file);
        } catch (Exception e) {
            // 异常原文（可能是 null，也可能是英文）只进日志，界面上只说没起来、请重试
            AppLog.e(TAG, "起不来分享服务", e);
            toast(activity, activity.getString(R.string.share_phone_failed));
            return;
        }
        AppLog.i(TAG, "分享 " + file.getName() + "，端口 " + server.getListeningPort()
                + "，可用地址 " + endpoints.size() + " 个：" + endpoints);
        ShareLog.add("sharing " + file.getName() + " (" + FileShareServer.readableSize(file.length())
                + ") on port " + server.getListeningPort() + ", addresses offered: " + endpoints);
        new Presenter(activity, server, endpoints, file, extraNote).show();
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    /** 对话框本身。拆出来是因为「切换地址」要改二维码，得留着几个引用。 */
    private static final class Presenter {
        private final Activity activity;
        private final FileShareServer server;
        private final List<LocalNetwork.Endpoint> endpoints;
        private final File file;
        private final String extraNote;

        private int index;
        /** 手机的请求到过车机没有：到过就不再提示「连不上」。 */
        private boolean reached;
        /** 手机打开过正确的链接：之后来的「链接不对」（别的设备、残缺链接）不再盖掉进度。 */
        private boolean opened;
        /** 图片、视频已经在手机上显示出来：之后预览、长按另存时的断开不再把提示退回「已连上」。 */
        private boolean shownOnPhone;
        private TextView statusText;
        private ImageView qrImage;
        private TextView addressText;
        private TextView qrFailed;

        Presenter(Activity activity, FileShareServer server,
                  List<LocalNetwork.Endpoint> endpoints, File file, String extraNote) {
            this.activity = activity;
            this.server = server;
            this.endpoints = endpoints;
            this.file = file;
            this.extraNote = extraNote;
        }

        void show() {
            int pad = dp(20);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad, pad, pad);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            qrImage = new ImageView(activity);
            LinearLayout.LayoutParams qrParams =
                    new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX);
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            qrImage.setLayoutParams(qrParams);
            root.addView(qrImage);

            qrFailed = text(activity.getString(R.string.share_phone_qr_failed), R.dimen.text_caption, false);
            qrFailed.setVisibility(android.view.View.GONE);
            root.addView(qrFailed);

            // 进度：等手机 → 手机连上了 → 发完了；等太久就说连不上、该怎么办（2026-10-11：以前扫了码
            // 打不开，车上看不出手机的请求到底有没有来，只能猜）
            statusText = text("", R.dimen.text_body, false);
            root.addView(statusText);

            root.addView(text(activity.getString(FileShareServer.isMedia(file.getName())
                    ? R.string.share_phone_steps : R.string.share_phone_steps_file), R.dimen.text_body, false));
            if (extraNote != null && !extraNote.isEmpty()) {
                root.addView(text(extraNote, R.dimen.text_caption, false));
            }

            addressText = text("", R.dimen.text_caption, true);
            root.addView(addressText);

            root.addView(text(activity.getString(R.string.share_phone_network_hint), R.dimen.text_caption, true));
            root.addView(text(activity.getString(R.string.share_phone_keep_open), R.dimen.text_caption, true));

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                    activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.share_phone_title)
                    .setView(root)
                    .setPositiveButton(R.string.action_close, null)
                    .setOnDismissListener(dialog -> stop());

            if (endpoints.size() > 1) {
                // 两块网卡同时有地址是常事，默认那个未必是手机连得上的那个。
                // 与其让人对着一个扫不通的码发呆，不如给一个「换一个」。
                builder.setNeutralButton(R.string.share_phone_switch_address, null);
            }

            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(d -> {
                com.kooo.evcam.ui.CamDialogs.paintNow(dialog);
                if (endpoints.size() > 1) {
                    // 直接拿按钮改点击：默认行为会关掉对话框，而换地址不该关
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                        index = (index + 1) % endpoints.size();
                        render();
                    });
                }
            });
            server.setListener((event, remote) -> closer.post(() -> onEvent(event)));
            dialog.show();
            render();
            // 分享最多开这么久；界面退到后台（按了主页、被别的界面盖住、整页被重建）也关 ——
            // 只靠「对话框关掉」来停服务的话，界面没正常收尾时服务会一直开着、那个链接一直能下（安全审查 2026-10-08）
            closer.postDelayed(dialog::dismiss, MAX_OPEN_MS);
            dialog.setOnDismissListener(d -> {
                closer.removeCallbacksAndMessages(null);
                stop();
            });
            if (activity instanceof androidx.lifecycle.LifecycleOwner) {
                ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().addObserver(
                        new androidx.lifecycle.DefaultLifecycleObserver() {
                            @Override
                            public void onStop(androidx.lifecycle.LifecycleOwner owner) {
                                owner.getLifecycle().removeObserver(this);
                                if (dialog.isShowing()) {
                                    dialog.dismiss();
                                } else {
                                    stop();
                                }
                            }
                        });
            }
        }

        private final android.os.Handler closer = new android.os.Handler(android.os.Looper.getMainLooper());

        /** 等这么久手机还没来，就提示连不上。 */
        private static final long UNREACHABLE_AFTER_MS = 20_000L;

        // 方法引用：字段初始化时 activity / endpoints 还没赋值，lambda 里直接读它们编不过
        private final Runnable unreachable = this::onUnreachable;

        private void onUnreachable() {
            if (!reached) {
                statusText.setText(activity.getString(R.string.share_status_unreachable,
                        endpoints.get(index).address));
                ShareLog.add("no request from the phone after " + (UNREACHABLE_AFTER_MS / 1000) + " s on "
                        + endpoints.get(index).address);
            }
        }

        /** 主线程上。 */
        private void onEvent(FileShareServer.Event event) {
            reached = true;
            closer.removeCallbacks(unreachable);
            boolean media = FileShareServer.isMedia(file.getName());
            switch (event) {
                case PAGE_OPENED:
                    opened = true;
                    statusText.setText(media ? R.string.share_status_connected_media : R.string.share_status_connected);
                    break;
                case DOWNLOAD_STARTED:
                    opened = true;
                    statusText.setText(R.string.share_status_sending);
                    break;
                case DOWNLOAD_DONE:
                    // 图片、视频是页面里直接显示的：「发完」只是手机上显示出来了，还得长按保存，别急着关
                    shownOnPhone = media;
                    statusText.setText(media ? R.string.share_status_done_media : R.string.share_status_done);
                    break;
                case DOWNLOAD_STOPPED:
                    // 视频、图片在手机浏览器里常先要一小段就断开（预览；NanoHTTPD 不认 Range），
                    // 断了不代表失败：回到「已连上」，不停在「正在发送」
                    if (!media) {
                        statusText.setText(R.string.share_status_stopped);
                    } else {
                        // 已经显示在手机上之后的断开（长按另存、拖视频进度）：回到「已在手机上」，别停在「正在发送」
                        statusText.setText(shownOnPhone ? R.string.share_status_done_media
                                : R.string.share_status_connected_media);
                    }
                    break;
                case WRONG_LINK:
                    if (!opened) {
                        statusText.setText(R.string.share_status_wrong_link);
                    }
                    break;
                default:
                    break;
            }
        }

        private void render() {
            LocalNetwork.Endpoint endpoint = endpoints.get(index);
            String url = server.urlFor(endpoint.address);
            Bitmap qr = QrCode.encode(url, QR_SIZE_PX);
            qrImage.setImageBitmap(qr);
            // 画不出来时那一格是白的，看上去和「界面坏了」没区别 —— 说出来
            qrFailed.setVisibility(qr == null ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            addressText.setText(activity.getString(R.string.share_phone_address,
                    url == null ? "" : url, file.getName(),
                    FileShareServer.readableSize(file.length())));
            if (!reached) {
                statusText.setText(activity.getString(R.string.share_status_waiting, endpoint.address));
                closer.removeCallbacks(unreachable);
                closer.postDelayed(unreachable, UNREACHABLE_AFTER_MS);
            }
            ShareLog.add("QR shows " + endpoint.address + ":" + server.getListeningPort() + " ("
                    + endpoint.interfaceName + ", " + endpoint.kind + ")");
            // 日志和诊断报告里不写带随机串的完整地址：那就是下载这个文件的钥匙（安全审查 2026-10-08）
            AppLog.i(TAG, "二维码地址: http://" + endpoint.address + ":" + server.getListeningPort()
                    + "/…（网卡 " + endpoint.interfaceName + "）"
                    + (qr == null ? "  << 二维码没画出来" : ""));
        }

        private boolean stopped;

        private void stop() {
            if (stopped) {
                return;   // 关对话框和界面退到后台可能都走到这里
            }
            stopped = true;
            // 先摘掉监听：stop 会断开还连着的手机，那时 Watched 报的「断了」不是真的断，别往已关的界面上写
            // （ShareLog 里仍会紧跟着「dialog closed」记一行 DOWNLOAD_STOPPED，按顺序看得懂）
            server.setListener(null);
            ShareLog.add("dialog closed, sharing stopped" + (reached ? "" : " (the phone never reached the head unit)"));
            try {
                server.stop();
                AppLog.d(TAG, "分享服务已停止");
            } catch (Exception e) {
                AppLog.w(TAG, "停止分享服务时出错: " + e);
            }
        }

    /** @param sizeDimen 字号体系里的一档（{@code R.dimen.text_*}），不要传字面 sp */
    private TextView text(String content, int sizeDimen, boolean secondary) {
            TextView view = new TextView(activity);
            view.setText(content);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(sizeDimen));
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            view.setPadding(0, dp(10), 0, 0);
            if (secondary) {
                view.setAlpha(0.7f);
            }
            view.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return view;
        }

        private int dp(int value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    activity.getResources().getDisplayMetrics());
        }
    }
}
