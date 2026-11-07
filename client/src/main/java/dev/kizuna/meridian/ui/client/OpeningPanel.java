package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 独立开脉窗口：经脉主图展示适应，条件印记解释准备情况，操作保持在底部。 */
final class OpeningPanel {
    private static final int EDGE = 8;
    private static final int MARGIN = 16;
    private static final int TITLE_HEIGHT = 14;
    private static final int STATUS_TOP = 25;
    private static final int DETAILS_TOP = 46;
    private static final int CHOICE_DETAILS_TOP = 78;
    private static final int FOOTER_SPACE = 62;
    private static final int FEEDBACK_HEIGHT = 20;
    private static final int MAX_BUTTON_WIDTH = 200;
    private static final int CHOICE_TOP = 43;
    private static final int BUTTON_HEIGHT = 30;
    private static final int ACTION_BOTTOM = 38;
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
    private final OpeningDiagram diagram = new OpeningDiagram();
    private List<String> meterIds = List.of();
    private MeridianOperationView view = MeridianOperationView.getDefaultInstance();

    record Controls(Supplier<String> caption, BooleanSupplier enabled, Runnable start,
                    Supplier<String> choiceCaption, BooleanSupplier hasChoices, Runnable choose, Runnable focus) { }

    OpeningPanel(Controls controls) {
        // XML 管理布局槽，所有附属共享相同卡片结构和输入方式。
        this.hasChoices = controls.hasChoices();
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "opening"))
                .expandTemplate(FlowLayout.class, "opening-detail", Map.of());
        statusSlot = content.childById(FlowLayout.class, "status");
        statusText = statusSlot.childById(LabelComponent.class, "status-text");
        feedbackSlot = content.childById(FlowLayout.class, "feedback");
        feedbackText = feedbackSlot.childById(LabelComponent.class, "feedback-text");
        // 空状态保留布局槽，不挂载空 Label；owo 的文字悬浮查询要求至少有一行文字。
        feedbackSlot.removeChild(feedbackText);
        choiceSlot = content.childById(FlowLayout.class, "choice");
        var choice = new ActionRow(controls.choiceCaption(), hasChoices, controls.choose());
        choice.id("gather-choice");
        choiceSlot.child(choice);
        action = new ActionRow(controls.caption(), controls.enabled(), controls.start());
        action.id("opening-action");
        content.childById(FlowLayout.class, "operation").child(action);
        bindKeys(List.of(choice, action), controls);
        root = new ResponsiveLayout(content, this::arrange);
        rebuild();
    }

    private void bindKeys(List<ActionRow> buttons, Controls controls) {
        // 浮窗内焦点操作与主模型的快捷键提交同一权威请求。
        for (ActionRow control : buttons) {
            control.keyPress().subscribe(event -> {
                // 焦点留在操作浮窗时，F 和 G 仍操作同一场景与权威任务。
                if (event.modifiers() != 0) {
                    return false;
                }
                if (event.key() == GLFW.GLFW_KEY_F) {
                    controls.focus().run();
                    return true;
                }
                if (event.key() == GLFW.GLFW_KEY_G) {
                    if (controls.enabled().getAsBoolean()) {
                        controls.start().run();
                    }
                    return true;
                }
                return false;
            });
        }
    }

    private void arrange(int width, int height) {
        // 主图与条件共用滚动内容，操作和失败提示保持可见。
        MeridianTheme.apply(content);
        MeridianPanel.place(content.childById(UIComponent.class, "selection-title"), EDGE, EDGE,
                width - MARGIN, TITLE_HEIGHT);
        MeridianPanel.place(content.childById(UIComponent.class, "status"), EDGE, STATUS_TOP,
                width - MARGIN, TITLE_HEIGHT);
        boolean multiple = hasChoices.getAsBoolean();
        int top = multiple ? CHOICE_DETAILS_TOP : DETAILS_TOP;
        MeridianPanel.place(content.childById(UIComponent.class, "details"), EDGE, top,
                width - MARGIN, height - top - FOOTER_SPACE);
        MeridianPanel.place(content.childById(UIComponent.class, "feedback"), EDGE, height - FOOTER_SPACE,
                width - MARGIN, FEEDBACK_HEIGHT);
        int buttonWidth = Math.min(MAX_BUTTON_WIDTH, width - MARGIN);
        if (multiple != choiceShown) {
            choiceShown = multiple;
            if (multiple) {
                content.child(choiceSlot);
            } else {
                content.removeChild(choiceSlot);
            }
        }
        MeridianPanel.place(choiceSlot, (width - buttonWidth) / 2, CHOICE_TOP, buttonWidth, BUTTON_HEIGHT);
        MeridianPanel.place(content.childById(UIComponent.class, "operation"), (width - buttonWidth) / 2,
                height - ACTION_BOTTOM, buttonWidth, BUTTON_HEIGHT);
    }

    void show(String title, MeridianOperationView view, String error) {
        // 身份和条件列表不变时更新现有控件，保留滚动位置和主图插值。
        this.view = view;
        diagram.update(view);
        arrange(root.width(), root.height());
        String heading = title.isBlank() ? "经脉" : title;
        var titleLabel = content.childById(LabelComponent.class, "selection-title");
        titleLabel.text(net.minecraft.network.chat.Component.literal(heading));
        updateText(statusSlot, statusText, view.getStatus(), "");
        String hint = error.isEmpty() ? view.getHint() : error;
        updateText(feedbackSlot, feedbackText, error.isEmpty() ? "" : "操作未完成", hint);
        List<MeridianOperationMeter> conditions = conditions();
        var ids = conditions.stream().map(MeridianOperationMeter::getId).toList();
        if (!ids.equals(meterIds)) {
            meterIds = ids;
            rebuild();
        } else {
            for (int index = 0; index < meters.size(); index++) {
                meters.get(index).update(conditions.get(index));
            }
        }
    }

    private void updateText(FlowLayout slot, LabelComponent label, String text, String hint) {
        // 悬浮说明属于稳定槽位；没有文案时移除文字控件，避免鼠标经过或点击时查询空行。
        slot.tooltip(hint.isBlank() ? List.of() : List.of(net.minecraft.network.chat.Component.literal(hint)));
        boolean shown = slot.children().contains(label);
        if (text.isBlank()) {
            if (shown) {
                slot.removeChild(label);
            }
            return;
        }

        // 重用原标签，只有文案变化或重新显示时才触发布局，避免每次快照都重建控件。
        if (!label.text().getString().equals(text)) {
            label.text(net.minecraft.network.chat.Component.literal(text));
        }
        if (!shown) {
            slot.child(label);
        }
    }

    private void rebuild() {
        // 主图只展示声明的主进度，其余条件使用印记和状态，不重复堆叠数值。
        FlowLayout column = content.childById(FlowLayout.class, "meters");
        column.clearChildren();
        meters.clear();
        column.child(diagram);
        for (MeridianOperationMeter value : conditions()) {
            var meter = new OperationMeter(value);
            meters.add(meter);
            column.child(meter);
        }
    }

    private List<MeridianOperationMeter> conditions() {
        // 主进度已由图像展示，条件区只保留其他服务端要求。
        return view.getMetersList().stream()
                .filter(meter -> !meter.getId().equals(view.getPrimaryMeterId()))
                .toList();
    }

    FlowLayout root() {
        // 返回窗口独立持有的组件树。
        return root;
    }

    void focusActions() {
        // 窗口挂载后提供初始键盘落点；不覆盖用户通过 Tab 选中的其他控件。
        if (root.focusHandler() != null && root.focusHandler().focused() == null) {
            root.focusHandler().focus(action, UIComponent.FocusSource.MOUSE_CLICK);
        }
    }
}
