package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 整体回归：重复、重连、结果和计时清零不能触发错误视听反馈。 */
public class OpeningFeedbackTest {
    @Test
    public void onlyAuthoritativeTransitionsEmitOnce() {
        // 接近完成后即使本地过了很久也不能补完进度，只有新 OPENED 状态触发。
        var feedback = new OpeningFeedback();
        BodySnapshot active = body(1, OpeningTaskStatus.OPENING_HOLDING, .99, false);
        assertTrue(feedback.receive(active, 0).isEmpty());
        assertNull(feedback.flash("test:channel", 100_000_000_000L));
        assertTrue(feedback.receive(active, 100_000_000_000L).isEmpty());
        BodySnapshot done = body(2, OpeningTaskStatus.OPENING_COMPLETED, 1, true);
        assertEquals(OpeningFeedback.Outcome.COMPLETED, feedback.receive(done, 100).get(0).outcome());
        assertTrue(feedback.receive(done, 200).isEmpty());
        assertTrue(feedback.receive(active, 300).isEmpty());
        assertEquals(100, feedback.flash("test:channel", 400).atNanos());
        // 重连只建立历史基线；调试命令从未开直达已开也走同一反馈。
        feedback.clear();
        assertTrue(feedback.receive(done, 500).isEmpty());
        feedback.clear();
        feedback.receive(body(0, OpeningTaskStatus.OPENING_TASK_STATUS_UNSPECIFIED, 0, false), 0);
        assertEquals(1, feedback.receive(done, 500).size());
    }

    @Test
    public void holdResetAndTerminalFailureRecedeWithoutRepeating() {
        // 掉出维持回准备只反馈一次，后续准备快照不能不断播放失败音。
        var feedback = new OpeningFeedback();
        feedback.receive(body(1, OpeningTaskStatus.OPENING_HOLDING, .6, false), 0);
        assertEquals(OpeningFeedback.Outcome.INTERRUPTED, feedback.receive(
                body(2, OpeningTaskStatus.OPENING_PREPARING, 0, false), 100).get(0).outcome());
        assertEquals(.6, feedback.flash("test:channel", 100).progress(), 1e-9);
        assertTrue(feedback.receive(body(3, OpeningTaskStatus.OPENING_PREPARING, 0, false), 200).isEmpty());
        feedback.receive(body(4, OpeningTaskStatus.OPENING_HOLDING, .2, false), 300);
        assertEquals(1, feedback.receive(body(5, OpeningTaskStatus.OPENING_EXHAUSTED, 0, false), 400).size());
        assertNull(feedback.flash("test:channel", 2_500_000_000L));
    }

    private static BodySnapshot body(long revision, OpeningTaskStatus status, double progress, boolean opened) {
        // 夹具只声明协议事实，测试不复制开脉计算。
        return BodySnapshot.newBuilder().setId("test:body").setRevision(revision)
                .addMeridianStates(MeridianState.newBuilder().setId("test:channel")
                        .setOpening(MeridianOpening.newBuilder().setStage(opened ? MeridianOpeningStage.OPENED
                                : MeridianOpeningStage.IN_PROGRESS).setProgress(progress)))
                .setOpeningTask(OpeningTask.newBuilder().setActionInstanceId("attempt").setStartedMillis(10)
                        .setStatus(status).setRequiredMillis(15000).setHeldMillis((long) (15000 * progress))
                        .setRule(MeridianOpeningRule.newBuilder().setTargetMeridianId("test:channel"))).build();
    }
}
