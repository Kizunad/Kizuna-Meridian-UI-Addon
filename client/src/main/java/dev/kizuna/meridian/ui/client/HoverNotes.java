package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.core.UIComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/** 自绘卡片的分区悬停说明：每帧绘制时登记区域，画完按鼠标位置挑一条挂到组件上。 */
final class HoverNotes {
    private final UIComponent owner;
    private final List<Note> notes = new ArrayList<>();
    private String shown;

    /** 一块区域和它的说明；说明只在鼠标落进区域时才生成。 */
    private record Note(MeridianTheme.Bounds bounds, Supplier<String> text) { }

    HoverNotes(UIComponent owner) {
        // 说明挂在所属组件上，由 owo 照常在最上层绘制。
        this.owner = owner;
    }

    void begin() {
        // 每帧重新登记，区域跟着当前排布走。
        notes.clear();
    }

    void add(MeridianTheme.Bounds bounds, Supplier<String> text) {
        // 先登记的区域优先，重叠时取前一块。
        notes.add(new Note(bounds, text));
    }

    void finish(int mouseX, int mouseY) {
        // 只在说明换了时重设，免得每帧重新折行。
        String text = notes.stream()
                .filter(note -> contains(note.bounds(), mouseX, mouseY))
                .findFirst()
                .map(note -> note.text().get())
                .orElse(null);
        if (Objects.equals(text, shown)) {
            return;
        }
        shown = text;
        if (text == null) {
            owner.tooltip((List<ClientTooltipComponent>) null);
            return;
        }
        owner.tooltip(Component.literal(text));
    }

    private static boolean contains(MeridianTheme.Bounds bounds, int x, int y) {
        // 左上含、右下不含，与 fill 的像素范围一致。
        return x >= bounds.x() && x < bounds.x() + bounds.width()
                && y >= bounds.y() && y < bounds.y() + bounds.height();
    }
}
