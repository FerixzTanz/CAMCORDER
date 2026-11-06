package com.kooo.evcam.zeekr;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 请车机把仪表盘上的「投屏」打开：ECARX 的仪表交互接口（{@code com.ecarx.xui.adaptapi.diminteraction.DimInteraction}，
 * 车机自带、BOOTCLASSPATH 上，方向盘硬键回调走的也是它，Lab 10-04 验证过容器里不要权限）。全反射。
 *
 * <h3>为什么要它</h3>
 *
 * <p>仪表盘（2560×536）是一块公开的 presentation 屏，{@link ClusterDisplayTest} 开的窗口车机收下了，但仪表盘
 * 上什么都没有（用户 2026-11-06 实车）：ECARX 的仪表在安卓那一层上面还盖着自己的表盘，要等车机被告知「投屏」才把
 * 安卓的画面露出来（DiPlay 在吉利 FX11 上量到的，同一家供应商；它们那边是 DimNaviService 的
 * notifyDimProjectScreenSwitch）。极氪的 SDK 里对应的是 {@code dimProjectionScreenRequest(action, x, y, w, h)}：
 * 动作 {@link #ACTION_REQUEST} → {@link #ACTION_START} → {@link #ACTION_STOP}，状态由
 * {@code registerProjectionScreenEventCallback} 推回来（空闲 1 / 进行中 2 / 被强停 3）。</p>
 *
 * <p>只在开发者选项的测试里用；每一步的结果都记进黑匣子。车机上没有这一套（别的车型）就报 unavailable，什么都不做。</p>
 */
final class ClusterProjection {

    private static final String TAG = "ClusterProjection";
    static final String CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction";
    static final String CALLBACK_CLASS = CLASS + "$IDimProjectionScreenCallback";

    /** {@code PROJECT_SCREEN_ACTION_TYPE_*}（极氪 6.7.0 的 SDK 常量）。 */
    static final int ACTION_REQUEST = 1;
    static final int ACTION_START = 2;
    static final int ACTION_STOP = 3;
    static final int ACTION_RECOVERY = 4;
    /** {@code PROJECT_SCREEN_STATE_TYPE_*}。 */
    static final int STATE_IDLE = 1;
    static final int STATE_IN_PROGRESS = 2;
    static final int STATE_FORCE_STOP = 3;

    /** 状态推回来谁来看（库的线程上）。 */
    interface StateListener {
        void onState(int state);
    }

    private final Object dim;
    private final Method request;
    private final Method register;
    private final Method unregister;
    private Object callback;
    /** 每一步的结果，给诊断报告（英文，一行一步）。 */
    private final StringBuilder log = new StringBuilder();

    private ClusterProjection(Object dim, Method request, Method register, Method unregister) {
        this.dim = dim;
        this.request = request;
        this.register = register;
        this.unregister = unregister;
    }

    /**
     * 连上车机的仪表交互接口（{@code DimInteraction.create(context)}，约 1 秒，别在主线程上叫）。
     * 这台车机没有这一套、或者接口上没有投屏那几个方法是 null，原因写进 {@code reason}。
     */
    static ClusterProjection connect(Context context, StringBuilder reason) {
        try {
            Class<?> cls = Class.forName(CLASS);
            Method create = cls.getMethod("create", Context.class);
            Object dim = create.invoke(null, context.getApplicationContext());
            if (dim == null) {
                reason.append("DimInteraction.create returned null");
                return null;
            }
            Method request = cls.getMethod("dimProjectionScreenRequest", int.class, int.class, int.class, int.class, int.class);
            Class<?> cb = Class.forName(CALLBACK_CLASS);
            Method register = cls.getMethod("registerProjectionScreenEventCallback", cb);
            Method unregister = cls.getMethod("unregisterProjectionScreenEventCallback", cb);
            ClusterProjection out = new ClusterProjection(dim, request, register, unregister);
            out.log.append("DimInteraction: ").append(dim.getClass().getName());
            // 顺便读一个只读的：车机「在仪表上显示 presentation」的选项是什么（含义不明，先记下来）
            try {
                Object option = cls.getMethod("getShowPresentationOption").invoke(dim);
                out.log.append("; showPresentationOption=").append(option);
            } catch (Throwable t) {
                out.log.append("; showPresentationOption: ").append(describe(t));
            }
            return out;
        } catch (ClassNotFoundException e) {
            reason.append("no ").append(CLASS).append(" on this head unit");
            return null;
        } catch (NoSuchMethodException e) {
            reason.append("DimInteraction has no projection methods: ").append(e.getMessage());
            return null;
        } catch (Throwable t) {
            reason.append("DimInteraction failed: ").append(describe(t));
            return null;
        }
    }

    /** 订状态推送；失败记一行，不算错。 */
    void listen(StateListener listener) {
        try {
            Class<?> cb = Class.forName(CALLBACK_CLASS);
            callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onProjectionScreenStateChange":
                        int state = args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : -1;
                        BlackBox.noteImportant("仪表盘投屏：车机报状态 " + state + describeState(state));
                        synchronized (log) {
                            log.append("; state=").append(state);
                        }
                        listener.onState(state);
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && proxy == args[0];
                    case "toString":
                        return "ClusterProjection.callback";
                    default:
                        return null;
                }
            });
            Object ok = register.invoke(dim, callback);
            BlackBox.note("仪表盘投屏：订状态 " + ok);
        } catch (Throwable t) {
            callback = null;
            BlackBox.note("仪表盘投屏：订不了状态（" + describe(t) + "）");
        }
    }

    /** 发一个动作；返回车机有没有收下（抛异常算没收下，原文记进黑匣子）。 */
    boolean send(int action, int x, int y, int width, int height) {
        String name = action == ACTION_REQUEST ? "REQUEST" : action == ACTION_START ? "START"
                : action == ACTION_STOP ? "STOP" : action == ACTION_RECOVERY ? "RECOVERY" : String.valueOf(action);
        try {
            request.invoke(dim, action, x, y, width, height);
            BlackBox.noteImportant("仪表盘投屏：" + name + " " + x + "," + y + " " + width + "×" + height + " 发出去了");
            synchronized (log) {
                log.append("; ").append(name).append(" ok");
            }
            return true;
        } catch (Throwable t) {
            AppLog.w(TAG, "dimProjectionScreenRequest " + name + " failed: " + describe(t));
            BlackBox.noteImportant("仪表盘投屏：" + name + " 失败（" + describe(t) + "）");
            synchronized (log) {
                log.append("; ").append(name).append(" failed: ").append(describe(t));
            }
            return false;
        }
    }

    /** 不听了。 */
    void close() {
        Object cb = callback;
        callback = null;
        if (cb != null) {
            try {
                unregister.invoke(dim, cb);
            } catch (Throwable ignored) {
                // 注销不掉也没关系：测试结束了
            }
        }
    }

    String log() {
        synchronized (log) {
            return log.toString();
        }
    }

    static String describeState(int state) {
        switch (state) {
            case STATE_IDLE:
                return "（idle）";
            case STATE_IN_PROGRESS:
                return "（in progress）";
            case STATE_FORCE_STOP:
                return "（force stop）";
            default:
                return "";
        }
    }

    private static String describe(Throwable t) {
        Throwable e = t;
        while (e instanceof InvocationTargetException && e.getCause() != null) {
            e = e.getCause();
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
