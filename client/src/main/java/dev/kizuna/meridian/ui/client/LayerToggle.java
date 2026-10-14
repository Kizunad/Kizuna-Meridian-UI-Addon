package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.util.function.BooleanSupplier;
import org.lwjgl.glfw.GLFW;

/** 身体查看器自己的勾选行；不使用原版按钮纹理与控件。 */
final class LayerToggle extends BaseComponent {
    private final String label;
    private final BooleanSupplier checked;
    private final Runnable toggle;

    LayerToggle(String label, BooleanSupplier checked, Runnable toggle) {
        // 状态直接查询面板，程序切层和鼠标勾选不会保留两份相互漂移的选中值。
        this.label = label;
        this.checked = checked;
        this.toggle = toggle;
        sizing(Sizing.fill(100), Sizing.fixed(27));
        cursorStyle(CursorStyle.HAND);
        tooltip(Text.literal(label));
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 平面勾选框与画布使用同一色系；文字和方框都属于一个点击目标。
        boolean enabled = checked.getAsBoolean();
        context.fill(x, y, x + width, y + height, hovered ? 0xFF283B42 : 0xFF1B2A31);
        int color = enabled ? 0xFFE3C37B : 0xFF80979F;
        context.drawBorder(x + 7, y + 8, 10, 10, color);
        if (enabled) {
            context.fill(x + 10, y + 11, x + 14, y + 15, color);
        }
        var font = MinecraftClient.getInstance().textRenderer;
        context.drawText(font, font.trimToWidth(label, Math.max(0, width - 26)), x + 24, y + 9, color, false);
    }

    @Override
    public boolean onMouseDown(double mouseX, double mouseY, int button) {
        // 一次按下只切换一层，其余层保留勾选状态。
        if (button != 0) {
            return false;
        }
        toggle.run();
        return true;
    }

    @Override
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        // 键盘聚焦后使用空格或回车，与鼠标操作走同一条状态更新路径。
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) {
            toggle.run();
            return true;
        }
        return super.onKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 允许按 Tab 到达渲染开关，避免控制仅能用鼠标。
        return true;
    }
}
