package com.kooo.evcam.camera;

import java.util.function.Predicate;

/**
 * 主界面销毁时，进程里正在用的相机管线是哪一份（CAMCORDER 2026-10-15）。
 *
 * <p>进程里只该有一份管线：录像、HUD 侧视、侧视弹窗、后视镜都挂在 {@link CameraManagerHolder} 那一份上。
 * 新建的主界面手里的 {@code cameraManager} 一直是 null，要等 TextureView 就绪、{@code initCamera()} 跑完才有；
 * 夜间模式连着翻两次（实测 3 秒内 off→on→off），第二次销毁时这个界面还没轮到。
 * 只看界面自己手里那份，就会以为没有管线可留，把 Holder 清掉 —— 录像还在用的那份成了孤儿，
 * 下一个来要相机的（HUD 侧视）{@code getOrInit} 另建一份，两份管线抢同一个相机 2，
 * 互相顶掉（相机服务一路 onDisconnected），录像断、主界面起不来、HUD 一直盖着「没有实时画面」。</p>
 *
 * <p>所以界面手里那份没有（或已释放）时，Holder 里那份也算。纯 Java，见 {@code LivePipelineTest}。</p>
 */
public final class LivePipeline {

    private LivePipeline() {
    }

    /**
     * @param held     界面自己手里那份；还没起来是 null
     * @param shared   {@link CameraManagerHolder} 里进程共用的那份；没有是 null
     * @param released 这一份已经释放了吗
     * @return 还活着的那一份：先看界面手里的，再看 Holder 里的；都没有是 null
     */
    public static <T> T pick(T held, T shared, Predicate<T> released) {
        if (held != null && !released.test(held)) {
            return held;
        }
        if (shared != null && !released.test(shared)) {
            return shared;
        }
        return null;
    }
}
