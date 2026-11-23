package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.minecraft.client.Minecraft;

/** 展开窗里的画布：原点挪到组件左上角，文字不带阴影，颜色跟当前主题。 */
record WindowCanvas(OwoUIGraphics graphics, int left, int top) implements SummaryCanvas {
    @Override
    public int width(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    @Override
    public int color(String token) {
        return MeridianTheme.color(token);
    }

    @Override
    public void text(int x, int y, String text, int color) {
        graphics.text(Minecraft.getInstance().font, text, left + x, top + y, color, false /* shadow */);
    }

    @Override
    public void fill(int fromX, int fromY, int toX, int toY, int color) {
        graphics.fill(left + fromX, top + fromY, left + toX, top + toY, color);
    }
}
