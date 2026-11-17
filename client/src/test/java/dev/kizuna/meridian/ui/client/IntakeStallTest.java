package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import org.junit.Test;
import static org.junit.Assert.*;

/** 一时的拥堵不算纳不进；纳入量比一个呼吸周期还久没涨才算，期间报过的受阻原因记一个呼吸周期。 */
public class IntakeStallTest {
    private static final FailureCode NONE = FailureCode.FAILURE_CODE_UNSPECIFIED;
    private static final FailureCode FULL = FailureCode.CAPACITY_EXCEEDED;
    private static final long SECOND = 1_000;

    @Test
    public void passingCongestionWhileQiKeepsArrivingIsNotARejection() {
        // 真客户端吐纳 5 分钟的样子：每一两秒报一次受阻，纳入量最多 4 秒不动、随后又涨。一直不算纳不进。
        var stall = new IntakeStall();
        double stored = 0;
        for (long second = 0; second < 300; second++) {
            if (second % 5 == 0) {
                stored += 0.5;
            }
            FailureCode code = second % 2 == 0 ? FULL : NONE;
            stall.observe(view(stored, code), second * SECOND);
            assertFalse("第 " + second + " 秒", stall.stalled(second * SECOND));
        }
    }

    @Test
    public void reportsTheLatestReasonOnceNothingHasArrivedForAWholeBreath() {
        // 一直纳不进：吸气的几秒报受阻、呼气的几秒不报。满 10 秒以前不写，之后写最近一次的原因，呼气时也不闪掉。
        var stall = new IntakeStall();
        for (long second = 0; second <= 30; second++) {
            boolean inhaling = second % 8 < 4;
            FailureCode code = NONE;
            if (inhaling) {
                code = second < 16 ? FULL : FailureCode.ENVIRONMENT_UNAVAILABLE;
            }
            stall.observe(view(0, code), second * SECOND);
            assertEquals("第 " + second + " 秒", second >= 10, stall.stalled(second * SECOND));
            FailureCode latest = second < 16 ? FULL : FailureCode.ENVIRONMENT_UNAVAILABLE;
            assertEquals("第 " + second + " 秒", latest, stall.failure(second * SECOND));
        }
    }

    @Test
    public void arrivingQiStoppingOrANewSessionStartsTheWaitAgain() {
        var stall = new IntakeStall();
        stall.observe(view(0, FULL), 0);
        stall.observe(view(0, FULL), 12 * SECOND);
        assertTrue(stall.stalled(12 * SECOND));
        // 纳进来一点：立刻不算，重新等满一个周期多。
        stall.observe(view(0.2, FULL), 13 * SECOND);
        assertFalse(stall.stalled(13 * SECOND));
        assertFalse(stall.stalled(22 * SECOND));
        assertTrue(stall.stalled(23 * SECOND));
        // 受阻只记一个呼吸周期：再久没报，就不拿旧原因说事。
        assertEquals(FULL, stall.failure(21 * SECOND));
        assertEquals(NONE, stall.failure(22 * SECOND));
        // 停了吐纳：不算，记着的受阻也忘掉；再开始要重新等满。
        stall.observe(view(0.2, FULL).toBuilder().setActive(false).build(), 25 * SECOND);
        assertFalse(stall.stalled(25 * SECOND));
        assertEquals(NONE, stall.failure(25 * SECOND));
        stall.observe(view(0.2, FULL), 26 * SECOND);
        assertFalse(stall.stalled(35 * SECOND));
        assertTrue(stall.stalled(36 * SECOND));
        // 换了会话（重新登录、换维度）：重新等。
        stall.observe(view(0.2, FULL).toBuilder().setSessionId("next").build(), 37 * SECOND);
        assertFalse(stall.stalled(37 * SECOND));
    }

    @Test
    public void aPlaceWithNothingToBreatheStallsWithoutAnyReportedFailure() {
        // 末地这类地方：吐纳开着，服务端一直不报受阻，纳入量也一直是零。照样算纳不进，只是没有受阻原因。
        var stall = new IntakeStall();
        for (long second = 0; second <= 20; second++) {
            stall.observe(view(0, NONE), second * SECOND);
        }
        assertTrue(stall.stalled(20 * SECOND));
        assertEquals(NONE, stall.failure(20 * SECOND));
    }

    private static BreathingView view(double stored, FailureCode code) {
        return BreathingView.newBuilder().setSessionId("first").setActive(true).setStoredQi(stored)
                .setFailure(code).build();
    }
}
