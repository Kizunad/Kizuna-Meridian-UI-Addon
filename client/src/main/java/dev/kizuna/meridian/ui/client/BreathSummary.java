package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import java.util.Locale;

/** 展开窗和小窗对同一份呼吸回执的读数。 */
record BreathSummary(double reserve, double air, double stored) {
    static final int PERCENT = 100;

    static BreathSummary of(BreathingView breath) {
        // 自动吐纳只耗耐力，手动由较早耗尽的一项限制。
        double reserve = breath.getStaminaRatio();
        if (!breath.getAutomatic()) {
            reserve = Math.min(reserve, breath.getAttentionRatio());
        }
        double air = QiDisplay.ratio(breath.getAirLiters(), breath.getCapacityLiters());
        return new BreathSummary(reserve, air, breath.getStoredQi());
    }

    String intake() {
        // 入脉累计与经脉当前余额的扣费无关。
        return String.format(Locale.ROOT, "累计入脉 %.2f 真元", stored);
    }

    String intakeAmount() {
        // 小窗只显示累计值，完整的账户说明留在展开页。
        return String.format(Locale.ROOT, "%.2f", stored);
    }

    String airLabel() {
        return String.format(Locale.ROOT, "空气 %.0f%%", air * PERCENT);
    }

    String airShortLabel() {
        // HUD 固定短字头，完整用语仍由展开页和悬浮说明呈现。
        return String.format(Locale.ROOT, "气 %.0f%%", air * PERCENT);
    }

    String reservePercent() {
        // 与完整吐纳摘要共用同一份余力比例。
        return String.format(Locale.ROOT, "%.0f", reserve * PERCENT);
    }

    String reserveLabel() {
        return String.format(Locale.ROOT, "余力 %.0f%%", reserve * PERCENT);
    }
}
