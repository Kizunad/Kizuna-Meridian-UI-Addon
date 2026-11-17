package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/**
 * 判断这会儿是不是真的纳不进：吐纳顺的时候，器官一时装满、经络一时送不过来也会报受阻，过一两秒自己就通了，
 * 那种不算。正在吐纳、纳入量比一个呼吸周期还久没涨才算；这期间报过的受阻原因记一个呼吸周期，供窗口写出来。
 */
final class IntakeStall {
    /** 自动吐纳一吸一呼共 8 秒，纳入量比这还久没涨才算没纳进去。 */
    static final long PATIENCE_MILLIS = 10_000;
    /** 受阻只在吸气的那几秒报上来，记一个呼吸周期。 */
    static final long MEMORY_MILLIS = 8_000;
    private static final FailureCode NONE = FailureCode.FAILURE_CODE_UNSPECIFIED;
    private String session = "";
    private boolean active;
    private double stored;
    private long grewAt;
    private FailureCode failure = NONE;
    private long failedAt;

    void observe(BreathingView breath, long nowMillis) {
        // 换了会话、刚开始吐纳、纳入量动了，都从这一刻重新等；停了就把记着的受阻忘掉。
        boolean started = breath.getActive() && !active;
        if (!breath.getSessionId().equals(session) || started || breath.getStoredQi() != stored) {
            grewAt = nowMillis;
        }
        session = breath.getSessionId();
        active = breath.getActive();
        stored = breath.getStoredQi();
        if (!active) {
            failure = NONE;
        } else if (breath.getFailureValue() != 0) {
            failure = breath.getFailure();
            failedAt = nowMillis;
        }
    }

    boolean stalled(long nowMillis) {
        // 正在吐纳，而且纳入量已经这么久没涨。
        return active && nowMillis - grewAt >= PATIENCE_MILLIS;
    }

    FailureCode failure(long nowMillis) {
        // 最近一个呼吸周期里报过的受阻原因；没报过回「未指定」。
        boolean remembered = failure != NONE && nowMillis - failedAt <= MEMORY_MILLIS;
        return remembered ? failure : NONE;
    }
}
