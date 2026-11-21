package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BreathSummaryTest {
    private static final double FIRST_DEPOSIT = 0.25;
    private static final double NEXT_DEPOSIT = 1.75;
    private static final double AIR = 3;
    private static final double CAPACITY = 4;
    private static final double STAMINA = 0.9;
    private static final double ATTENTION = 0.4;

    @Test
    public void latestAuthorityMatchesExpandedReadings() {
        // 两帧具有相同会话和储量以外的读数，摘要必须跟随本帧入脉累计。
        var first = BreathingView.newBuilder()
                .setSessionId("active")
                .setStoredQi(FIRST_DEPOSIT)
                .setAirLiters(AIR)
                .setCapacityLiters(CAPACITY)
                .setStaminaRatio(STAMINA)
                .setAttentionRatio(ATTENTION)
                .build();
        var latest = first.toBuilder()
                .setStoredQi(NEXT_DEPOSIT)
                .setAutomatic(true /* automatic */)
                .build();
        assertEquals("累计入脉 0.25 真元", BreathSummary.of(first).intake());
        var actual = BreathSummary.of(latest);
        assertEquals(latest.getStoredQi(), actual.stored(), 0);
        assertEquals("累计入脉 1.75 真元", actual.intake());
        assertEquals("空气 75%", actual.airLabel());
        assertEquals("余力 90%", actual.reserveLabel());
        assertEquals("余力 40%", BreathSummary.of(first).reserveLabel());
    }
}
