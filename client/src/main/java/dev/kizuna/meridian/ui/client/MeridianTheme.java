package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.client.Minecraft;

/** 经脉窗口共用工作区主题；身体与关口的业务颜色由各自绘制器维护。 */
public final class MeridianTheme {
    private static final int TEXT_INSET = 6;

    public enum ButtonState { NORMAL, HIGHLIGHTED, DISABLED }

    /** GUI 逻辑像素中的绘制范围，与窗口缩放后的命中范围一致。 */
    public record Bounds(int x, int y, int width, int height) { }

    private MeridianTheme() {
        // 直接读取必装工作区的主题，不缓存另一份配色或注册表。
    }

    public static int color(String token) {
        // 绘制时读取当前主题，已打开的窗口也能立即跟随外观设置。
        return InventoryUiClient.runtime().color(token);
    }

    static void apply(FlowLayout content) {
        // XML 负责结构；颜色在绘制前更新，包含运行中挂回的状态标签。
        content.surface((graphics, parent) -> {
            refreshLabels(parent);
        });
    }

    private static void refreshLabels(UIComponent component) {
        // 只更新有变化的颜色，避免每帧触发标签重排。
        if (component instanceof LabelComponent label) {
            String token = ThemeTokens.TEXT;
            String id = label.id();
            if ("body-hint".equals(id) || "status-text".equals(id)) {
                token = ThemeTokens.MUTED_TEXT;
            } else if ("feedback-text".equals(id)) {
                token = ThemeTokens.INPUT_ERROR;
            }
            int argb = color(token);
            if (label.color().get().argb() != argb) {
                label.color(Color.ofArgb(argb));
            }
        }
        if (component instanceof ParentUIComponent parent) {
            for (UIComponent child : parent.children()) {
                refreshLabels(child);
            }
        }
    }

    public static void panel(OwoUIGraphics graphics, Bounds bounds) {
        // 浮层与窗口同底色，只用一像素边界区分区域。
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(),
                color(ThemeTokens.PANEL));
        graphics.drawRectOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color(ThemeTokens.BORDER));
    }

    public static void button(OwoUIGraphics graphics, Bounds bounds, String title, ButtonState state) {
        // 静态底色与单层边框同时表达可用性和焦点，不用闪烁催促操作。
        String background = ThemeTokens.CONTROL;
        String border = ThemeTokens.BORDER;
        String foreground = ThemeTokens.TEXT;
        if (state == ButtonState.HIGHLIGHTED) {
            background = ThemeTokens.CONTROL_HOVER;
            border = ThemeTokens.ACCENT;
        } else if (state == ButtonState.DISABLED) {
            foreground = ThemeTokens.MUTED_TEXT;
        }
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(),
                color(background));
        graphics.drawRectOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color(border));
        var font = Minecraft.getInstance().font;
        String caption = font.plainSubstrByWidth(title, Math.max(0, bounds.width() - TEXT_INSET * 2));
        graphics.text(font, caption, bounds.x() + (bounds.width() - font.width(caption)) / 2,
                bounds.y() + (bounds.height() - font.lineHeight) / 2, color(foreground), false /* shadow */);
    }
}
