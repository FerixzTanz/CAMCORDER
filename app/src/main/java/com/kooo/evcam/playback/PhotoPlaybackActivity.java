package com.kooo.evcam.playback;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.profile.RecordSpecs;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 照片查看器（2.13 起照车机自带相册的查看页排）：看一组照片，底下一条胶片在相邻几组之间走。
 *
 * <p>挑哪一组、多选、批量删除都在回看网格（{@link GalleryActivity}）里；这里只管看
 * 这一组、锁定 / 分享 / 发到手机 / 删除这一组。进来时带着哪一组的时间戳
 * （{@link #EXTRA_GROUP_PREFIX}）就先放哪一组，没带就放最新的。</p>
 *
 * <h3>只有一套视图</h3>
 *
 * <p>点一格就让那一格占满整块，做的只是<b>把别的几格收起来</b> —— 没有第二套布局、
 * 没有第二个 ImageView，同一张图从头到尾只解一次码。主界面预览就是这么做的。</p>
 *
 * <p>点一下就到位：网格里点环视的某一路，直接展开并放大那一路，不必先展开再点；
 * 再点一下回到网格，中间不停在「整张环视」那一层。
 * 手势全走 {@code setOnClickListener} —— 只要还留着双击，单击就得等
 * 300ms 的双击判定，那正是「预览比回看弹得快」的来源。</p>
 *
 * <h3>为什么是独立 Activity</h3>
 *
 * <p>以前它是嵌在主界面里的一个 Fragment，主界面只是把录制那一层
 * {@code setVisibility(GONE)} —— 于是<b>主界面从头到尾没有 pause 过</b>，
 * 相机全程在采集、在出帧，只是被这一层盖住了，白白发热耗电。</p>
 */
public class PhotoPlaybackActivity extends AppCompatActivity {

    /** 进来先放哪一组：{@link PhotoGroup#getTimestampPrefix()}。 */
    public static final String EXTRA_GROUP_PREFIX = "group_prefix";

    private static final String TAG = "PhotoPlaybackActivity";

    // UI 组件
    private RecyclerView photoList;
    private TextView emptyText;
    private TextView currentDatetime;
    private MaterialButton btnLock;
    private View btnViewMode;

    // 预览区组件
    private View multiViewLayout;
    private ImageView imageFront, imageBack, imageLeft, imageRight;
    private FrameLayout frameFront, frameBack, frameLeft, frameRight;
    /** 两路座舱那一列。两格都没有文件时整列让开，环视独占整块。 */
    private View cabinColumn;
    private TextView labelFront, labelBack, labelLeft, labelRight;
    /** 只有环视那一格有：别的几格没有文件时整个收起来，没有地方需要说「无照片」。 */
    private TextView placeholderFront;
    /** 「锁定影像」开着没有：扫描时读一次，回到前台时再看一眼。 */
    private boolean lockEnabled;
    /** 照片目录里锁定的文件名（开关关着时是空的）。 */
    private Set<String> lockedPhotos = new HashSet<>();
    private View controlsLayout;

    // 数据：所有组，最新的在前（胶片就是这个顺序）
    private final List<PhotoGroup> groups = new ArrayList<>();
    private FilmstripAdapter<PhotoGroup> strip;
    private PhotoGroup currentGroup;

    // 状态
    /**
     * 现在是哪一路占满整块；{@code null} 表示摆成网格。
     *
     * <p>原来这里是 {@code isSingleMode} 加 {@code currentSinglePosition} 两个字段，
     * 而它们只有三种合法组合 —— 两个字段表达一件事，迟早会对不上。</p>
     */
    private String expandedPosition;
    /**
     * 放大到环视的哪一格；{@link PlaybackViewport#NO_CELL} 表示整张。
     *
     * <p>只有环视有格子可放 —— 它本身就是一张 2×2。座舱是一整幅画面，点了不动。</p>
     */
    private int zoomedCell = PlaybackViewport.NO_CELL;
    /** 最近一次按在哪 —— 点击回调不带坐标，而「点的是哪一路」全看这个。 */
    private float lastTouchX, lastTouchY;
    /** 四路的顺序。分享、循环切换都按这个走，省得各写一份。 */
    private static final String[] POSITIONS = {
            PhotoGroup.POSITION_FRONT, PhotoGroup.POSITION_BACK,
            PhotoGroup.POSITION_LEFT, PhotoGroup.POSITION_RIGHT,
    };
    /** 鱼眼校正：只改屏幕上的样子，原图不动。开关记在设置里（工具面板、录像回看上拨）。 */
    private boolean fisheyeOn;
    /** 开关和参数的监听。拿住它：SharedPreferences 只弱引用监听器。 */
    private SharedPreferences.OnSharedPreferenceChangeListener fisheyeListener;
    /** 进来时要先放的那一组；放过一次就清掉。 */
    private String wantedPrefix;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_playback);
        wantedPrefix = getIntent().getStringExtra(EXTRA_GROUP_PREFIX);

        initViews();
        setupListeners();
        setupTapToExpand();
        updatePhotoList();
        applyStatusBarInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (com.kooo.evcam.storage.FootageLocks.enabled(this) != lockEnabled) {
            // 设置里刚拨过「锁定影像」：重新扫一遍，按钮、标记都跟着
            updatePhotoList();
        }
    }

    @Override
    protected void onDestroy() {
        new AppConfig(PhotoPlaybackActivity.this).removeFisheyeListener(fisheyeListener);
        fisheyeListener = null;
        super.onDestroy();
    }

    private void initViews() {
        fisheyeOn = new AppConfig(PhotoPlaybackActivity.this).isFisheyeCorrection();
        currentDatetime = findViewById(R.id.current_datetime);
        btnLock = findViewById(R.id.pb_lock);

        photoList = findViewById(R.id.photo_list);
        emptyText = findViewById(R.id.empty_text);

        // 四宫格预览
        multiViewLayout = findViewById(R.id.multi_view_layout);

        imageFront = findViewById(R.id.image_front);
        imageBack = findViewById(R.id.image_back);
        imageLeft = findViewById(R.id.image_left);
        imageRight = findViewById(R.id.image_right);

        // 展开、收起都会改变这一格的大小，而放大用的矩阵是按当时的尺寸算出来的：
        // 布局一变就得重算，否则画面会停在按旧尺寸算的位置上
        imageFront.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, orr, ob) -> {
            if (l != ol || top != ot || r != orr || b != ob) {
                applyCellZoom();
            }
        });

        frameFront = findViewById(R.id.frame_front);
        frameBack = findViewById(R.id.frame_back);
        frameLeft = findViewById(R.id.frame_left);
        frameRight = findViewById(R.id.frame_right);
        cabinColumn = findViewById(R.id.cabin_column);

        labelFront = findViewById(R.id.label_front);
        labelBack = findViewById(R.id.label_back);
        labelLeft = findViewById(R.id.label_left);
        labelRight = findViewById(R.id.label_right);

        // 角标叫什么和主界面同一个来源：布局里那四个「前后左右」说的是合成流的
        // 四个方向，而这里每一格是一路相机 —— 三路配置下就成了环视写着「前」
        nameLane(labelFront, "front");
        nameLane(labelBack, "back");
        nameLane(labelLeft, "left");
        nameLane(labelRight, "right");

        placeholderFront = findViewById(R.id.placeholder_front);

        btnViewMode = findViewById(R.id.btn_view_mode);
        controlsLayout = findViewById(R.id.controls_layout);

        // 胶片：横着一排，最新的在前
        strip = new FilmstripAdapter<>(new FilmstripAdapter.Binder<PhotoGroup>() {
            @Override public File thumbnail(PhotoGroup g) { return g.getThumbnailFile(); }
            @Override public boolean isVideo(PhotoGroup g) { return false; }
            @Override public String label(PhotoGroup g) { return g.getFormattedTime(); }
        });
        strip.setOnPick((group, index) -> loadPhotoGroup(group));
        photoList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        photoList.setAdapter(strip);
    }

    private void setupListeners() {
        // 鱼眼校正。开关在工具面板和录像回看上拨，这里只管开关或参数变了之后的事：
        // 改的是「怎么画」，把当前这一组重新贴一遍
        fisheyeListener = new AppConfig(PhotoPlaybackActivity.this).onFisheyeChanged(() -> {
            fisheyeOn = new AppConfig(PhotoPlaybackActivity.this).isFisheyeCorrection();
            if (currentGroup != null) {
                loadPhotoGroup(currentGroup);
            }
        });

        // 返回 / 全部照片：都是回到网格，关掉自己就回去了
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_all).setOnClickListener(v -> finish());
        findViewById(R.id.pb_info).setOnClickListener(v -> showInfo());
        findViewById(R.id.pb_share).setOnClickListener(v -> shareCurrent());
        findViewById(R.id.pb_send).setOnClickListener(v -> sendCurrentPhotoToPhone());
        findViewById(R.id.pb_delete).setOnClickListener(v -> confirmDeleteCurrent());
        btnLock.setOnClickListener(v -> toggleLockGroup());
        btnViewMode.setOnClickListener(v -> cycleViewMode());
    }

    /**
     * 点一下就到位 —— 和主界面预览同一套。
     *
     * <p>原来要双击才放大，环视还得先双击放大、再单击那一路，两步。而且第一步
     * 走的是 {@code onSingleTapConfirmed}：那个回调要等双击判定的 300ms 过去才发，
     * <b>「预览弹得比回看快」就是这 300ms</b>，不是解码，也不是别的。</p>
     *
     * <p>这里用的是最朴素的 {@code setOnClickListener}：按下就算数，没有等待。
     * 坐标由按下时记一笔，点击回调本身不带坐标。</p>
     */
    private void setupTapToExpand() {
        tapExpands(frameFront, PhotoGroup.POSITION_FRONT);
        tapExpands(frameBack, PhotoGroup.POSITION_BACK);
        tapExpands(frameLeft, PhotoGroup.POSITION_LEFT);
        tapExpands(frameRight, PhotoGroup.POSITION_RIGHT);
    }

    private void tapExpands(View frame, String position) {
        if (frame == null) {
            return;
        }
        frame.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
            }
            return false;   // 不拦，点击照常走 —— 按压反馈和无障碍都在那条路上
        });
        frame.setOnClickListener(v -> tapped(position));
    }

    /**
     * 点了某一格之后。
     *
     * <ul>
     *   <li>网格里点一格 → 这一格占满整块。环视还顺带把<b>点到的那一路</b>放大：
     *       想看哪一路，一下到位。</li>
     *   <li>占满的环视上再点 → 放大那一路。</li>
     *   <li><b>已经放大了的话，点哪儿都是收回网格。</b>「整张环视、座舱还藏着」那一层
     *       不是一个要停留的画面：看完一路要回到全貌，没有理由多点一下。
     *       只有环视的那一组本来就看不出差别 —— 网格里也只有它一格。</li>
     *   <li>占满的座舱上再点 → 一整幅画面，没有格子可分，直接收回网格。</li>
     * </ul>
     */
    private void tapped(String position) {
        if (currentGroup == null || !currentGroup.hasPhoto(position)) {
            return;
        }
        if (expandedPosition == null) {
            expandedPosition = position;
            zoomedCell = gridColumns(position) >= 2 ? cellUnderTouch() : PlaybackViewport.NO_CELL;
            applyViewMode();
            return;
        }
        if (gridColumns(position) < 2) {
            collapse();
            return;
        }
        int cell = cellUnderTouch();
        if (zoomedCell != PlaybackViewport.NO_CELL || cell == PlaybackViewport.NO_CELL) {
            collapse();   // 放大着的时候点哪儿都是退回去；黑边上也没有画面可点
            return;
        }
        zoomedCell = cell;
        applyCellZoom();
    }

    /** 手指落在环视照片的哪一路上；落在 fitCenter 留出的黑边上返回 NO_CELL。 */
    private int cellUnderTouch() {
        Drawable drawable = imageFront == null ? null : imageFront.getDrawable();
        if (drawable == null) {
            return PlaybackViewport.NO_CELL;
        }
        return PlaybackViewport.cellAtInPicture(lastTouchX, lastTouchY,
                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
                imageFront.getWidth(), imageFront.getHeight());
    }

    /**
     * 把当前这张照片发到手机上：放大着哪一路就发哪一路；什么都没放大时发环视那张
     * （项目拥有者 2026-09-26 定）。以前没放大时只提示「先点一路放大」，多一步。
     *
     * <p>这一组没拍到环视时，按 {@link #POSITIONS} 的顺序发第一张有的。</p>
     */
    private void sendCurrentPhotoToPhone() {
        String position = currentGroup == null ? null
                : expandedPosition != null ? expandedPosition : firstPhotoPosition();
        if (position == null) {
            Toast.makeText(PhotoPlaybackActivity.this, R.string.share_phone_no_file,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        com.kooo.evcam.share.PhoneShare.show(PhotoPlaybackActivity.this,
                currentGroup.getPhotoFile(position));
    }

    /** 这一组里按 {@link #POSITIONS} 顺序第一张有的照片（环视排第一）；一张都没有返回 null。 */
    private String firstPhotoPosition() {
        for (String position : POSITIONS) {
            if (currentGroup.hasPhoto(position)) {
                return position;
            }
        }
        return null;
    }

    /** 这一格装的是哪一路相机，名字和主界面同一个来源。 */
    private void nameLane(TextView label, String position) {
        if (label == null) {
            return;
        }
        label.setText(new com.kooo.evcam.AppConfig(PhotoPlaybackActivity.this)
                .getCameraName(PhotoPlaybackActivity.this,
                        com.kooo.evcam.camera.CameraSlots.keyForSuffix(position)));
    }

    /**
     * 现在该看什么：摆成网格，还是某一路占满整块。
     *
     * <p>让一路占满，做的只是<b>把别的几格收起来</b> —— 图还是那张图，位置还是那个
     * 位置，没有换布局、没有再解一次码。主界面预览就是这么做的，所以它是即时的。</p>
     *
     * <p>原来这里是另起一套 {@code single_view_layout} 加一个 ImageView：同一个文件
     * 要为它再解一次码，进出一次就是两次解码，而屏幕上从头到尾只有那一张图。</p>
     */
    private void applyViewMode() {
        boolean grid = expandedPosition == null;
        frameFront.setVisibility(grid || PhotoGroup.POSITION_FRONT.equals(expandedPosition)
                ? View.VISIBLE : View.GONE);
        boolean back = laneVisible(frameBack, PhotoGroup.POSITION_BACK);
        boolean left = laneVisible(frameLeft, PhotoGroup.POSITION_LEFT);
        boolean right = laneVisible(frameRight, PhotoGroup.POSITION_RIGHT);
        if (cabinColumn != null) {
            cabinColumn.setVisibility(back || left || right ? View.VISIBLE : View.GONE);
        }
        applyCellZoom();
        ((MaterialButton) btnViewMode).setText(grid
                ? getString(R.string.photo_mode_multi)
                : getString(R.string.photo_mode_single, getPositionLabel(expandedPosition)));
    }

    /**
     * 座舱那几格该不该出现：有文件，而且没有别的一路正占着整块。
     *
     * @return 这一格现在是不是看得见
     */
    private boolean laneVisible(View frame, String position) {
        boolean show = currentGroup != null && currentGroup.hasPhoto(position)
                && (expandedPosition == null || position.equals(expandedPosition));
        if (frame != null) {
            frame.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        return show;
    }

    /** 收回网格。 */
    private void collapse() {
        expandedPosition = null;
        zoomedCell = PlaybackViewport.NO_CELL;
        applyViewMode();
    }

    /**
     * 底部那个按钮：网格 → 有图的每一路 → 回到网格。
     *
     * <p>点画面已经能到任何一路了，这个按钮留着是因为它同时是<b>现在在看哪一路</b>
     * 的标签 —— 而且从放大的画面退回网格，不必非得知道「点黑边」这条规矩。</p>
     */
    private void cycleViewMode() {
        if (currentGroup == null) {
            return;
        }
        List<String> order = new ArrayList<>();
        order.add(null);            // 网格
        for (String position : POSITIONS) {
            if (currentGroup.hasPhoto(position)) {
                order.add(position);
            }
        }
        int at = order.indexOf(expandedPosition);
        expandedPosition = order.get((Math.max(at, 0) + 1) % order.size());
        zoomedCell = PlaybackViewport.NO_CELL;
        applyViewMode();
    }

    /**
     * 获取位置对应的标签
     */
    private String getPositionLabel(String position) {
        // 这里要的是<b>相机的名字</b>（环视 / 前座舱 / 后座舱），不是格子的方位。
        // 以前用的是 zeekr_lane_* —— 那是环视那张 2×2 里四个格子的名字，
        // 和「这是哪一路相机」是两回事，摆在框上会让人以为四个框就是前后左右
        return new AppConfig(this).getCameraName(this,
                com.kooo.evcam.camera.CameraSlots.keyForSuffix(position));
    }

    /**
     * 加载图片组进行显示
     */
    private void loadPhotoGroup(PhotoGroup group) {
        this.currentGroup = group;
        emptyText.setVisibility(View.GONE);
        multiViewLayout.setVisibility(View.VISIBLE);
        controlsLayout.setVisibility(View.VISIBLE);
        markOnStrip(group);

        // 正占着整块的那一路，这一组里没有的话就收回网格
        if (expandedPosition != null && !group.hasPhoto(expandedPosition)) {
            expandedPosition = null;
        }
        // 换了图，取景回到整张 —— 留着上一张的放大矩形，会把新图按别人的格子切
        zoomedCell = PlaybackViewport.NO_CELL;

        currentDatetime.setText(group.getFormattedDateTime());
        updatePhotoDisplay(group);
        updateLockButton();
    }

    // ------------------------------------------------------------------ 锁定影像

    /** 按钮说的是这一组：整组都锁着画「锁上了」（点了解锁），否则画「开着」（点了锁定）。 */
    private void updateLockButton() {
        if (btnLock == null) {
            return;
        }
        btnLock.setVisibility(lockEnabled && currentGroup != null ? View.VISIBLE : View.GONE);
        if (lockEnabled && currentGroup != null) {
            boolean locked = lockedPhotos.containsAll(currentGroup.fileNames());
            btnLock.setIconResource(locked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
            btnLock.setContentDescription(getString(locked ? R.string.action_unlock_footage : R.string.action_lock_footage));
        }
    }

    /** 锁定 / 解锁这一组（项目所有者 2026-10-03：照片直接锁一整组）。 */
    private void toggleLockGroup() {
        if (currentGroup == null || currentGroup.getPhotoCount() == 0) {
            return;
        }
        final List<String> names = currentGroup.fileNames();
        final boolean unlock = lockedPhotos.containsAll(names);
        btnLock.setEnabled(false);
        com.kooo.evcam.storage.FootageLocks.set(StorageHelper.getPhotoDir(this), names, !unlock, ok -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            btnLock.setEnabled(true);
            if (!ok) {
                Toast.makeText(this, R.string.msg_footage_lock_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (unlock) {
                lockedPhotos.removeAll(names);
            } else {
                lockedPhotos.addAll(names);
            }
            Toast.makeText(this, getResources().getQuantityString(
                    unlock ? R.plurals.msg_footage_unlocked : R.plurals.msg_footage_locked,
                    names.size(), names.size()), Toast.LENGTH_SHORT).show();
            updateLockButton();
        });
    }

    /**
     * 把这一组的图贴上去。
     *
     * <p>这里只管<b>贴哪几张</b>；谁显示、谁占多大归 {@link #applyViewMode()}。</p>
     *
     * <p>没有文件的那一路要把图清掉：那一格反正会收起来，但留着上一组的图，
     * 下次它重新出现时会先闪一下别人的照片。</p>
     */
    private void updatePhotoDisplay(PhotoGroup group) {
        for (String position : POSITIONS) {
            loadLane(imageFor(position), group, position);
        }
        // 环视那一格一直留着：它是这个界面的主画面，连它都没有，总得有地方说一声
        placeholderFront.setVisibility(
                group.hasPhoto(PhotoGroup.POSITION_FRONT) ? View.GONE : View.VISIBLE);
        applyViewMode();
    }

    private void loadLane(ImageView image, PhotoGroup group, String position) {
        if (image == null) {
            return;
        }
        if (group.hasPhoto(position)) {
            loadImage(group.getPhotoFile(position), image, position);
        } else {
            // clear 而不是只置空：上一组的加载可能还在路上，不取消的话它回来时
            // 会把图贴进一个「这一组没有这一路」的格子里
            Glide.with(PhotoPlaybackActivity.this).clear(image);
            image.setImageDrawable(null);
        }
    }

    private ImageView imageFor(String position) {
        switch (position) {
            case PhotoGroup.POSITION_BACK: return imageBack;
            case PhotoGroup.POSITION_LEFT: return imageLeft;
            case PhotoGroup.POSITION_RIGHT: return imageRight;
            default: return imageFront;
        }
    }

    /**
     * 加载图片。
     *
     * <p>{@code position} 是这张图来自哪一路 —— 鱼眼校正要按它去查这一路是不是
     * 拼成四宫格存的：环视那一路一张图里装着四个画面，得一格一格校正。</p>
     */
    private void loadImage(File photoFile, ImageView imageView, String position) {
        if (photoFile == null || !photoFile.exists()) {
            return;
        }

        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(photoFile.lastModified()))
                .error(android.R.color.black);

        // 校正只对环视那一路：座舱是普通相机，一张图就是一个画面，不该动它
        int lanes = fisheyeOn ? gridColumns(position) : 1;
        if (lanes > 1) {
            AppConfig config = new AppConfig(PhotoPlaybackActivity.this);
            options = options.transform(new FisheyeTransformation(lanes, lanes,
                    config.getFisheyeFov(), config.getFisheyeProjection()));
        }
        options = options.placeholder(keepShowing(imageView));

        Glide.with(PhotoPlaybackActivity.this)
                .load(photoFile)
                .apply(options)
                .into(imageView);
    }

    /**
     * 换图期间先接着显示现在这一帧。
     *
     * <p>拨校正开关等于重新贴一次图，而 Glide 一开始加载就会把 ImageView 清掉 ——
     * 占位图是黑的，看到的就是「画面黑一下再回来」。把当前这一帧拷一份当占位图，
     * 屏幕上就一直有画面。拷贝是必要的：原来那张属于 Glide 的池子，它随时会回收。</p>
     *
     * @return 占位图；现在还没有画面时返回 null，由 Glide 用空白顶着
     */
    private Drawable keepShowing(ImageView imageView) {
        Drawable current = imageView.getDrawable();
        if (!(current instanceof BitmapDrawable)) {
            return null;
        }
        Bitmap shown = ((BitmapDrawable) current).getBitmap();
        if (shown == null || shown.isRecycled()) {
            return null;
        }
        try {
            Bitmap copy = shown.copy(Bitmap.Config.ARGB_8888, false);
            return copy == null ? null : new BitmapDrawable(getResources(), copy);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "占位图拷贝不出来，换图时会闪一下: " + e);
            return null;
        }
    }

    /**
     * 把当前的取景贴到环视那一格上。
     *
     * <p>放大不是缩放整张图，是<b>换一个取景矩形</b>：把那一路映射到整格。
     * 连续回放对视频做的是同一件事，这里换成 ImageView 的矩阵而已。</p>
     */
    private void applyCellZoom() {
        if (imageFront == null) {
            return;
        }
        Drawable drawable = imageFront.getDrawable();
        float[] r = zoomedCell == PlaybackViewport.NO_CELL || drawable == null ? null
                : PlaybackViewport.imageRects(zoomedCell,
                        drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
                        imageFront.getWidth(), imageFront.getHeight());
        if (r == null) {
            imageFront.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageFront.setImageMatrix(new android.graphics.Matrix());
            return;
        }
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setRectToRect(
                new android.graphics.RectF(r[0], r[1], r[2], r[3]),
                new android.graphics.RectF(r[4], r[5], r[6], r[7]),
                android.graphics.Matrix.ScaleToFit.FILL);
        imageFront.setScaleType(ImageView.ScaleType.MATRIX);
        imageFront.setImageMatrix(matrix);
    }

    /**
     * 这一路的照片横竖各排了几路。
     *
     * <p>照片跟着这一路录制的排列走（见 {@code SingleCamera.saveBitmapAsJPEG}）：
     * 环视合成流拆四宫格，所以是 2；座舱那种普通相机是一整张，1。</p>
     */
    private int gridColumns(String position) {
        try {
            // 配置那边按内部 key 存，这里拿到的是对外的名字，翻一下
            return RecordSpecs.storedAsGrid(PhotoPlaybackActivity.this,
                    com.kooo.evcam.camera.CameraSlots.keyForSuffix(position)) ? 2 : 1;
        } catch (Exception e) {
            Log.w(TAG, "读不到 " + position + " 的排列，按不拆处理: " + e);
            return 1;
        }
    }


    /**
     * 扫一遍照片目录，胶片按最新的在前排好；然后放回原来看的那一组
     * （进来时带的那一组，或者刷新前看的那一组），没有就放最新的。
     */
    private void updatePhotoList() {
        // 屏幕上现在放的是哪一组。扫描之后 PhotoGroup 全是新对象，得靠时间戳把它认回来
        String shown = currentGroup != null ? currentGroup.getTimestampPrefix() : wantedPrefix;
        wantedPrefix = null;

        File saveDir = StorageHelper.getPhotoDir(PhotoPlaybackActivity.this);
        lockEnabled = com.kooo.evcam.storage.FootageLocks.enabled(PhotoPlaybackActivity.this);
        lockedPhotos = com.kooo.evcam.storage.FootageLocks.shown(PhotoPlaybackActivity.this, saveDir);

        groups.clear();
        for (DateSection<PhotoGroup> section : PhotoScan.scan(saveDir)) {
            groups.addAll(section.getItems());
        }
        strip.setItems(groups);

        if (groups.isEmpty()) {
            showEmpty();
            return;
        }
        multiViewLayout.setVisibility(View.VISIBLE);
        controlsLayout.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        PhotoGroup target = groups.get(0);
        if (shown != null) {
            for (PhotoGroup group : groups) {
                if (shown.equals(group.getTimestampPrefix())) {
                    target = group;
                    break;
                }
            }
        }
        loadPhotoGroup(target);
    }

    /** 一张都没有（全删光了）：画面收起，只留一句话。 */
    private void showEmpty() {
        currentGroup = null;
        expandedPosition = null;
        zoomedCell = PlaybackViewport.NO_CELL;
        multiViewLayout.setVisibility(View.GONE);
        controlsLayout.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        currentDatetime.setText("");
        updateLockButton();
    }

    /** 胶片上标出正在看的这一组，并滚到它。 */
    private void markOnStrip(PhotoGroup group) {
        int index = groups.indexOf(group);
        strip.setCurrent(index);
        if (index >= 0) {
            photoList.scrollToPosition(index);
        }
    }

    // ------------------------------------------------------------------ 详情 / 分享 / 删除

    private void showInfo() {
        if (currentGroup == null) {
            return;
        }
        AppConfig config = new AppConfig(this);
        List<String> names = new ArrayList<>();
        for (String position : POSITIONS) {
            if (currentGroup.hasPhoto(position)) {
                names.add(config.getCameraName(this, com.kooo.evcam.camera.CameraSlots.keyForSuffix(position)));
            }
        }
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.action_info)
                .setMessage(getString(R.string.info_photo_detail, currentGroup.getFormattedDateTime(),
                        currentGroup.getFormattedSize(), android.text.TextUtils.join(" · ", names)))
                .setPositiveButton(R.string.action_close, null));
    }

    private void shareCurrent() {
        if (currentGroup == null) {
            return;
        }
        PhotoShare.confirmAndShare(this, 1, PhotoShare.filesOf(Collections.singletonList(currentGroup)));
    }

    private void confirmDeleteCurrent() {
        if (currentGroup == null) {
            return;
        }
        final PhotoGroup group = currentGroup;
        com.kooo.evcam.ui.CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_delete_photos_title)
                .setMessage(getResources().getQuantityString(R.plurals.dlg_delete_photos_msg, 1, 1))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    // 锁定的留下（锁定影像关着时不算）
                    Set<String> keep = lockEnabled ? lockedPhotos : new HashSet<String>();
                    int deleted = group.deleteAllExcept(keep);
                    int kept = group.getPhotoCount();
                    Toast.makeText(this, getResources().getQuantityString(R.plurals.msg_photos_deleted, deleted, deleted)
                            + (kept > 0 ? getResources().getQuantityString(R.plurals.msg_kept_locked, kept, kept) : ""),
                            Toast.LENGTH_SHORT).show();
                    // 这一组整个没了就看下一组（胶片上它后面那张），否则留在原地
                    if (kept == 0) {
                        int at = groups.indexOf(group);
                        currentGroup = at >= 0 && at + 1 < groups.size() ? groups.get(at + 1)
                                : at > 0 ? groups.get(at - 1) : null;
                    }
                    updatePhotoList();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * 返回键：先退出当前这一层，最后才是离开这个界面。
     */
    @Override
    public void onBackPressed() {
        if (zoomedCell != PlaybackViewport.NO_CELL || expandedPosition != null) {
            // 和点画面一样，一下退回网格：中间那一层不值得让返回键多按一次
            collapse();
            return;
        }
        super.onBackPressed();
    }

    private void applyStatusBarInsets() {
        View toolbarView = findViewById(R.id.toolbar);
        if (toolbarView != null) {
            final int originalPaddingTop = toolbarView.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbarView, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbarView);
        }
    }
}
