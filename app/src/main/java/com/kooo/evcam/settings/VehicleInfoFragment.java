package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.telemetry.SignalText;
import com.kooo.evcam.telemetry.InfoBar;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 系统信息（试验性）：车机能读到的车辆信号和此刻的状态。
 *
 * <p>每一行最前面一个勾：勾上的显示在行驶信息条上（{@link InfoBar}，项目所有者 2026-10-03）。
 * 有图标的带出它那一格，没图标的画成文字格；没验证的只有开发者勾得了。</p>
 *
 * <p>行按信号表（{@link Signal}）生成，表里加一行这里就多一行；分组、名字、验证程度都从表里来，
 * 这里只管排版和把值写成人话。名字深色的是实验中观察一致的，中灰是先用着的（细节待定），浅色的没验证；
 * 值深色是有数据，浅色「—」是没数据。名字下面一行小字是号码，对照 Lab 的记录用。</p>
 *
 * <p>资源只在这一页开着时占：进来登记（{@link Telemetry#acquire}），离开注销 —— 没别人（录像的信息条）
 * 在用的话，车辆接口的监听、定位订阅、线程全都停掉。</p>
 */
public class VehicleInfoFragment extends Fragment implements Telemetry.Listener {

    private static final String USER = "vehicle-info";

    private final Map<Signal, TextView> valueViews = new EnumMap<>(Signal.class);
    /** 经纬度那一行的值（不是车辆信号，从快照里取）。 */
    private TextView positionView;
    /** 经纬度那一行每秒刷一次：定位来了不通知监听，车停着时车辆读数又不变。 */
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable positionTick = new Runnable() {
        @Override
        public void run() {
            showPosition();
            main.postDelayed(this, 1000L);
        }
    };
    private TextView statusView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_vehicle_info, container, false);
        statusView = root.findViewById(R.id.vehicle_info_status);
        build(inflater, root.findViewById(R.id.vehicle_info_container));
        return root;
    }

    private void build(LayoutInflater inflater, LinearLayout list) {
        Context ctx = list.getContext();
        for (Signal.Group group : Signal.Group.values()) {
            View header = inflater.inflate(R.layout.pref_category, list, false);
            ((TextView) header.findViewById(android.R.id.title)).setText(group.labelRes);
            header.findViewById(android.R.id.summary).setVisibility(View.GONE);
            list.addView(header);
            for (Signal s : Signal.values()) {
                if (s.group != group) {
                    continue;
                }
                View row = inflater.inflate(R.layout.item_vehicle_info_row, list, false);
                TextView label = row.findViewById(R.id.vehicle_info_label);
                label.setText(s.labelRes);
                label.setTextColor(ContextCompat.getColor(ctx, tone(s.trust)));
                ((TextView) row.findViewById(R.id.vehicle_info_id)).setText(address(s));
                valueViews.put(s, row.findViewById(R.id.vehicle_info_value));
                bindCheck(row, s.name(), InfoBar.selectable(s));
                list.addView(row);
            }
            if (group == Signal.Group.VEHICLE) {
                // 经纬度不是车辆信号（系统定位），放在车辆这一组最后
                View row = inflater.inflate(R.layout.item_vehicle_info_row, list, false);
                ((TextView) row.findViewById(R.id.vehicle_info_label)).setText(R.string.vi_position);
                ((TextView) row.findViewById(R.id.vehicle_info_id)).setText(R.string.vi_position_source);
                positionView = row.findViewById(R.id.vehicle_info_value);
                bindCheck(row, InfoBar.POSITION, true);
                list.addView(row);
            }
        }
    }

    /**
     * 那一行的勾：勾上就上信息条，马上生效。勾不了的（没验证、又不是开发者）灰着，显示的是实际生效的 ——
     * 开发者模式关掉后，之前勾的没验证的项在这里也是不勾的样子。点整行等于点勾。
     */
    private void bindCheck(View row, String item, boolean selectable) {
        CheckBox check = row.findViewById(R.id.vehicle_info_check);
        // 每一行的勾 id 都一样：界面重建时系统会把最后一行的勾状态恢复到每一行上，再触发监听写回 ——
        // 勾选全被冲掉。勾的状态每次都从 InfoBar 重新取，不要系统替我们存
        check.setSaveEnabled(false);
        Context ctx = row.getContext();
        check.setChecked(selectable && InfoBar.selection(ctx).contains(item));
        check.setEnabled(selectable);
        check.setOnCheckedChangeListener((button, checked) ->
                InfoBar.setSelected(button.getContext(), item, checked));
        if (selectable) {
            row.setBackgroundResource(R.drawable.bg_pref_row);
            row.setOnClickListener(v -> check.toggle());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Telemetry t = Telemetry.get();
        t.addListener(this);
        t.acquire(requireContext(), USER);
        render(t.readings());
        main.removeCallbacks(positionTick);
        main.post(positionTick);
    }

    @Override
    public void onStop() {
        main.removeCallbacks(positionTick);
        Telemetry t = Telemetry.get();
        t.removeListener(this);
        t.release(USER);
        super.onStop();
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        if (isAdded()) {
            render(readings);
        }
    }

    private void render(Readings r) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        int known = ContextCompat.getColor(ctx, R.color.text_primary);
        int unknown = ContextCompat.getColor(ctx, R.color.text_tertiary);
        for (Map.Entry<Signal, TextView> e : valueViews.entrySet()) {
            Object v = r.get(e.getKey());
            TextView tv = e.getValue();
            tv.setText(SignalText.text(ctx, e.getKey(), v));
            tv.setTextColor(v == null ? unknown : known);
        }
        showPosition();
        statusView.setText(Telemetry.get().describe());
    }

    private void showPosition() {
        Context ctx = getContext();
        if (ctx == null || positionView == null) {
            return;
        }
        VehicleState state = Telemetry.get().latest();
        boolean located = state.latitude != null && state.longitude != null;
        positionView.setText(SignalText.position(ctx, state.latitude, state.longitude));
        positionView.setTextColor(ContextCompat.getColor(ctx,
                located ? R.color.text_primary : R.color.text_tertiary));
    }

    /** 名字的深浅：确认了的最深，先用着的中灰，没验证的最浅。 */
    private static int tone(Signal.Trust trust) {
        switch (trust) {
            case CONFIRMED:
                return R.color.pref_title;
            case PROVISIONAL:
                return R.color.text_secondary;
            default:
                return R.color.text_tertiary;
        }
    }

    /** 号码那一行：怎么读 + 号码（+ 区域）。 */
    static String address(Signal s) {
        String id = String.format(Locale.US, "0x%08X", s.id);
        switch (s.kind) {
            case FUNCTION_ZONE:
                return id + " · zone 0x" + Integer.toHexString(s.zone);
            case SENSOR_EVENT:
                return id + " · sensor event";
            case SENSOR_VALUE:
                return id + " · sensor value";
            default:
                return id + " · function";
        }
    }

}
