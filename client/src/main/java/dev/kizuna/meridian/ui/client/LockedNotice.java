package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 未解锁窗口的简短说明；实际权限仍由服务端裁定。 */
final class LockedNotice extends BaseUIComponent {
    private static final int PADDING = 12;
    private static final int REASON_TOP = 32;
    private static final int LINE_HEIGHT = 12;
    private static final int FULL = 100;
    private final String title;
    private final String reason;

    LockedNotice(String title, String reason) {
        // 标题和原因由调用方提供，卡片本身不判断任何解锁条件。
        this.title = title;
        this.reason = reason;
        sizing(Sizing.fill(FULL), Sizing.fill(FULL));
        id("locked-notice");
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 常规字号左对齐；原因按可用宽度折行，保留寻找残卷的信息。
        var font = Minecraft.getInstance().font;
        int textWidth = Math.max(1, width - PADDING * 2);
        String heading = font.plainSubstrByWidth(title + "尚未解锁", textWidth);
        context.text(font, heading, x + PADDING, y + PADDING, MeridianTheme.color(ThemeTokens.TEXT),
                false /* shadow */);
        var lines = font.split(Component.literal(reason), textWidth);
        int reasonColor = MeridianTheme.color(ThemeTokens.MUTED_TEXT);
        for (int line = 0; line < lines.size(); line++) {
            context.text(font, lines.get(line), x + PADDING, y + REASON_TOP + line * LINE_HEIGHT,
                    reasonColor, false /* shadow */);
        }
    }
}
