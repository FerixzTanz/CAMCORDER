package com.kooo.evcam.ui;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.playback.FisheyeTransformation;
import com.kooo.evcam.playback.PhotoGroup;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面快门旁边那张「最近一张 / 一段」的缩略图。
 *
 * <p>拍照模式找照片目录里最新的一张（环视那一路，开了鱼眼校正就按四宫格校正，
 * 和回看里看到的一样）；录像模式找录像目录里最新的一段环视录像，取 1 秒处的一帧。
 * 目录扫描在后台线程，贴图交给 Glide；目录是空的就留一块灰。</p>
 */
public final class LastClipThumb {

    private static final ExecutorService SCAN = Executors.newSingleThreadExecutor();
    private static final long VIDEO_FRAME_US = 1_000_000L;

    private LastClipThumb() {
    }

    public static void load(ImageView view, boolean photoMode) {
        if (view == null) {
            return;
        }
        AppConfig config = new AppConfig(view.getContext());
        File dir = photoMode ? StorageHelper.getPhotoDir(view.getContext())
                : StorageHelper.getVideoDir(view.getContext());
        SCAN.execute(() -> {
            File newest = newest(dir, photoMode);
            view.post(() -> show(view, newest, photoMode, config));
        });
    }

    private static File newest(File dir, boolean photoMode) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File best = null;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            boolean matches = photoMode
                    ? (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                    && PhotoGroup.POSITION_FRONT.equals(PhotoGroup.extractPosition(file.getName()))
                    : name.endsWith(".mp4") && isSurround(file.getName());
            if (matches && (best == null || file.lastModified() > best.lastModified())) {
                best = file;
            }
        }
        return best;
    }

    /** 录像文件名里带的槽位：只认环视那一路，它才是四宫格 */
    private static boolean isSurround(String name) {
        String slot = com.kooo.evcam.zeekr.RecordingTimeline.parseCameraSlot(name);
        return slot == null || com.kooo.evcam.camera.CameraSlots.SURROUND.equals(slot);
    }

    private static void show(ImageView view, File file, boolean photoMode, AppConfig config) {
        if (file == null) {
            view.setImageDrawable(null);
            return;
        }
        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(file.lastModified()))
                .centerCrop();
        if (!photoMode) {
            options = options.frame(VIDEO_FRAME_US);
        } else if (config.isFisheyeCorrection()) {
            int lanes = com.kooo.evcam.profile.RecordSpecs.storedAsGrid(view.getContext(),
                    com.kooo.evcam.camera.CameraSlots.KEY_SURROUND) ? 2 : 1;
            if (lanes > 1) {
                options = options.transform(new FisheyeTransformation(lanes, lanes,
                        config.getFisheyeFov(), config.getFisheyeProjection()));
            }
        }
        Glide.with(view).load(file).apply(options).into(view);
    }
}
