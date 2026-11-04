package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import static dev.kizuna.meridian.ui.client.CombatBookStyle.*;

/** 功法引用图标与底部槽格；不会创建或转移 ItemStack。 */
final class CombatBookTile extends BaseUIComponent {
    private final CombatBookPanel panel;
    private final String techniqueId;
    private final int number;

    CombatBookTile(CombatBookPanel panel, String techniqueId, int number) {
        // number 为零表示左页来源，正数表示实际槽号，两者共用图标和命中边界。
        this.panel = panel;
        this.techniqueId = techniqueId;
        this.number = number;
        int tileHeight = TILE_HEIGHT;
        if (number > 0) {
            tileHeight = SLOT_HEIGHT;
        }
        sizing(Sizing.fixed(TILE_WIDTH), Sizing.fixed(tileHeight));
        cursorStyle(CursorStyle.HAND);
    }

    private String currentId() {
        // 槽位实时读取 Combat 快照，命令修改也立刻可见。
        if (number > 0) {
            return panel.slotId(number);
        }
        return techniqueId;
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 选中边框独立于鼠标悬停；拖动时只有十槽内的有效落点高亮。
        String id = currentId();
        var entry = panel.entry(id);
        int border = paper(ThemeTokens.BORDER);
        if (hovered || (!id.isEmpty() && id.equals(panel.selected()))) {
            border = paper(ThemeTokens.ACCENT);
        }
        context.fill(x, y, x + width, y + height, paper(ThemeTokens.PANEL_BOTTOM));
        context.drawRectOutline(x, y, width, height, border);
        if (number > 0 && panel.dragging() && isInBoundingBox(mouseX, mouseY)) {
            context.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                    paper(ThemeTokens.INVENTORY_DROP_VALID));
        }
        int iconSize = Math.min(ICON_SIZE, Math.min(width - GAP * 2, height - LINE_HEIGHT - GAP));
        if (entry != null) {
            icon(context, entry, new Bounds(x + (width - iconSize) / 2, y + GAP, iconSize, iconSize));
        }
        drawCaption(context, id, entry);
    }

    private void drawCaption(OwoUIGraphics context, String id,
                             dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique entry) {
        // 空槽与失效槽保留不同文案，失效身份只在 tooltip 中用于辨认原配置。
        var font = Minecraft.getInstance().font;
        String caption = "空";
        String hint = "空槽";
        if (entry != null) {
            hint = name(entry).getString();
            caption = font.plainSubstrByWidth(hint, Math.max(1, width - GAP));
        } else if (!id.isEmpty()) {
            caption = "失效";
            hint = "未学或内容已移除：" + id;
        }
        if (number > 0) {
            caption = Integer.toString(number);
            if (number == dev.kizuna.meridian.combat.core.CombatIds.SLOT_COUNT) {
                caption = "0";
            }
            hint += "\n拖入功法以配置 · 右键清空";
            if (entry == null && !id.isEmpty()) {
                context.text(font, "×", x + width - GAP * 2, y + GAP,
                        paper(ThemeTokens.CONTROL_CLOSE_HOVER), false /* shadow */);
            }
        }
        context.text(font, caption, x + (width - font.width(caption)) / 2, y + height - LINE_HEIGHT,
                paper(ThemeTokens.TEXT), false /* shadow */);
        tooltip(List.of(Component.literal(hint)));
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent event, boolean doubleClick) {
        // 右键清空是显式操作，左键选中不写槽也不施放。
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && number > 0) {
            panel.clear(number);
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            panel.select(currentId());
            if (number == 0) {
                panel.pressSource(techniqueId);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseDrag(MouseButtonEvent event, double deltaX, double deltaY) {
        // 只有左页可以发起拖动，槽间交换不属于配置交互。
        if (number == 0 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            panel.beginDrag(techniqueId);
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseUp(MouseButtonEvent event) {
        // owo 的释放坐标相对捕获组件，换回屏幕坐标后检查真实十槽落点。
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            panel.drop(x + event.x(), y + event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyPress(KeyEvent event) {
        // 键盘可选中焦点图标，数字键留给窗口统一处理。
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE) {
            panel.select(currentId());
            return true;
        }
        return super.onKeyPress(event);
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 拖动释放沿用 owo 聚焦组件的捕获，Tab 也能访问全部图标。
        return true;
    }
}
