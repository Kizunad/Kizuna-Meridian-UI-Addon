package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.BooleanSupplier;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/** 经脉窗口顶部的页签；选中与否由面板查询，点击只请求切页。 */
final class MeridianTab extends BaseUIComponent {
    static final int WIDTH = 48;
    static final int HEIGHT = 18;
    private static final int MARK_HEIGHT = 2;
    private final String label;
    private final BooleanSupplier selected;
    private final Runnable select;

    MeridianTab(String id, String label, BooleanSupplier selected, Runnable select) {
        // 两个页签共用同一套绘制，标题文字即页名。
        this.label = label;
        this.selected = selected;
        this.select = select;
        id(id);
        sizing(Sizing.fixed(WIDTH), Sizing.fixed(HEIGHT));
        cursorStyle(CursorStyle.HAND);
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 选中页签底下一道强调色，未选中的文字变淡；悬停只换底色。
        boolean current = selected.getAsBoolean();
        String background = ThemeTokens.CONTROL;
        if (hovered || current) {
            background = ThemeTokens.CONTROL_HOVER;
        }
        String foreground = ThemeTokens.MUTED_TEXT;
        if (current) {
            foreground = ThemeTokens.TEXT;
        }
        context.fill(x, y, x + width, y + height, MeridianTheme.color(background));
        context.drawRectOutline(x, y, width, height, MeridianTheme.color(ThemeTokens.BORDER));
        if (current) {
            context.fill(x + 1, y + height - MARK_HEIGHT - 1, x + width - 1, y + height - 1,
                    MeridianTheme.color(ThemeTokens.ACCENT));
        }
        var font = DataDrawing.font();
        int textX = x + (width - font.width(label)) / 2;
        int textY = y + (height - font.lineHeight) / 2;
        context.text(font, label, textX, textY, MeridianTheme.color(foreground), false /* shadow */);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 只认左键。
        if (click.button() != 0) {
            return false;
        }
        select.run();
        return true;
    }

    @Override
    public boolean onKeyPress(KeyEvent event) {
        // 键盘聚焦后空格或回车切页，与鼠标同一路径。
        if (event.key() == GLFW.GLFW_KEY_SPACE || event.key() == GLFW.GLFW_KEY_ENTER) {
            select.run();
            return true;
        }
        return super.onKeyPress(event);
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 页签能用 Tab 键走到。
        return true;
    }
}
