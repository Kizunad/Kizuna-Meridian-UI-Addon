package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import org.junit.Test;
import static org.junit.Assert.*;

/** 摄入倍率那一行只在吐纳中、倍率明显变了时出现。 */
public class IntakeCaptionTest {
    @Test
    public void showsOnlyWhileBreathingWithAVisibleMultiplier() {
        BreathingView boosted = BreathingView.newBuilder().setActive(true).setIntakeMultiplier(1.5).build();
        IntakeCaption caption = IntakeCaption.of(boosted).orElseThrow();
        assertEquals("摄入 ×1.50", caption.text());
        assertTrue(caption.boosted());

        BreathingView drained = boosted.toBuilder().setIntakeMultiplier(0.8).build();
        assertEquals("摄入 ×0.80", IntakeCaption.of(drained).orElseThrow().text());
        assertFalse(IntakeCaption.of(drained).orElseThrow().boosted());

        assertTrue(IntakeCaption.of(boosted.toBuilder().setActive(false).build()).isEmpty());
        assertTrue(IntakeCaption.of(boosted.toBuilder().setIntakeMultiplier(1.004).build()).isEmpty());
        // 旧服务端不发这个字段，按一处理。
        assertTrue(IntakeCaption.of(boosted.toBuilder().clearIntakeMultiplier().build()).isEmpty());
    }
}
