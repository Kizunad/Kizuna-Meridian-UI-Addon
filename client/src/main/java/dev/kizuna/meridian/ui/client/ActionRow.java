package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import org.lwjgl.glfw.GLFW;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import dev.kizuna.inventoryui.theme.ThemeTokens;

/** 与分层勾选框一致的平面操作行，鼠标和键盘共享执行入口。 */
public final class ActionRow extends BaseUIComponent {
    private static final int HEIGHT = 30;
    private static final int INSET = 3;
    private static final int ICON_SIZE = 28;
    private static final int ICON_TEXTURE_SIZE = 64;
    private static final int LABEL_BOTTOM = 14;
    private Identifier icon;
    private final Supplier<String> label;
    private final BooleanSupplier enabled;
    private final Runnable action;

    public ActionRow(Supplier<String> label, BooleanSupplier enabled, Runnable action) {
        // 文案与可用性按展示状态读取，不保存第二份业务状态。
        this.label = label;
        this.enabled = enabled;
        this.action = action;
        sizing(Sizing.fill(100), Sizing.fixed(HEIGHT));
        cursorStyle(CursorStyle.HAND);
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 鼠标悬停和键盘焦点共用静态强调，禁用态仍可读但不能提交。
        var focus = focusHandler();
        var state = MeridianTheme.ButtonState.NORMAL;
        if (!enabled.getAsBoolean()) {
            state = MeridianTheme.ButtonState.DISABLED;
        } else if (hovered || (focus != null && focus.focused() == this)) {
            state = MeridianTheme.ButtonState.HIGHLIGHTED;
        }
        var bounds = new MeridianTheme.Bounds(x + INSET, y + INSET, width - INSET * 2, height - INSET * 2);
        if (icon == null) {
            MeridianTheme.button(context, bounds, label.get(), state);
        } else {
            MeridianTheme.button(context, bounds, "", state);
            drawIcon(context);
        }
    }

    public ActionRow icon(Identifier texture) {
        // 图像与点击区域属于同一控件，键盘操作仍沿用主操作入口。
        icon = texture;
        return this;
    }

    private void drawIcon(OwoUIGraphics context) {
        // 图标置于文字上方，小窗口仍保留完整的按钮命中区。
        int left = x + (width - ICON_SIZE) / 2;
        context.blit(RenderPipelines.GUI_TEXTURED, icon, left, y + INSET * 2, 0, 0,
                ICON_SIZE, ICON_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
        var font = Minecraft.getInstance().font;
        String text = font.plainSubstrByWidth(label.get(), Math.max(1, width - INSET * 2));
        context.text(font, text, x + (width - font.width(text)) / 2, y + height - LABEL_BOTTOM,
                MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 主操作只响应左键，其他按键交给组件事件，允许附属登记上下文操作。
        if (click.button() != 0) {
            return super.onMouseDown(click, doubled);
        }
        return activate();
    }

    @Override
    public boolean onKeyPress(KeyEvent event) {
        // 键盘选择与点击执行同一个条件检查。
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE) {
            return activate();
        }
        return super.onKeyPress(event);
    }

    private boolean activate() {
        // 不可用阶段禁止重复发起，但服务端仍独立校验并发与容量。
        if (!enabled.getAsBoolean()) {
            return false;
        }
        action.run();
        return true;
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 允许用 Tab 完成选择和启动操作。
        return true;
    }
}
