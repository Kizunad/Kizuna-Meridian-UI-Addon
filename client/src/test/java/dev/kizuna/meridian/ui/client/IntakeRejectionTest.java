package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.Test;
import static org.junit.Assert.*;

/** 纳不进时写原因的那一行：报过受阻写受阻的原因，没报过看当地有没有灵气；太长就折行、收省略号。 */
public class IntakeRejectionTest {
    /** 按字数量宽度：一个字算一格。 */
    private static final ToIntFunction<String> BY_LETTER = text -> text.codePointCount(0, text.length());
    private static final int WIDE = 40;
    private static final FailureCode NONE = FailureCode.FAILURE_CODE_UNSPECIFIED;
    private static final GatheringEnvironment RICH = GatheringEnvironment.newBuilder().setAvailable(true)
            .setConcentrationPerBlock(0.7).build();

    @Test
    public void namesWhereTheBreathGotStuck() {
        // 经脉满了、此地没有灵气、一时接不上各有各的说法；当地有灵气也照受阻的原因写。
        assertEquals("未纳入：经脉已满", IntakeRejection.reason(FailureCode.CAPACITY_EXCEEDED, RICH));
        assertEquals("未纳入：此地没有灵气", IntakeRejection.reason(FailureCode.ENVIRONMENT_UNAVAILABLE, RICH));
        assertEquals("未纳入：此地灵气一时接不上",
                IntakeRejection.reason(FailureCode.ENVIRONMENT_INTERVAL_INVALID, RICH));
        assertEquals("未纳入：经络来不及送", IntakeRejection.reason(FailureCode.THROUGHPUT_EXCEEDED, RICH));
        // 没有专门说法的失败码也要写一句，不能悄悄不写。
        assertEquals("未纳入：原因见按钮提示", IntakeRejection.reason(FailureCode.REVISION_CONFLICT, RICH));
    }

    @Test
    public void withoutAReportedFailureOnlyABarrenPlaceIsNamed() {
        // 服务端没报受阻：当地没有灵气（末地这类地方，或者浓度是零）写此地没有灵气；当地有灵气就不写，
        // 那多半是手动吐纳没在吸。
        GatheringEnvironment none = GatheringEnvironment.getDefaultInstance();
        GatheringEnvironment thin = RICH.toBuilder().setConcentrationPerBlock(0).build();
        assertEquals("未纳入：此地没有灵气", IntakeRejection.reason(NONE, none));
        assertEquals("未纳入：此地没有灵气", IntakeRejection.reason(NONE, thin));
        assertEquals("", IntakeRejection.reason(NONE, RICH));
    }

    @Test
    public void writesNothingWithoutAReasonAndPrefersARejectedRequest() {
        // 没有原因、请求也没被拒：一行都不写。整个请求被拒时写服务端回的那句，不再叠一行原因。
        assertTrue(IntakeRejection.lines("", "", BY_LETTER, WIDE).isEmpty());
        assertEquals(List.of("未纳入：经脉已满"), IntakeRejection.lines("", "未纳入：经脉已满", BY_LETTER, WIDE));
        assertEquals(List.of("已有任务正在执行，请等待完成。"),
                IntakeRejection.lines("已有任务正在执行，请等待完成。", "未纳入：经脉已满", BY_LETTER, WIDE));
    }

    @Test
    public void wrapsByLetterAndEndsTheSecondLineWithAnOmission() {
        // 八格宽：十三个字折成两行；二十二个字两行放不下，第二行末尾换成省略号，每行都不超宽。
        assertEquals(List.of("未纳入：此地灵气", "一时接不上"),
                IntakeRejection.lines("", "未纳入：此地灵气一时接不上", BY_LETTER, 8));
        List<String> cut = IntakeRejection.lines("器官或目标经脉已满，等待真元转移或拓宽经脉。", "", BY_LETTER, 8);
        assertEquals(List.of("器官或目标经脉已", "满，等待真元转…"), cut);
        assertEquals(IntakeRejection.MAX_LINES, cut.size());
    }
}
