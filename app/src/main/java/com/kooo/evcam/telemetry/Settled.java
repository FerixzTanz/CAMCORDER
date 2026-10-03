package com.kooo.evcam.telemetry;

import java.util.Objects;

/**
 * 会自己闪一下又回去的信号：变了要稳住一会儿才算数。和 {@link MinimumOn} 正好反过来 ——
 * 那个让真的短脉冲看得见，这个让假的短脉冲不算数。
 *
 * <p>从读不到变成读到，直接算（之前没有可比的）；读到之后的每一次变化（包括变成读不到），
 * 新值要连续 {@link #settleMs} 不变才换过去，没稳住就当没变过。</p>
 *
 * <p>到点要有人再算一次才换得过去：要稳多久由 {@link VehicleStateMapper#republishAfterMs} 告诉来源。</p>
 */
final class Settled<T> {

    private final long settleMs;
    private T adopted;
    private T candidate;
    private long candidateSinceMs;

    Settled(long settleMs) {
        this.settleMs = settleMs;
    }

    T update(long nowMs, T value) {
        if (adopted == null) {
            adopted = value;
            candidate = value;
            return adopted;
        }
        if (!Objects.equals(value, candidate)) {
            candidate = value;
            candidateSinceMs = nowMs;
        }
        if (!Objects.equals(candidate, adopted) && nowMs - candidateSinceMs >= settleMs) {
            adopted = candidate;
        }
        return adopted;
    }
}
