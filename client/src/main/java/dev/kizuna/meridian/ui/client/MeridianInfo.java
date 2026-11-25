package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.kizuna.inventoryui.theme.ThemeTokens;

/** 标题旁的圆形信息入口，快捷键只在悬浮时显示。 */
final class MeridianInfo extends BaseUIComponent {
    private static final int SIZE = 18;
    private static final int RADIUS = 7;
    private static final int INNER_RADIUS = 6;

    static final String KEYS = "F：镜头对准选中的经脉\nG：凝聚真元 / 暂停";

    MeridianInfo(String guide) {
        // 自绘圆环避免依赖字体是否包含圆形 i，也保持与场景控件一致的外观。
        sizing(Sizing.fixed(SIZE), Sizing.fixed(SIZE));
        tooltip(tooltipLines(guide, DataText.get("help")));
    }

    static java.util.List<Component> tooltipLines(String guide, String dataHelp) {
        // 生物附属给的开脉流程在前，快捷键居中，数据页的说明在后；一行文字一行提示。
        var lines = new java.util.ArrayList<Component>();
        if (!guide.isBlank()) {
            guide.lines().forEach(line -> lines.add(Component.literal(line)));
            lines.add(Component.empty());
        }
        WindowText.get("action.controls", KEYS).lines().forEach(line -> lines.add(Component.literal(line)));
        dataHelp.lines().forEach(line -> lines.add(Component.literal(line)));
        return java.util.List.copyOf(lines);
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 小尺寸圆环逐行绘制，只使用现有 UI 图元。
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        String token = hovered ? ThemeTokens.ACCENT : ThemeTokens.MUTED_TEXT;
        int color = MeridianTheme.color(token);
        for (int offset = -RADIUS; offset <= RADIUS; offset++) {
            int outer = (int) Math.floor(Math.sqrt(RADIUS * RADIUS - offset * offset));
            int inner = 0;
            if (Math.abs(offset) < INNER_RADIUS) {
                inner = (int) Math.floor(Math.sqrt(INNER_RADIUS * INNER_RADIUS - offset * offset));
            }
            context.fill(centerX - outer, centerY + offset, centerX - inner + 1, centerY + offset + 1, color);
            context.fill(centerX + inner, centerY + offset, centerX + outer + 1, centerY + offset + 1, color);
        }
        var text = Minecraft.getInstance().font;
        context.text(text, "i", centerX - text.width("i") / 2, centerY - text.lineHeight / 2,
                color, false /* shadow */);
    }
}
