package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import net.minecraft.util.Identifier;
import java.util.Map;
import java.util.function.Consumer;
import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.*;

/** 可重复进入的手动吐纳窗口；身体预览继续保持只读。 */
public final class CultivationPanel {
    private final FlowLayout root;
    private final BreathingCanvas canvas;
    private final BreathingMeters meters;
    private final Consumer<BreathControl> sender;
    private GatheringView view;
    private boolean pending;
    private boolean tutorial;
    private final io.wispforest.owo.ui.component.CheckboxComponent automatic;
    private final AutomaticNote automaticNote = new AutomaticNote();
    private boolean syncingAutomatic;

    public CultivationPanel(BodyScene scene, Consumer<BreathControl> sender) {
        // XML 提供整幅场景；各控件随后叠加在同一场景内部。
        this.sender = sender;
        FlowLayout content = UIModelLoader.get(new Identifier("meridian_ui", "cultivation"))
                .expandTemplate(FlowLayout.class, "cultivation-panel", Map.of());
        canvas = new BreathingCanvas(scene, sender);
        content.child(canvas);
        ActionRow action = new ActionRow(this::actionLabel, this::canAct, this::act);
        action.id("breathing-start");
        meters = new BreathingMeters(content, action);
        automatic = io.wispforest.owo.ui.component.Components.checkbox(net.minecraft.text.Text.literal("自动吐纳"));
        automatic.id("breathing-automatic");
        automatic.positioning(io.wispforest.owo.ui.core.Positioning.relative(50, 100));
        automatic.margins(io.wispforest.owo.ui.core.Insets.bottom(72));
        automatic.zIndex(2);
        automatic.onChanged(checked -> {
            // 闲置时只选择下一次模式；活动中提交切换意图，费用由服务器处理。
            if (!syncingAutomatic && this.view != null && this.view.getBreathing().getActive()) {
                sender.accept(BreathControl.newBuilder().setAction(SET_AUTOMATIC_BREATHING)
                        .setAutomatic(checked).setAimX(.5).setAimY(.4).setAimZ(.5).build());
            }
        });
        content.child(automatic);
        // 勾选后在复选框上方常显代价说明，不能只藏在悬浮提示里。
        automaticNote.positioning(io.wispforest.owo.ui.core.Positioning.relative(50, 100));
        automaticNote.margins(io.wispforest.owo.ui.core.Insets.bottom(94));
        automaticNote.zIndex(2);
        content.child(automaticNote);
        root = new ResponsiveLayout(content, (width, height) -> {
            // 说明条随场景宽度收窄，紧凑窗口折成两行仍完整显示停止线和效率；紧凑时纳入量上移，说明条再上移避开它。
            meters.arrange(width, height);
            boolean compact = width < BreathingMeters.COMPACT_WIDTH;
            // 紧凑按钮更矮，复选框紧贴按钮上方，避开右侧上移的纳入量。
            automatic.margins(io.wispforest.owo.ui.core.Insets.bottom(compact ? 64 : 72));
            automaticNote.horizontalSizing(io.wispforest.owo.ui.core.Sizing.fixed(Math.max(120, Math.min(420, width - 40))));
            automaticNote.margins(io.wispforest.owo.ui.core.Insets.bottom(compact ? 122 : 94));
        });
    }

    private String automaticDescription() {
        // 停止线与效率来自服务端视图，客户端不写死自动规则。
        BreathingView breath = view.getBreathing();
        return String.format(java.util.Locale.ROOT,
                "自动吐纳：持续消耗呼吸耐力（膈肌），不耗精力；呼吸耐力低于%.0f%%自动停下；同样碰到的灵气只吸收手动的%.0f%%。",
                breath.getAutomaticMinimumStaminaRatio() * 100, breath.getAutomaticCaptureEfficiency() * 100);
    }

    private final class AutomaticNote extends io.wispforest.owo.ui.base.BaseComponent {
        AutomaticNote() {
            // 宽度由窗口布局调整，高度固定容纳两行说明。
            sizing(io.wispforest.owo.ui.core.Sizing.fixed(420), io.wispforest.owo.ui.core.Sizing.fixed(28));
            id("breathing-automatic-note");
        }

        @Override
        public boolean isInBoundingBox(double x, double y) {
            // 说明条只展示文字，不参与命中；即使未显示或与其他控件相邻，也不能挡住纳入量悬浮和场景输入。
            return false;
        }

        @Override
        public void draw(io.wispforest.owo.ui.core.OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 只在勾选自动或服务端正在自动时显示，未勾选不占用场景。
            if (view == null || !view.hasBreathing() || !automatic.isChecked()) {
                return;
            }
            var font = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            context.fill(x, y, x + width, y + height, 0xC8061016);
            var lines = font.wrapLines(net.minecraft.text.Text.literal(automaticDescription()), Math.max(1, width - 12));
            for (int line = 0; line < Math.min(2, lines.size()); line++) {
                var wrapped = lines.get(line);
                context.drawText(font, wrapped, x + (width - font.getWidth(wrapped)) / 2, y + 4 + line * 11, 0xFF9BF2B8, false);
            }
        }
    }

    public FlowLayout root() {
        // 宿主窗口只持有组件，资源和状态由模块更新。
        return root;
    }

    public BreathingCanvas canvas() {
        // 演示验收通过真实组件定位鼠标位置。
        return canvas;
    }

    public void update(GatheringView view, boolean pending, String error) {
        // 两个窗口共享同一权威快照，当前组件仅保存展示引用。
        boolean wasActive = this.view != null && this.view.getBreathing().getActive();
        this.view = view;
        this.pending = pending;
        if (view != null) {
            if (view.getBreathing().getActive() || wasActive) {
                syncingAutomatic = true;
                automatic.checked(view.getBreathing().getAutomatic());
                syncingAutomatic = false;
            }
            // 悬浮提示与常显说明使用同一份权威停止线和效率。
            if (view.hasBreathing()) {
                automatic.tooltip(net.minecraft.text.Text.literal(automaticDescription()));
            }
            canvas.update(view);
        }
        meters.update(view, error);
    }

    private String actionLabel() {
        // 教学确认与练习开关使用一个明确入口，不在场景内堆放表单。
        if (tutorial) {
            return "开始引导";
        }
        if (view != null && view.getBreathing().getActive()) {
            return "结束吐纳";
        }
        return "开始吐纳";
    }

    private boolean canAct() {
        // 未装配呼吸能力时不可启动；停止输入优先于等待中的状态请求。
        return view != null && view.getBreathing().getAvailable() && (!pending || view.getBreathing().getActive());
    }

    private void act() {
        // 首次指导保存在客户端偏好中，确认后按所选手动或自动模式开始。
        if (view.getBreathing().getActive()) {
            canvas.stopInput();
            return;
        }
        if (!tutorial && !BreathingTutorial.seen()) {
            tutorial = true;
            canvas.tutorial(true);
            return;
        }
        if (tutorial) {
            tutorial = false;
            BreathingTutorial.remember();
            canvas.tutorial(false);
        }
        sender.accept(BreathControl.newBuilder().setAction(BEGIN_BREATHING)
                .setAutomatic(automatic.isChecked())
                .setAimX(.5).setAimY(.4).setAimZ(.5).build());
    }

    public void close() {
        // 关闭场景结束手动输入；已入账加工和在途仍由宿主完成。
        canvas.stopInput();
        canvas.dispose();
    }
}
