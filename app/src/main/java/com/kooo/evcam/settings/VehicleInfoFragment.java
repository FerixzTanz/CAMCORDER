package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 系统信息（试验性）：车机能读到的车辆信号和此刻的状态。
 *
 * <p>行按信号表（{@link Signal}）生成，表里加一行这里就多一行；分组、名字、验证程度都从表里来，
 * 这里只管排版和把值写成人话。名字深色的是确认了的，中灰是先用着的（细节待定），浅色的没验证；
 * 值深色是有数据，浅色「—」是没数据。名字下面一行小字是号码，对照 Lab 的记录用。</p>
 *
 * <p>资源只在这一页开着时占：进来登记（{@link Telemetry#acquire}），离开注销 —— 没别人（录像的信息条）
 * 在用的话，车辆接口的监听、定位订阅、线程全都停掉。</p>
 */
public class VehicleInfoFragment extends Fragment implements Telemetry.Listener {

    private static final String USER = "vehicle-info";

    private final Map<Signal, TextView> valueViews = new EnumMap<>(Signal.class);
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
                list.addView(row);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Telemetry t = Telemetry.get();
        t.addListener(this);
        t.acquire(requireContext(), USER);
        render(t.readings());
    }

    @Override
    public void onStop() {
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
            tv.setText(text(ctx, e.getKey(), v));
            tv.setTextColor(v == null ? unknown : known);
        }
        statusView.setText(Telemetry.get().describe());
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

    /** 归一后的值 → 页面上的字。 */
    private static String text(Context ctx, Signal s, Object v) {
        if (v == null) {
            return ctx.getString(R.string.vi_v_none);
        }
        switch (s.format) {
            case ON_OFF:
            case LEVEL:
                return pick(ctx, v, R.string.vi_v_on, R.string.vi_v_off);
            case DOOR:
                return pick(ctx, v, R.string.vi_v_open, R.string.vi_v_closed);
            case BELT:
                return pick(ctx, v, R.string.vi_v_buckled, R.string.vi_v_unbuckled);
            case SEAT:
                return pick(ctx, v, R.string.vi_v_occupied, R.string.vi_v_empty);
            case SHOWN:
            case POPUP:
                return pick(ctx, v, R.string.vi_v_shown, R.string.vi_v_hidden);
            case MIRROR_DIP: {
                int code = v instanceof Integer ? (Integer) v : -1;
                return ctx.getString(code == Signal.MIRROR_TILTING ? R.string.vi_v_mirror_tilting
                        : code == Signal.MIRROR_DOWN ? R.string.vi_v_mirror_down
                        : code == Signal.MIRROR_RETURNING ? R.string.vi_v_mirror_returning
                        : R.string.vi_v_mirror_normal);
            }
            case DAY_NIGHT:
                return ctx.getString(Integer.valueOf(Signal.NIGHT).equals(v) ? R.string.vi_v_night : R.string.vi_v_day);
            case INDICATOR: {
                int code = v instanceof Integer ? (Integer) v : -1;
                return ctx.getString(code == 1 ? R.string.vi_v_left : code == 2 ? R.string.vi_v_right
                        : code == 3 ? R.string.vi_v_hazard : R.string.vi_v_off);
            }
            case LIGHT_SWITCH: {
                int code = v instanceof Integer ? (Integer) v : -1;
                if (code == 0) {
                    return ctx.getString(R.string.vi_v_off);
                }
                if (code == Signal.LIGHT_SWITCH_POSITION) {
                    return ctx.getString(R.string.vi_v_light_position);
                }
                if (code == Signal.LIGHT_SWITCH_LOW_BEAM) {
                    return ctx.getString(R.string.vi_v_light_low);
                }
                if (code == Signal.LIGHT_SWITCH_AUTO) {
                    return ctx.getString(R.string.vi_v_light_auto);
                }
                return String.format(Locale.US, "0x%08X", code);
            }
            case IGNITION: {
                int code = v instanceof Integer ? (Integer) v : -1;
                if (code == Signal.IGNITION_ACC) {
                    return ctx.getString(R.string.vi_v_ign_acc);
                }
                if (code == Signal.IGNITION_ON) {
                    return ctx.getString(R.string.vi_v_ign_on);
                }
                if (code == Signal.IGNITION_DRIVING) {
                    return ctx.getString(R.string.vi_v_ign_driving);
                }
                return String.format(Locale.US, "0x%08X", code);
            }
            case GEAR:
                return String.valueOf(v);
            case KMH:
            case MPS:
                return number(v, "%.0f km/h");
            case KM:
                return number(v, "%.0f km");
            case PERCENT:
            case PERCENT_RAW:
                return number(v, "%.0f %%");
            case DEGREES:
                return number(v, "%.0f°");
            case CELSIUS:
                return number(v, "%.1f °C");
            default:
                return String.valueOf(v);
        }
    }

    private static String pick(Context ctx, Object v, int whenTrue, int whenFalse) {
        return ctx.getString(Boolean.TRUE.equals(v) ? whenTrue : whenFalse);
    }

    private static String number(Object v, String pattern) {
        return v instanceof Float ? String.format(Locale.US, pattern, (Float) v) : String.valueOf(v);
    }
}
