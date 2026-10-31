package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import org.lwjgl.glfw.GLFW;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** 与分层勾选框一致的平面操作行，鼠标和键盘共享执行入口。 */
final class ActionRow extends BaseUIComponent {
    private static final int HEIGHT = 30;
    private static final int TEXT = 0xFFE3C37B;
    private static final int MUTED = 0xFF80979F;
    private static final int BACKGROUND = 0xFF1B2A31;
    private static final int HOVER = 0xFF283B42;
    private final Supplier<String> label;
    private final BooleanSupplier enabled;
    private final Runnable action;
    private final long createdNanos = System.nanoTime();

    ActionRow(Supplier<String> label, BooleanSupplier enabled, Runnable action) {
        // 文案与可用性按展示状态读取，不保存第二份业务状态。
        this.label = label;
        this.enabled = enabled;
        this.action = action;
        sizing(Sizing.fill(100), Sizing.fixed(HEIGHT));
        cursorStyle(CursorStyle.HAND);
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 少量同心描边按缓慢周期改变透明度，黄光仅作提示，不创建粒子或离屏缓冲。
        var text = Minecraft.getInstance().font;
        boolean available = enabled.getAsBoolean();
        double ageSeconds = (System.nanoTime() - createdNanos) / 1e9;
        double glow = EffectSettings.reducedMotion() ? .5 : .5 + .5 * Math.sin(ageSeconds * Math.PI / 2);
        if (available) {
            for (int inset = 0; inset < 3; inset++) {
                int alpha = (int) ((12 + 15 * glow) / (inset + 1));
                context.drawRectOutline(x + inset, y + inset, width - inset * 2, height - inset * 2,
                        alpha << 24 | 0xE6C76D);
            }
        }
        context.fill(x + 3, y + 3, x + width - 3, y + height - 3, hovered ? HOVER : BACKGROUND);
        context.drawRectOutline(x + 3, y + 3, width - 6, height - 6, available ? 0x807D6F45 : 0x403A4B50);
        String caption = text.plainSubstrByWidth(label.get(), Math.max(0, width - 12));
        context.text(text, caption, x + (width - text.width(caption)) / 2,
                y + (height - text.lineHeight) / 2, available ? TEXT : MUTED, false);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 只响应左键；能否执行由 activate 统一判断。
        if (click.button() != 0) {
            return false;
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
