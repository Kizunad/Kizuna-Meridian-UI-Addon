package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

/** 「此地灵气」小窗：点吐纳窗口的徽记打开，逐行写当地灵气的详细数值，跟着吐纳视图刷新。 */
final class EnvironmentDetailPanel extends FlowLayout {
    private static final int PADDING = 6;
    private static final int FULL = 100;
    private final LabelComponent lines = UIComponents.label(Component.empty());

    EnvironmentDetailPanel() {
        // 一个标签撑满小窗，文字自己换行。
        super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
        padding(Insets.of(PADDING));
        lines.id("aura-lines");
        lines.shadow(false /* shadow */);
        lines.horizontalSizing(Sizing.fill(FULL));
        lines.color(Color.ofArgb(MeridianTheme.color(ThemeTokens.TEXT)));
        child(lines);
    }

    void update(GatheringEnvironment environment) {
        // 还没收到环境视图时说明原因，不显示猜出来的数。
        if (environment == null) {
            lines.text(Component.literal("还没有收到当地灵气的读数。打开吐纳窗口后就有。"));
            return;
        }
        lines.text(Component.literal(String.join("\n", EnvironmentEmblem.details(environment))));
    }
}
