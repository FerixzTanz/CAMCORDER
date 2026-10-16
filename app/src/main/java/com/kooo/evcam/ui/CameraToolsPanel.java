package com.kooo.evcam.ui;

import android.content.Context;
import android.transition.Slide;
import android.transition.TransitionManager;
import android.util.Range;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.ImageAdjustManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面的工具面板（layout_camera_tools）：画面上只留一个「工具」圆钮，
 * 回看、设置、鱼眼校正、两个开关和画面调节全在这里。
 *
 * <p>开关行和画面调节各行用的是设置界面那几份行布局（pref_row / pref_row_seekbar /
 * pref_row_value），所以长得和设置里一个样；只是这里不走 Preference，
 * 自己填字、自己接点击。画面调节的模型还是 {@link ImageAdjustManager}，
 * 每改一个值就套到所有相机上并存下来 —— 原来那个悬浮窗（ImageAdjustFloatingWindow）
 * 做的就是这件事，现在窗口没了，逻辑搬到这里。</p>
 *
 * <p>相机还没起来时 {@link Host#adjustManager()} 是 null，调节那几行先写「不支持」；
 * 相机就绪后 {@link #refresh} 再填一次。</p>
 */
public final class CameraToolsPanel {

    public interface Host {
        void openVideos();
        void openPhotos();
        void openSettings();
        /** 画面调节的模型；相机还没起来时为 null。 */
        ImageAdjustManager adjustManager();
        /** 用户第一次动了画面调节：把「启用画面调节」打开并套到相机上。 */
        void onImageAdjustTouched();
        /** 「录制状态角标」开关拨了：主界面按新值画角标。 */
        void onStatsToggled(boolean enabled);
        /** 拨超级后视镜；返回拨完之后的真实状态（没有悬浮窗权限时拨不动，返回原状态）。 */
        boolean setRearView(boolean on);
        /** 拨悬浮按钮；返回拨完之后的真实状态。 */
        boolean setFloatingButton(boolean on);
        /** 退出应用（抽屉没了，退出从那儿搬到面板最底下）。 */
        void exitApp();
    }

    private final View panel;
    private final Host host;
    private final AppConfig config;
    private final LayoutInflater inflater;
    private final LinearLayout adjustRows;

    private SeekBar exposureBar;
    private TextView exposureValue;
    private final List<Runnable> refreshers = new ArrayList<>();
    /** 开关行按真实状态重摆：后视镜、悬浮按钮也可能在设置里或悬浮窗自己关掉 */
    private final List<Runnable> switchSyncs = new ArrayList<>();

    public CameraToolsPanel(View panel, Host host) {
        this.panel = panel;
        this.host = host;
        this.config = new AppConfig(panel.getContext());
        this.inflater = LayoutInflater.from(panel.getContext());
        this.adjustRows = panel.findViewById(R.id.tools_adjust_rows);

        panel.findViewById(R.id.btn_tools_close).setOnClickListener(v -> hide());
        panel.findViewById(R.id.tools_videos).setOnClickListener(v -> host.openVideos());
        panel.findViewById(R.id.tools_photos).setOnClickListener(v -> host.openPhotos());
        panel.findViewById(R.id.tools_settings).setOnClickListener(v -> host.openSettings());
        panel.findViewById(R.id.tools_adjust_reset).setOnClickListener(v -> {
            ImageAdjustManager manager = host.adjustManager();
            if (manager != null) {
                manager.resetToDefault();
                manager.saveParamsToConfig();
                for (Runnable r : refreshers) {
                    r.run();
                }
            }
        });

        panel.findViewById(R.id.tools_exit).setOnClickListener(v -> host.exitApp());

        LinearLayout switches = panel.findViewById(R.id.tools_switches);
        // 两个原来在抽屉里的开关：点一下就拨，开关显示的是拨完的真实状态
        addSwitch(switches, R.string.set_section_rearview, 0,
                config::isRearViewEnabled, host::setRearView);
        addSwitch(switches, R.string.set_section_floating, 0,
                config::isRecordingFloatingEnabled, host::setFloatingButton);
        addSwitch(switches, R.string.set_recording_stats_title, R.string.set_recording_stats_summary,
                config::isRecordingStatsEnabled, on -> {
                    config.setRecordingStatsEnabled(on);
                    host.onStatsToggled(on);
                    return on;
                });
        refresh();
    }

    // ------------------------------------------------------------------ 开合

    public boolean isShowing() {
        return panel.getVisibility() == View.VISIBLE;
    }

    public void toggle() {
        if (isShowing()) {
            hide();
        } else {
            show();
        }
    }

    public void show() {
        if (isShowing()) {
            return;
        }
        slide();
        panel.setVisibility(View.VISIBLE);
        for (Runnable sync : switchSyncs) {
            sync.run();
        }
        refresh();
    }

    public void hide() {
        if (!isShowing()) {
            return;
        }
        slide();
        panel.setVisibility(View.GONE);
        ImageAdjustManager manager = host.adjustManager();
        if (manager != null) {
            manager.saveParamsToConfig();
        }
    }

    /** 从哪一边滑：面板贴哪边就从哪边来。录制中（且开着「录制时减少动效」）直接切。 */
    private void slide() {
        if (!(panel.getParent() instanceof ViewGroup)
                || !MotionPolicy.decorative(panel.getContext())) {
            return;
        }
        int edge = Gravity.END;
        if (panel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            int gravity = ((FrameLayout.LayoutParams) panel.getLayoutParams()).gravity;
            edge = (gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.START
                    ? Gravity.START : Gravity.END;
        }
        TransitionManager.beginDelayedTransition((ViewGroup) panel.getParent(),
                new Slide(edge).setDuration(220).addTarget(panel));
    }

    /** 面板贴左边还是右边（跟「操作按钮位置」那个设置走）。 */
    public void setSide(boolean left) {
        if (panel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            params.gravity = (left ? Gravity.START : Gravity.END) | Gravity.TOP;
            panel.setLayoutParams(params);
        }
    }

    // ------------------------------------------------------------------ 画面调节

    /** 按相机现在支持什么重新填调节那几行。 */
    public void refresh() {
        adjustRows.removeAllViews();
        refreshers.clear();
        ImageAdjustManager manager = host.adjustManager();
        boolean any = false;
        if (manager != null) {
            if (manager.isExposureCompensationSupported() && manager.getExposureRange() != null) {
                addExposure(manager);
                any = true;
            }
            if (manager.isAwbModeSupported()) {
                addChoice(manager, R.string.adj_awb, manager.getSupportedAwbModes(),
                        AppConfig.AWB_MODE_DEFAULT, manager::getAwbMode, manager::setAwbMode,
                        AppConfig::getAwbModeDisplayName);
                any = true;
            }
            if (manager.isTonemapModeSupported()) {
                addChoice(manager, R.string.adj_tonemap, manager.getSupportedTonemapModes(),
                        AppConfig.TONEMAP_MODE_DEFAULT, manager::getTonemapMode, manager::setTonemapMode,
                        AppConfig::getTonemapModeDisplayName);
                any = true;
            }
            if (manager.isEdgeModeSupported()) {
                addChoice(manager, R.string.adj_edge, manager.getSupportedEdgeModes(),
                        AppConfig.EDGE_MODE_DEFAULT, manager::getEdgeMode, manager::setEdgeMode,
                        AppConfig::getEdgeModeDisplayName);
                any = true;
            }
            if (manager.isNoiseReductionModeSupported()) {
                addChoice(manager, R.string.adj_noise, manager.getSupportedNoiseReductionModes(),
                        AppConfig.NOISE_REDUCTION_DEFAULT, manager::getNoiseReductionMode,
                        manager::setNoiseReductionMode, AppConfig::getNoiseReductionModeDisplayName);
                any = true;
            }
            if (manager.isEffectModeSupported()) {
                addChoice(manager, R.string.adj_effect, manager.getSupportedEffectModes(),
                        AppConfig.EFFECT_MODE_DEFAULT, manager::getEffectMode, manager::setEffectMode,
                        AppConfig::getEffectModeDisplayName);
                any = true;
            }
        }
        if (!any) {
            View row = inflater.inflate(R.layout.pref_row_info, adjustRows, false);
            ((TextView) row.findViewById(android.R.id.title)).setText(R.string.adj_unsupported);
            row.findViewById(android.R.id.summary).setVisibility(View.GONE);
            adjustRows.addView(row);
        }
        panel.findViewById(R.id.tools_adjust_reset).setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private void addExposure(ImageAdjustManager manager) {
        Range<Integer> range = manager.getExposureRange();
        View row = inflater.inflate(R.layout.pref_row_seekbar, adjustRows, false);
        ((TextView) row.findViewById(android.R.id.title)).setText(R.string.adj_exposure);
        row.findViewById(android.R.id.summary).setVisibility(View.GONE);
        exposureBar = row.findViewById(R.id.seekbar);
        exposureValue = row.findViewById(R.id.seekbar_value);
        exposureBar.setMax(range.getUpper() - range.getLower());
        Runnable sync = () -> {
            exposureBar.setProgress(manager.getExposureCompensation() - range.getLower());
            int value = manager.getExposureCompensation();
            exposureValue.setText((value > 0 ? "+" : "") + value);
        };
        sync.run();
        refreshers.add(sync);
        exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                touched(manager);
                manager.setExposureCompensation(progress + range.getLower());
                manager.updateAllCameras();
                int value = manager.getExposureCompensation();
                exposureValue.setText((value > 0 ? "+" : "") + value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                manager.saveParamsToConfig();
            }
        });
        adjustRows.addView(row);
    }

    private interface IntGetter { int get(); }
    private interface IntSetter { void set(int value); }
    private interface Namer { String name(Context context, int value); }

    /** 「名字 … 当前值 ›」一行，点开是单选对话框；第一项永远是「默认」。 */
    private void addChoice(ImageAdjustManager manager, int titleRes, int[] supported,
            int defaultValue, IntGetter getter, IntSetter setter, Namer namer) {
        Context context = panel.getContext();
        List<Integer> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        values.add(defaultValue);
        names.add(context.getString(R.string.adj_default));
        if (supported != null) {
            for (int mode : supported) {
                values.add(mode);
                names.add(namer.name(context, mode));
            }
        }
        View row = inflater.inflate(R.layout.pref_row_value, adjustRows, false);
        ((TextView) row.findViewById(android.R.id.title)).setText(titleRes);
        TextView summary = row.findViewById(android.R.id.summary);
        Runnable sync = () -> summary.setText(nameOf(names, values, getter.get()));
        sync.run();
        refreshers.add(sync);
        row.setOnClickListener(v -> {
            int checked = Math.max(0, values.indexOf(getter.get()));
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                    context, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(titleRes)
                    .setSingleChoiceItems(names.toArray(new String[0]), checked, (dialog, which) -> {
                        touched(manager);
                        setter.set(values.get(which));
                        manager.updateAllCameras();
                        manager.saveParamsToConfig();
                        sync.run();
                        dialog.dismiss();
                    })
                    .setNegativeButton(R.string.action_cancel, null);
            CamDialogs.show(builder);
        });
        adjustRows.addView(row);
    }

    private static String nameOf(List<String> names, List<Integer> values, int value) {
        int index = values.indexOf(value);
        return index >= 0 ? names.get(index) : names.get(0);
    }

    /** 第一次动调节：启用画面调节（原来是开发者选项里的一行，现在动了就算开） */
    private void touched(ImageAdjustManager manager) {
        if (!config.isImageAdjustEnabled()) {
            config.setImageAdjustEnabled(true);
            host.onImageAdjustTouched();
        }
    }

    // ------------------------------------------------------------------ 开关行

    private interface BoolGetter { boolean get(); }
    /** 拨到某个值；返回拨完之后的真实值（拨不动就还是原来的） */
    private interface BoolSetter { boolean set(boolean on); }

    private void addSwitch(LinearLayout into, int titleRes, int summaryRes, BoolGetter current,
            BoolSetter onChange) {
        View row = inflater.inflate(R.layout.pref_row, into, false);
        ((TextView) row.findViewById(android.R.id.title)).setText(titleRes);
        TextView summary = row.findViewById(android.R.id.summary);
        if (summaryRes != 0) {
            summary.setText(summaryRes);
        } else {
            summary.setVisibility(View.GONE);
        }
        ViewGroup frame = row.findViewById(android.R.id.widget_frame);
        SwitchCompat toggle = (SwitchCompat) inflater.inflate(R.layout.pref_widget_switch, frame, false);
        toggle.setChecked(current.get());
        frame.addView(toggle);
        switchSyncs.add(() -> toggle.setChecked(current.get()));
        row.setOnClickListener(v -> toggle.setChecked(onChange.set(!toggle.isChecked())));
        into.addView(row);
    }
}
