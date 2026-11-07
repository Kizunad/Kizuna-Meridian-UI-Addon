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

    MeridianInfo() {
        // 自绘圆环避免依赖字体是否包含圆形 i，也保持与场景控件一致的外观。
        sizing(Sizing.fixed(SIZE), Sizing.fixed(SIZE));
        tooltip(Component.literal("F - Focus\nG - 凝聚真元 / 暂停"));
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
