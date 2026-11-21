package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class MeridianSummaries {
    private static final String NAME_KEY = "meridian_ui.compact.";
    private static final CompactVariant BREATH = new CompactVariant("breath", NAME_KEY + "breath", 75, 30);
    private static final CompactVariant CHANNELS = new CompactVariant("meridians", NAME_KEY + "meridians", 118, 18);
    private static final CompactVariant OVERVIEW = new CompactVariant("overview", NAME_KEY + "overview", 116, 11);
    private static final int LINE = CompactSummary.LINE;
    private static final int GRID_TOP = 11;
    private static final int GRID_HEIGHT = 3;
    private static final int RESERVE_HEIGHT = 3;

    private MeridianSummaries() {
    }

    static CompactProvider breath(BodyUiModule module) {
        // 展开和收小均使用同一份呼吸回执的精度。
        return new CompactSummary(List.of(BREATH), (g, variant) -> {
            var reading = BreathSummary.of(module.gatheringView().getBreathing());
            CompactSummary.fit(g, 0, 0, BREATH.width(), "息 余力 " + reading.reservePercent() + "%");
            CompactSummary.bar(g, 0, LINE, BREATH.width(), reading.reserve());
            CompactSummary.fit(g, 0, LINE + RESERVE_HEIGHT, BREATH.width(),
                    reading.airShortLabel() + "  纳 " + reading.intakeAmount());
        });
    }

    static CompactProvider channels(BodyUiModule module) {
        // 经脉行序、开拓数和容量继承展开数据页的同一投影。
        return new CompactSummary(List.of(CHANNELS), (g, variant) -> {
            var page = MeridianData.of(module.snapshot());
            var rows = page.rows();
            var label = String.format(Locale.ROOT, "脉  开 %d/%d  真元 %.2f/%.2f", page.opened(), rows.size(),
                    page.meridianTotal(), page.lumen().capacity() + page.wall().capacity());
            CompactSummary.fit(g, 0, 0, CHANNELS.width(), label);
            for (int i = 0; i < rows.size(); i++) {
                var state = rows.get(i).state();
                String token = ThemeTokens.MUTED_TEXT;
                if (state.getCondition() != MeridianCondition.INTACT) {
                    token = ThemeTokens.INPUT_ERROR;
                } else if (state.getOpening().getStage() == MeridianOpeningStage.OPENED) {
                    token = ThemeTokens.PROGRESS_FILL;
                }
                int left = i * CHANNELS.width() / rows.size();
                int right = Math.max(left + 1, (i + 1) * CHANNELS.width() / rows.size() - 1);
                g.fill(left, GRID_TOP, right, GRID_TOP + GRID_HEIGHT, MeridianTheme.color(token));
            }
        });
    }

    static CompactProvider overview(BodyUiModule module, MeridianSceneExtension extension) {
        // 概况在体表页顶端展开，锁住的独立行留在原位。
        return new CompactSummary(List.of(OVERVIEW), (g, variant) ->
                CompactSummary.fit(g, 0, 0, OVERVIEW.width(), extension.overviewLine(module.snapshot()))) {
            @Override
            public Optional<String> expandedFeature() {
                return Optional.of(BodyFeature.SURFACE.id);
            }
        };
    }
}
