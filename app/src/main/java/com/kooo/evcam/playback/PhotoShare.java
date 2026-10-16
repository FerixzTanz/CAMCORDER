package com.kooo.evcam.playback;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把几张照片交给系统分享。回看网格（选中几组）和照片查看器（当前这一组）都走这里。
 *
 * <p>先弹一个「有几张，分享 / 关闭」的确认框，再起系统的选择器。文件通过 FileProvider 给出去。</p>
 */
public final class PhotoShare {

    private static final String TAG = "PhotoShare";

    private PhotoShare() {
    }

    /** 这几组里有文件的照片，按 {@link PhotoGroup} 的四路顺序。 */
    public static List<File> filesOf(Iterable<PhotoGroup> groups) {
        List<File> files = new ArrayList<>();
        String[] positions = {PhotoGroup.POSITION_FRONT, PhotoGroup.POSITION_BACK,
                PhotoGroup.POSITION_LEFT, PhotoGroup.POSITION_RIGHT};
        for (PhotoGroup group : groups) {
            for (String position : positions) {
                File file = group.getPhotoFile(position);
                if (file != null && file.exists() && file.length() > 0) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    /** 确认框 → 系统分享。{@code groups} 是几组、{@code files} 是这几组里的照片。 */
    public static void confirmAndShare(Activity activity, int groups, List<File> files) {
        if (files.isEmpty()) {
            Toast.makeText(activity, R.string.msg_no_photos_to_share, Toast.LENGTH_SHORT).show();
            return;
        }
        String message = groups > 1
                ? activity.getResources().getQuantityString(R.plurals.photo_share_count,
                        groups, groups, files.size())
                : activity.getResources().getQuantityString(R.plurals.photo_share_total,
                        files.size(), files.size());
        CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.action_share_photos)
                .setMessage(message)
                .setPositiveButton(R.string.action_share, (dialog, which) -> share(activity, files))
                .setNegativeButton(R.string.action_close, null)
                .setCancelable(true));
    }

    public static void share(Activity activity, List<File> files) {
        if (files.isEmpty()) {
            return;
        }
        try {
            String authority = activity.getPackageName() + ".fileprovider";
            ArrayList<Uri> uris = new ArrayList<>();
            for (File file : files) {
                if (file.exists() && file.canRead()) {
                    uris.add(FileProvider.getUriForFile(activity, authority, file));
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(activity, files.size() == 1 ? R.string.msg_file_unreadable
                        : R.string.msg_nothing_to_share, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            intent.setType("image/jpeg");
            if (uris.size() == 1) {
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.action_share_photos));
            intent.putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.msg_share_photo_subject));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(intent, activity.getString(R.string.action_share_photos));
            if (chooser.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(chooser);
            } else {
                Toast.makeText(activity, R.string.msg_no_share_target, Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "分享图片失败: FileProvider 无法处理该文件路径", e);
            Toast.makeText(activity, R.string.msg_share_path_unsupported, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // 异常原文（英文、有时是 null）只进日志，界面上只说分享不了
            AppLog.e(TAG, "分享图片失败", e);
            Toast.makeText(activity, R.string.msg_share_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
