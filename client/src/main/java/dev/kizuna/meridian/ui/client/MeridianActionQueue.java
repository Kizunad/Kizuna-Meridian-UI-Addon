package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.ui.api.MeridianAction;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Sizing;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/** 模型下方按发布顺序排列的操作；条件失效时从布局和焦点树中移除。 */
final class MeridianActionQueue extends FlowLayout {
    private static final int BUTTON_WIDTH = 74;
    private static final int HEIGHT = 58;
    private static final int FULL = 100;
    private final Map<String, ActionRow> buttons = new LinkedHashMap<>();
    private Map<String, MeridianAction> actions = Map.of();

    MeridianActionQueue() {
        // 内容宽度由实际可用操作决定，父窗口负责在模型下方居中。
        super(Sizing.fill(FULL), Sizing.fixed(HEIGHT), Algorithm.HORIZONTAL);
        id("meridian-actions");
    }

    void update(List<MeridianAction> available) {
        // 每次更新替换回调捕获的快照；身份未变时保留已有组件和键盘焦点。
        var updated = new LinkedHashMap<String, MeridianAction>();
        for (MeridianAction action : available) {
            updated.put(action.id(), action);
        }
        List<String> previousIds = List.copyOf(actions.keySet());
        actions = updated;
        if (!previousIds.equals(List.copyOf(updated.keySet()))) {
            clearChildren();
            buttons.keySet().retainAll(updated.keySet());
            for (MeridianAction action : available) {
                ActionRow button = buttons.computeIfAbsent(action.id(), this::button);
                child(button);
            }
        }
        for (MeridianAction action : available) {
            buttons.get(action.id()).icon(action.icon()).tooltip(Component.literal(action.hint()));
        }
    }

    private ActionRow button(String id) {
        // 点击时重新查找当前操作，已隐藏的旧回调不能继续执行。
        var button = new ActionRow(() -> actions.get(id).label(), () -> actions.containsKey(id), () -> {
            MeridianAction current = actions.get(id);
            if (current != null) {
                current.activate().run();
            }
        });
        button.id(id);
        button.sizing(Sizing.fixed(BUTTON_WIDTH), Sizing.fixed(HEIGHT));
        return button;
    }

    int arrange(int availableWidth) {
        // 窄窗口自动换行，扩展操作不挤占左右边缘或彼此重叠。
        int columns = Math.max(1, availableWidth / BUTTON_WIDTH);
        int index = 0;
        for (String id : actions.keySet()) {
            ActionRow button = buttons.get(id);
            int column = index % columns;
            int row = index / columns;
            MeridianPanel.place(button, column * BUTTON_WIDTH, row * HEIGHT, BUTTON_WIDTH, HEIGHT);
            index++;
        }
        int rows = Math.max(1, (buttons.size() + columns - 1) / columns);
        return rows * HEIGHT;
    }

    int contentWidth(int availableWidth) {
        // 每行保留整格宽度，由父窗口居中实际队列。
        int columns = Math.max(1, availableWidth / BUTTON_WIDTH);
        return Math.min(buttons.size(), columns) * BUTTON_WIDTH;
    }
}
