package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.lwjgl.glfw.GLFW;

/** 附属共用紧凑条形浮窗，选择和执行分开，操作栏始终留在底部。 */
final class OpeningPanel {
    private final FlowLayout content;
    private final FlowLayout root;
    private final FlowLayout choiceSlot;
    private final FlowLayout statusSlot;
    private final LabelComponent statusText;
    private final FlowLayout feedbackSlot;
    private final LabelComponent feedbackText;
    private final BooleanSupplier hasChoices;
    private final ActionRow action;
    private boolean choiceShown = true;
    private final List<OperationMeter> meters = new ArrayList<>();
    private List<String> meterIds = List.of();
    private MeridianOperationView view = MeridianOperationView.getDefaultInstance();

    OpeningPanel(Supplier<String> caption, BooleanSupplier enabled, Runnable start,
                 Supplier<String> choiceCaption, BooleanSupplier hasChoices, Runnable choose, Runnable focus) {
        // XML 管理布局槽，所有附属共享相同卡片结构和输入方式。
        this.hasChoices = hasChoices;
        content = UIModelLoader.get(new Identifier("meridian_ui", "opening"))
                .expandTemplate(FlowLayout.class, "opening-detail", Map.of());
        statusSlot = content.childById(FlowLayout.class, "status");
        statusText = statusSlot.childById(LabelComponent.class, "status-text");
        feedbackSlot = content.childById(FlowLayout.class, "feedback");
        feedbackText = feedbackSlot.childById(LabelComponent.class, "feedback-text");
        // 空状态保留布局槽，不挂载空 Label；owo 的文字悬浮查询要求至少有一行文字。
        feedbackSlot.removeChild(feedbackText);
        choiceSlot = content.childById(FlowLayout.class, "choice");
        var choice = new ActionRow(choiceCaption, hasChoices, choose);
        choice.id("gather-choice");
        choiceSlot.child(choice);
        action = new ActionRow(caption, enabled, start);
        action.id("opening-action");
        content.childById(FlowLayout.class, "operation").child(action);
        for (ActionRow control : List.of(choice, action)) {
            control.keyPress().subscribe((key, scan, modifiers) -> {
                // 焦点留在操作浮窗时，F 和 G 仍操作同一场景与权威任务。
                if (modifiers != 0) {
                    return false;
                }
                if (key == GLFW.GLFW_KEY_F) {
                    focus.run();
                    return true;
                }
                if (key == GLFW.GLFW_KEY_G) {
                    if (enabled.getAsBoolean()) {
                        start.run();
                    }
                    return true;
                }
                return false;
            });
        }
        root = new ResponsiveLayout(content, this::arrange);
        rebuild();
    }

    private void arrange(int width, int height) {
        // 紧凑视口只保留状态条、进度条和动作入口，多个附属可复用同一布局。
        MeridianPanel.place(content.childById(Component.class, "selection-title"), 8, 8, width - 16, 14);
        MeridianPanel.place(content.childById(Component.class, "status"), 8, 25, width - 16, 14);
        boolean multiple = hasChoices.getAsBoolean();
        int top = multiple ? 78 : 46;
        int bottom = 62;
        MeridianPanel.place(content.childById(Component.class, "details"), 8, top, width - 16, height - top - bottom);
        MeridianPanel.place(content.childById(Component.class, "feedback"), 8, height - bottom + 2, width - 16, 20);
        int buttonWidth = Math.min(200, width - 16);
        if (multiple != choiceShown) {
            choiceShown = multiple;
            if (multiple) {
                content.child(choiceSlot);
            } else {
                content.removeChild(choiceSlot);
            }
        }
        MeridianPanel.place(choiceSlot, (width - buttonWidth) / 2, 43, buttonWidth, 30);
        MeridianPanel.place(content.childById(Component.class, "operation"), (width - buttonWidth) / 2,
                height - 38, buttonWidth, 30);
    }

    void show(String title, MeridianOperationView view, String error) {
        // 身份和 meter 列表不变时更新现有控件，保留滚动位置和填充插值。
        this.view = view;
        arrange(root.width(), root.height());
        String heading = title.isBlank() ? "经脉" : title;
        content.childById(LabelComponent.class, "selection-title").text(Text.literal(heading));
        updateText(statusSlot, statusText, view.getStatus(), "");
        String hint = error.isEmpty() ? view.getHint() : error;
        updateText(feedbackSlot, feedbackText, error.isEmpty() ? "" : "操作未完成", hint);
        var ids = view.getMetersList().stream().map(MeridianOperationMeter::getId).toList();
        if (!ids.equals(meterIds)) {
            meterIds = ids;
            rebuild();
        } else {
            for (int index = 0; index < meters.size(); index++) {
                meters.get(index).update(view.getMeters(index));
            }
        }
    }

    private void updateText(FlowLayout slot, LabelComponent label, String text, String hint) {
        // 悬浮说明属于稳定槽位；没有文案时移除文字控件，避免鼠标经过或点击时查询空行。
        slot.tooltip(hint.isBlank() ? List.of() : List.of(Text.literal(hint)));
        boolean shown = slot.children().contains(label);
        if (text.isBlank()) {
            if (shown) {
                slot.removeChild(label);
            }
            return;
        }

        // 重用原标签，只有文案变化或重新显示时才触发布局，避免每次快照都重建控件。
        if (!label.text().getString().equals(text)) {
            label.text(Text.literal(text));
        }
        if (!shown) {
            slot.child(label);
        }
    }

    private void rebuild() {
        // 仅在卡片结构改变时重建进度条，实时刷新只更新已有控件。
        FlowLayout column = content.childById(FlowLayout.class, "meters");
        column.clearChildren();
        meters.clear();
        for (MeridianOperationMeter value : view.getMetersList()) {
            var meter = new OperationMeter(value);
            meters.add(meter);
            column.child(meter);
        }
    }

    FlowLayout root() {
        // 返回窗口独立持有的组件树。
        return root;
    }

    void focusActions() {
        // 窗口挂载后提供初始键盘落点；不覆盖用户通过 Tab 选中的其他控件。
        if (root.focusHandler() != null && root.focusHandler().focused() == null) {
            root.focusHandler().focus(action, Component.FocusSource.MOUSE_CLICK);
        }
    }
}
