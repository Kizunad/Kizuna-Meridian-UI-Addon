package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import java.util.Locale;
import java.util.Optional;

/** 吐纳窗口纳入量下面那行「摄入 ×1.50」：只在正在吐纳、倍率明显不是一时显示。 */
record IntakeCaption(String text, boolean boosted) {
    // 两位小数下看不出差别的倍率不写。
    private static final double VISIBLE_DELTA = 0.005;

    static Optional<IntakeCaption> of(BreathingView breath) {
        if (!breath.getActive() || !breath.hasIntakeMultiplier()) {
            return Optional.empty();
        }
        double multiplier = breath.getIntakeMultiplier();
        if (Math.abs(multiplier - 1) <= VISIBLE_DELTA) {
            return Optional.empty();
        }
        String text = String.format(Locale.ROOT, "摄入 ×%.2f", multiplier);
        return Optional.of(new IntakeCaption(text, multiplier > 1));
    }
}
