package com.kooo.evcam.playback;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.storage.FootageLocks;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.zeekr.TimelineFormat;
import com.kooo.evcam.zeekr.TimelinePlayerActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 回看（2.13 起）：左栏「照片 / 录像」，右边按日期分组的缩略图网格 —— 照车机自带相册的排法。
 *
 * <p>点一格进查看器：照片是 {@link PhotoPlaybackActivity}（带上这一组的时间戳），
 * 录像是 {@link TimelinePlayerActivity}（带上这一条的开始时刻）。多选、删除、分享都在这里；
 * 查看器只管看一张 / 一条。</p>
 *
 * <p>目录扫描在后台线程（{@link PhotoScan} / {@link VideoScan}），回来再贴到网格上。</p>
 */
public class GalleryActivity extends AppCompatActivity {

    public static final String EXTRA_TAB = "tab";
    public static final String TAB_PHOTOS = "photos";
    public static final String TAB_VIDEOS = "videos";

    private static final ExecutorService SCAN = Executors.newSingleThreadExecutor();

    private boolean videos;
    private RecyclerView grid;
    private TextView emptyText;
    private TextView title;
    private View tabPhotos;
    private View tabVideos;
    private View actions;
    private View selection;
    private TextView selectedCount;
    private View btnShare;
    private TextView btnSelectAll;

    private final List<DateSection<PhotoGroup>> photoSections = new ArrayList<>();
    private final List<DateSection<VideoScan.Item>> videoSections = new ArrayList<>();
    private GalleryAdapter<PhotoGroup> photoAdapter;
    private GalleryAdapter<VideoScan.Item> videoAdapter;
    /** 照片目录里锁定的文件名（锁定影像关着时是空的）。 */
    private Set<String> lockedPhotos = new HashSet<>();
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        videos = TAB_VIDEOS.equals(getIntent().getStringExtra(EXTRA_TAB));

        title = findViewById(R.id.gallery_title);
        grid = findViewById(R.id.gallery_grid);
        emptyText = findViewById(R.id.empty_text);
        actions = findViewById(R.id.gallery_actions);
        selection = findViewById(R.id.gallery_selection);
        selectedCount = findViewById(R.id.selected_count);
        btnShare = findViewById(R.id.btn_share);
        btnSelectAll = findViewById(R.id.btn_select_all);

        findViewById(R.id.btn_menu).setOnClickListener(v -> openDrawerOnMain());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> scan());
        findViewById(R.id.btn_select).setOnClickListener(v -> setSelecting(true));
        findViewById(R.id.btn_done).setOnClickListener(v -> setSelecting(false));
        btnSelectAll.setOnClickListener(v -> {
            if (adapter().allSelected()) {
                adapter().clearSelection();
            } else {
                adapter().selectAll();
            }
        });
        btnShare.setOnClickListener(v -> shareChosen());
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());

        tabPhotos = findViewById(R.id.tab_photos);
        tabVideos = findViewById(R.id.tab_videos);
        tab(tabPhotos, R.drawable.ic_photo, R.string.nav_photo_playback, false);
        tab(tabVideos, R.drawable.ic_video, R.string.nav_video_playback, true);

        photoAdapter = new GalleryAdapter<>(photoSections, new GalleryAdapter.Binder<PhotoGroup>() {
            @Override public File thumbnail(PhotoGroup g) { return g.getThumbnailFile(); }
            @Override public boolean isVideo(PhotoGroup g) { return false; }
            @Override public String caption(PhotoGroup g) { return g.getFormattedTime(); }
            @Override public boolean locked(PhotoGroup g) { return g.isLocked(lockedPhotos); }
        });
        photoAdapter.setOnTileClick(group -> {
            Intent intent = new Intent(this, PhotoPlaybackActivity.class);
            intent.putExtra(PhotoPlaybackActivity.EXTRA_GROUP_PREFIX, group.getTimestampPrefix());
            startActivity(intent);
        });
        photoAdapter.setOnSelectionChanged(this::updateSelectedCount);

        videoAdapter = new GalleryAdapter<>(videoSections, new GalleryAdapter.Binder<VideoScan.Item>() {
            @Override public File thumbnail(VideoScan.Item i) { return i.thumbnail; }
            @Override public boolean isVideo(VideoScan.Item i) { return true; }
            @Override public String caption(VideoScan.Item i) { return TimelineFormat.duration(i.session.totalDurationMs); }
            @Override public boolean locked(VideoScan.Item i) { return i.locked; }
        });
        videoAdapter.setOnTileClick(item -> {
            Intent intent = new Intent(this, TimelinePlayerActivity.class);
            intent.putExtra(TimelinePlayerActivity.EXTRA_SESSION_START_MS, item.session.startEpochMs);
            startActivity(intent);
        });
        videoAdapter.setOnSelectionChanged(this::updateSelectedCount);

        GridLayoutManager layout = new GridLayoutManager(this, 4);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter().getItemViewType(position) == GalleryAdapter.TYPE_HEADER ? 4 : 1;
            }
        });
        grid.setLayoutManager(layout);
        // 内容区多宽就排几列：一格大约 gallery_tile_width 宽
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l - v.getPaddingLeft() - v.getPaddingRight();
            int span = Math.max(2, width / getResources().getDimensionPixelSize(R.dimen.gallery_tile_width));
            if (span != layout.getSpanCount()) {
                layout.setSpanCount(span);
                layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        return adapter().getItemViewType(position) == GalleryAdapter.TYPE_HEADER ? span : 1;
                    }
                });
            }
        });
        showTab(videos);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 查看器里可能删过、锁过；设置里可能拨过「锁定影像」：回来重扫一遍
        scan();
    }

    private void tab(View row, int icon, int label, boolean isVideos) {
        ((ImageView) row.findViewById(android.R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(android.R.id.title)).setText(label);
        row.setOnClickListener(v -> {
            if (videos != isVideos) {
                setSelecting(false);
                showTab(isVideos);
                scan();
            }
        });
    }

    private GalleryAdapter<?> adapter() {
        return videos ? videoAdapter : photoAdapter;
    }

    private void showTab(boolean toVideos) {
        videos = toVideos;
        tabPhotos.setActivated(!videos);
        tabVideos.setActivated(videos);
        title.setText(videos ? R.string.nav_video_playback : R.string.nav_photo_playback);
        emptyText.setText(videos ? R.string.msg_no_surround_clips : R.string.photo_empty);
        grid.setAdapter(adapter());
        // 视频是按分段发送的，没有整批分享
        btnShare.setVisibility(videos ? View.GONE : View.VISIBLE);
        updateEmpty();
    }

    // ------------------------------------------------------------------ 扫描

    private void scan() {
        final boolean forVideos = videos;
        SCAN.execute(() -> {
            if (forVideos) {
                List<DateSection<VideoScan.Item>> result = VideoScan.scan(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || videos != forVideos) {
                        return;
                    }
                    videoSections.clear();
                    videoSections.addAll(result);
                    videoAdapter.rebuild();
                    updateEmpty();
                    updateSelectedCount();
                });
            } else {
                File dir = StorageHelper.getPhotoDir(this);
                List<DateSection<PhotoGroup>> result = PhotoScan.scan(dir);
                Set<String> locked = FootageLocks.shown(this, dir);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || videos != forVideos) {
                        return;
                    }
                    lockedPhotos = locked;
                    photoSections.clear();
                    photoSections.addAll(result);
                    photoAdapter.rebuild();
                    updateEmpty();
                    updateSelectedCount();
                });
            }
        });
    }

    private void updateEmpty() {
        boolean empty = adapter().getItemCount() == 0;
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ 选择

    private void setSelecting(boolean on) {
        adapter().setSelecting(on);
        actions.setVisibility(on ? View.GONE : View.VISIBLE);
        selection.setVisibility(on ? View.VISIBLE : View.GONE);
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        GalleryAdapter<?> adapter = adapter();
        selectedCount.setText(getString(R.string.msg_selected_n, adapter.chosenCount()));
        btnSelectAll.setText(adapter.allSelected() ? R.string.action_deselect_all : R.string.action_select_all);
    }

    private void shareChosen() {
        List<PhotoGroup> groups = photoAdapter.chosenItems();
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.msg_select_photos_first, Toast.LENGTH_SHORT).show();
            return;
        }
        PhotoShare.confirmAndShare(this, groups.size(), PhotoShare.filesOf(groups));
    }

    private void confirmDelete() {
        if (adapter().chosenCount() == 0) {
            Toast.makeText(this, R.string.msg_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (videos) {
            List<VideoScan.Item> items = videoAdapter.chosenItems();
            int files = 0;
            long bytes = 0L;
            for (VideoScan.Item item : items) {
                files += item.files.size();
                bytes += item.bytes;
            }
            CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.player_delete_title)
                    .setMessage(getResources().getQuantityString(R.plurals.player_delete_msg,
                            files, files, TimelineFormat.size(bytes)))
                    .setPositiveButton(R.string.action_delete, (d, w) -> deleteVideos(items))
                    .setNegativeButton(R.string.action_cancel, null));
        } else {
            List<PhotoGroup> groups = photoAdapter.chosenItems();
            CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.dlg_delete_photos_title)
                    .setMessage(getResources().getQuantityString(R.plurals.dlg_delete_photos_msg,
                            groups.size(), groups.size()))
                    .setPositiveButton(R.string.action_delete, (d, w) -> deletePhotos(groups))
                    .setNegativeButton(R.string.action_cancel, null));
        }
    }

    /** 删文件放后台线程：U 盘上一个文件删一下就要几十到几百毫秒，一次删几条就要卡好几秒。 */
    private void deleteVideos(List<VideoScan.Item> items) {
        if (busy) {
            return;
        }
        busy = true;
        SCAN.execute(() -> {
            int[] result = VideoScan.delete(this, items);
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, getResources().getQuantityString(R.plurals.player_deleted, result[0], result[0])
                        + (result[1] > 0 ? getResources().getQuantityString(R.plurals.msg_kept_locked, result[1], result[1]) : ""),
                        Toast.LENGTH_SHORT).show();
                setSelecting(false);
                scan();
            });
        });
    }

    private void deletePhotos(List<PhotoGroup> groups) {
        if (busy) {
            return;
        }
        busy = true;
        final Set<String> keep = FootageLocks.enabled(this) ? lockedPhotos : new HashSet<>();
        SCAN.execute(() -> {
            int deleted = 0;
            int kept = 0;
            for (PhotoGroup group : groups) {
                deleted += group.deleteAllExcept(keep);
                kept += group.getPhotoCount();
            }
            final int done = deleted;
            final int left = kept;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, getResources().getQuantityString(R.plurals.msg_photos_deleted, done, done)
                        + (left > 0 ? getResources().getQuantityString(R.plurals.msg_kept_locked, left, left) : ""),
                        Toast.LENGTH_SHORT).show();
                setSelecting(false);
                scan();
            });
        });
    }

    // ------------------------------------------------------------------ 导航

    @Override
    public void onBackPressed() {
        // 选择里按返回：先退出选择。直接离开会让人以为选择被「提交」了
        if (adapter().isSelecting()) {
            setSelecting(false);
            return;
        }
        super.onBackPressed();
    }

    /** 抽屉长在主界面上：回去并让它开着。 */
    private void openDrawerOnMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_OPEN_DRAWER, true);
        startActivity(intent);
        finish();
    }
}
