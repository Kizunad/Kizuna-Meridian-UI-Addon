package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** 标题旁的圆形信息入口，快捷键只在悬浮时显示。 */
final class MeridianInfo extends BaseComponent {
    MeridianInfo() {
        // 自绘圆环避免依赖字体是否包含圆形 i，也保持与场景控件一致的外观。
        sizing(Sizing.fixed(18), Sizing.fixed(18));
        tooltip(Text.literal("F - Focus\nG - 凝聚真元 / 暂停"));
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 小尺寸圆环逐行绘制，只使用现有 UI 图元。
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        int color = hovered ? 0xFFF2DBA0 : 0xFFB4A57B;
        for (int offset = -7; offset <= 7; offset++) {
            int outer = (int) Math.floor(Math.sqrt(49 - offset * offset));
            int inner = 0;
            if (Math.abs(offset) < 6) {
                inner = (int) Math.floor(Math.sqrt(36 - offset * offset));
            }
            context.fill(centerX - outer, centerY + offset, centerX - inner + 1, centerY + offset + 1, color);
            context.fill(centerX + inner, centerY + offset, centerX + outer + 1, centerY + offset + 1, color);
        }
        var text = MinecraftClient.getInstance().textRenderer;
        context.drawText(text, "i", centerX - text.getWidth("i") / 2, centerY - 4, color, false);
    }
}
