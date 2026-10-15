package com.kooo.evcam.camera;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** {@link LivePipeline}：主界面销毁时，别因为自己手里还没有管线，就把别人正在用的那份丢掉。 */
public class LivePipelineTest {

    private final Set<Object> released = new HashSet<>();

    private Object pick(Object held, Object shared) {
        return LivePipeline.pick(held, shared, released::contains);
    }

    /** 平常：界面手里有，就是它。 */
    @Test
    public void theScreensOwnPipelineWins() {
        Object own = new Object();
        assertSame(own, pick(own, own));
    }

    /**
     * 2026-10-15：夜间模式 3 秒内翻两次，新界面还没 initCamera 就又被销毁。
     * 手里是 null，但录像和 HUD 用着 Holder 里那份 —— 它得算在内，不然 Holder 被清掉、HUD 另建一份。
     */
    @Test
    public void aScreenThatNeverGotItsPipelineStillCountsTheSharedOne() {
        Object shared = new Object();
        assertSame(shared, pick(null, shared));
    }

    /** 界面手里那份已经被换掉释放了（换车型重建），进程里有新的一份：留的是新的那份。 */
    @Test
    public void aReleasedOwnPipelineDoesNotHideALiveSharedOne() {
        Object own = new Object();
        Object shared = new Object();
        released.add(own);
        assertSame(shared, pick(own, shared));
    }

    /** 都释放了：没有可留的。 */
    @Test
    public void nothingLiveMeansNothingToKeep() {
        Object own = new Object();
        Object shared = new Object();
        released.add(own);
        released.add(shared);
        assertNull(pick(own, shared));
        assertNull(pick(null, shared));
        assertNull(pick(own, null));
    }

    @Test
    public void noPipelineAtAll() {
        assertNull(pick(null, null));
    }
}
