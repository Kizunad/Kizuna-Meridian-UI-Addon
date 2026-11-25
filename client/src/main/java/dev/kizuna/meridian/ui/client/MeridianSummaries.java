package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import java.util.List;
import java.util.Optional;

import static dev.kizuna.meridian.ui.client.CompactSummary.LINE;
import static dev.kizuna.meridian.ui.client.CompactSummary.dim;
import static dev.kizuna.meridian.ui.client.CompactSummary.main;

/** 吐纳、经脉、概况三个小窗的摘要。 */
final class MeridianSummaries {
    private static final String NAME_KEY = "meridian_ui.compact.";
    private static final int TICK_TOP = LINE + 2;
    private static final int TICK_HEIGHT = 6;
    private static final int TICK_WIDTH = 2;
    private static final int TICK_PITCH = TICK_WIDTH + 1;
    /** 没开的经脉是半透明的白格，受损的是橙格；这两种颜色只表示经脉状态，不随主题换。 */
    private static final int CLOSED_TICK = 0x47FFFFFF;
    private static final int DAMAGED_TICK = 0xFFF0864A;
    static final CompactVariant BREATH =
            new CompactVariant("breath", NAME_KEY + "breath", 100, CompactSummary.METER_HEIGHT);
    static final CompactVariant CHANNELS =
            new CompactVariant("meridians", NAME_KEY + "meridians", 140, TICK_TOP + TICK_HEIGHT);
    static final CompactVariant OVERVIEW =
            new CompactVariant("overview", NAME_KEY + "overview", MeridianSceneExtension.OVERVIEW_WIDTH, LINE);

    private MeridianSummaries() {
    }

    static CompactProvider breath(BodyUiModule module) {
        // 展开和收小均使用同一份呼吸回执。
        return new CompactSummary(List.of(BREATH), (canvas, variant) ->
                breath(canvas, variant, BreathSummary.of(module.gatheringView().getBreathing())));
    }

    static void breath(SummaryCanvas canvas, CompactVariant variant, BreathSummary reading) {
        // 短标签沿用当前语言，读数和展开窗使用同一份快照。
        var words = WindowText.MODERN;
        var head = List.of(main(canvas, words.format("compact.breath_prefix", "息 ")),
                dim(canvas, words.format("compact.reserve", "余力")));
        var value = main(canvas, reading.reservePercent());
        var air = dim(canvas, words.format("compact.air", "气 %s", CompactNumbers.percent(reading.air())));
        var intake = dim(canvas, words.format("compact.intake", "纳 %s", reading.intakeAmount()));
        CompactSummary.meter(canvas, variant, new CompactSummary.Meter(head, value, reading.reserve(), air, intake));
    }

    static CompactProvider channels(BodyUiModule module) {
        // 经脉行序、开拓数和容量继承展开数据页的同一投影。
        return new CompactSummary(List.of(CHANNELS), (canvas, variant) ->
                channels(canvas, variant, MeridianData.of(module.snapshot())));
    }

    static void channels(SummaryCanvas canvas, CompactVariant variant, MeridianData.Page page) {
        var rows = page.rows();
        var words = WindowText.MODERN;
        String opened = words.format("compact.opened", "开 %s/%s", page.opened(), rows.size());
        double capacity = page.lumen().capacity() + page.wall().capacity();
        String stored = CompactNumbers.coarse(page.meridianTotal()) + "/" + CompactNumbers.coarse(capacity);
        CompactSummary.left(canvas, 0, 0, List.of(main(canvas, words.format("compact.channel_prefix", "脉 ")),
                dim(canvas, opened)));
        CompactSummary.right(canvas, variant.width(), 0, main(canvas, words.format("compact.qi", "真元 %s", stored)));
        // 每条经脉一格；格数多到排不下时按小窗宽度均分。
        int pitch = TICK_PITCH;
        if (rows.size() * TICK_PITCH > variant.width() + 1) {
            pitch = Math.max(1, variant.width() / rows.size());
        }
        int width = Math.max(1, pitch - 1);
        // 格子下面垫一条深色底，没开的半透明白格压在雪地这样的亮背景上也分得出来。
        int backingRight = Math.min(variant.width(), rows.size() * pitch - (pitch - width));
        canvas.fill(0, TICK_TOP, backingRight, TICK_TOP + TICK_HEIGHT, canvas.color(ThemeTokens.COMPACT_TRACK));
        for (int index = 0; index < rows.size(); index++) {
            int tickLeft = index * pitch;
            if (tickLeft + width > variant.width()) {
                break;
            }
            canvas.fill(tickLeft, TICK_TOP, tickLeft + width, TICK_TOP + TICK_HEIGHT, tick(canvas, rows.get(index)));
        }
    }

    private static int tick(SummaryCanvas canvas, MeridianData.Row row) {
        if (row.state().getCondition() != MeridianCondition.INTACT) {
            return DAMAGED_TICK;
        }
        if (row.state().getOpening().getStage() == MeridianOpeningStage.OPENED) {
            return canvas.color(ThemeTokens.PROGRESS_FILL);
        }
        return CLOSED_TICK;
    }

    static CompactProvider overview(BodyUiModule module, MeridianSceneExtension extension) {
        // 概况在体表页顶端展开，锁住的独立行留在原位。
        return new CompactSummary(List.of(OVERVIEW), (canvas, variant) ->
                overview(canvas, extension.overviewLine(module.snapshot()))) {
            @Override
            public Optional<String> expandedFeature() {
                return Optional.of(BodyFeature.SURFACE.id);
            }
        };
    }

    static void overview(SummaryCanvas canvas, String line) {
        CompactSummary.left(canvas, 0, 0, List.of(dim(canvas, line)));
    }
}
