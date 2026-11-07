package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import dev.kizuna.inventoryui.theme.ThemeTokens;

/** 经脉窗口共用的勾选行；状态由调用方提供，绘制使用当前主题。 */
public final class LayerToggle extends BaseUIComponent {
    private static final int FULL = 100;
    private static final int HEIGHT = 27;
    private static final int BOX_LEFT = 7;
    private static final int BOX_TOP = 8;
    private static final int BOX_SIZE = 10;
    private static final int MARK_INSET = 3;
    private static final int TEXT_LEFT = 24;
    private static final int TEXT_TOP = 9;
    private static final int TEXT_END = 2;
    private final String label;
    private final BooleanSupplier checked;
    private final Runnable toggle;

    public LayerToggle(String label, BooleanSupplier checked, Runnable toggle) {
        // 状态直接查询面板，程序切层和鼠标勾选不会保留两份相互漂移的选中值。
        this.label = label;
        this.checked = checked;
        this.toggle = toggle;
        sizing(Sizing.fill(FULL), Sizing.fixed(HEIGHT));
        cursorStyle(CursorStyle.HAND);
        tooltip(Component.literal(label));
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 勾选标记区分开关，文字保持主题对比度，键盘焦点同样可见。
        boolean enabled = checked.getAsBoolean();
        var focus = focusHandler();
        boolean highlighted = hovered || (focus != null && focus.focused() == this);
        String background = highlighted ? ThemeTokens.CONTROL_HOVER : ThemeTokens.CONTROL;
        String foreground = enabled ? ThemeTokens.TEXT : ThemeTokens.MUTED_TEXT;
        int textColor = MeridianTheme.color(foreground);
        int boxX = x + BOX_LEFT;
        int boxY = y + BOX_TOP;
        context.fill(x, y, x + width, y + height, MeridianTheme.color(background));
        context.outline(boxX, boxY, BOX_SIZE, BOX_SIZE, textColor);
        if (enabled) {
            context.fill(boxX + MARK_INSET, boxY + MARK_INSET,
                    boxX + BOX_SIZE - MARK_INSET, boxY + BOX_SIZE - MARK_INSET, textColor);
        }
        var font = Minecraft.getInstance().font;
        String caption = font.plainSubstrByWidth(label, Math.max(0, width - TEXT_LEFT - TEXT_END));
        context.text(font, caption, x + TEXT_LEFT, y + TEXT_TOP, textColor, false /* shadow */);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 一次按下只切换一层，其余层保留勾选状态。
        if (click.button() != 0) {
            return false;
        }
        toggle.run();
        return true;
    }

    @Override
    public boolean onKeyPress(KeyEvent event) {
        // 键盘聚焦后使用空格或回车，与鼠标操作走同一条状态更新路径。
        if (event.key() == GLFW.GLFW_KEY_SPACE || event.key() == GLFW.GLFW_KEY_ENTER) {
            toggle.run();
            return true;
        }
        return super.onKeyPress(event);
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 允许按 Tab 到达渲染开关，避免控制仅能用鼠标。
        return true;
    }
}
