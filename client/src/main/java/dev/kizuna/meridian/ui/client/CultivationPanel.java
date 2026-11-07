package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.inventoryui.client.WindowInputHandler;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import net.minecraft.client.input.KeyEvent;

import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.*;

/** 可重复进入的手动吐纳窗口；身体预览继续保持只读。 */
public final class CultivationPanel {
    private static final int NOTE_PADDING = 6;
    private static final int NOTE_TOP = 4;
    private static final int NOTE_LINES = 2;
    private static final int NOTE_LINE_HEIGHT = 11;
    private static final int NOTE_WIDTH = 420;
    private static final int NOTE_MIN_WIDTH = 120;
    private static final int NOTE_HEIGHT = 28;
    private static final int NOTE_MARGIN = 40;
    private static final int NOTE_BOTTOM = 94;
    private static final int COMPACT_NOTE_BOTTOM = 122;
    private static final int AUTOMATIC_WIDTH = 100;
    private static final int AUTOMATIC_HEIGHT = 27;
    private static final int AUTOMATIC_BOTTOM = 68;
    private static final int COMPACT_AUTOMATIC_BOTTOM = 60;
    private static final int CENTER = 50;
    private static final int FULL = 100;
    private static final double AIM_CENTER = 0.5;
    private static final double AIM_HEIGHT = 0.4;
    private final FlowLayout root;
    private final BreathingCanvas canvas;
    private final BreathingMeters meters;
    private final Consumer<BreathControl> sender;
    private GatheringView view;
    private boolean pending;
    private boolean tutorial;
    private final LayerToggle automatic;
    private final AutomaticNote automaticNote = new AutomaticNote();
    private boolean automaticSelected;
    private boolean startRequested;

    public CultivationPanel(BodyScene scene, Consumer<BreathControl> sender) {
        // XML 提供整幅场景；各控件随后叠加在同一场景内部。
        this.sender = sender;
        FlowLayout content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "cultivation"))
                .expandTemplate(FlowLayout.class, "cultivation-panel", Map.of());
        canvas = new BreathingCanvas(scene, sender);
        content.child(canvas);
        ActionRow action = new ActionRow(this::actionLabel, this::canAct, this::act);
        action.id("breathing-start");
        meters = new BreathingMeters(content, action);
        automatic = new LayerToggle("自动吐纳", () -> automaticSelected, this::toggleAutomatic);
        automatic.id("breathing-automatic");
        automatic.sizing(Sizing.fixed(AUTOMATIC_WIDTH), Sizing.fixed(AUTOMATIC_HEIGHT));
        automatic.positioning(Positioning.relative(CENTER, FULL));
        content.child(automatic);
        // 勾选后在复选框上方常显代价说明，不能只藏在悬浮提示里；复选框与说明最后加入，按子组件顺序绘制在最上层。
        automaticNote.positioning(Positioning.relative(CENTER, FULL));
        content.child(automaticNote);
        root = new BreathingRoot(content);
    }

    private void arrange(int width, int height) {
        // 紧凑窗口把说明移到纳入量上方，开关仍紧邻开始按钮。
        meters.arrange(width, height);
        boolean compact = width < BreathingMeters.COMPACT_WIDTH;
        automatic.margins(Insets.bottom(compact ? COMPACT_AUTOMATIC_BOTTOM : AUTOMATIC_BOTTOM));
        int noteWidth = Math.max(NOTE_MIN_WIDTH, Math.min(NOTE_WIDTH, width - NOTE_MARGIN));
        automaticNote.horizontalSizing(Sizing.fixed(noteWidth));
        automaticNote.margins(Insets.bottom(compact ? COMPACT_NOTE_BOTTOM : NOTE_BOTTOM));
    }

    private void toggleAutomatic() {
        // 空闲只保存下一次选择；运行中沿用权威切换请求，不由控件结算费用。
        automaticSelected = !automaticSelected;
        if (view != null && view.getBreathing().getActive()) {
            sender.accept(control(SET_AUTOMATIC_BREATHING));
        }
    }

    private BreathControl control(BreathAction action) {
        // 模式切换和开始共用瞄准默认值，服务端继续校验真实可用能力。
        return BreathControl.newBuilder()
                .setAction(action)
                .setAutomatic(automaticSelected)
                .setAimX(AIM_CENTER)
                .setAimY(AIM_HEIGHT)
                .setAimZ(AIM_CENTER)
                .build();
    }

    private String automaticDescription() {
        // 暂停线、恢复线与效率来自服务端视图，客户端不写死自动规则。
        BreathingView breath = view.getBreathing();
        return String.format(java.util.Locale.ROOT,
                "低于%.0f%%暂停，恢复至%.0f%%继续。\n仅耗呼吸耐力，效率为手动的%.0f%%。",
                breath.getAutomaticMinimumStaminaRatio() * FULL,
                breath.getAutomaticResumeStaminaRatio() * FULL,
                breath.getAutomaticCaptureEfficiency() * FULL);
    }

    private final class AutomaticNote extends io.wispforest.owo.ui.base.BaseUIComponent {
        AutomaticNote() {
            // 宽度由窗口布局调整，高度固定容纳两行说明。
            sizing(Sizing.fixed(NOTE_WIDTH), Sizing.fixed(NOTE_HEIGHT));
            id("breathing-automatic-note");
        }

        @Override
        public boolean isInBoundingBox(double x, double y) {
            // 说明条只展示文字，不参与命中；即使未显示或与其他控件相邻，也不能挡住纳入量悬浮和场景输入。
            return false;
        }

        @Override
        public void draw(io.wispforest.owo.ui.core.OwoUIGraphics context, int mouseX, int mouseY,
                         float partialTicks, float delta) {
            // 只在勾选自动或服务端正在自动时显示，未勾选不占用场景。
            if (view == null || !view.hasBreathing() || !automaticSelected) {
                return;
            }
            var font = net.minecraft.client.Minecraft.getInstance().font;
            var lines = font.split(net.minecraft.network.chat.Component.literal(automaticDescription()),
                    Math.max(1, width - NOTE_PADDING * 2));
            int color = MeridianTheme.color(dev.kizuna.inventoryui.theme.ThemeTokens.MUTED_TEXT);
            for (int line = 0; line < Math.min(NOTE_LINES, lines.size()); line++) {
                context.text(font, lines.get(line), x + NOTE_PADDING, y + NOTE_TOP + line * NOTE_LINE_HEIGHT,
                        color, false /* shadow */);
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
                automaticSelected = view.getBreathing().getAutomatic();
            }
            // 悬浮提示与常显说明使用同一份权威停止线和效率。
            if (view.hasBreathing()) {
                automatic.tooltip(net.minecraft.network.chat.Component.literal(automaticDescription()));
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
        startRequested = true;
        sender.accept(control(BEGIN_BREATHING));
    }

    public void close() {
        // 关闭场景结束手动输入；已入账加工和在途仍由宿主完成。
        canvas.stopInput();
        if (startRequested && (view == null || !view.getBreathing().getActive())) {
            sender.accept(control(END_BREATHING));
        }
        canvas.dispose();
    }

    private final class BreathingRoot extends FlowLayout implements WindowInputHandler {
        BreathingRoot(FlowLayout content) {
            // 根组件接收工作区生命周期，画布仍拥有自己的透视镜头与打坐模型。
            super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
            child(new ResponsiveLayout(content, CultivationPanel.this::arrange));
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            // 吐纳使用鼠标，未消费的键交回工作区。
            return false;
        }

        @Override
        public void keyReleased(KeyEvent event) {
            // 没有持续键盘输入需要释放。
        }

        @Override
        public void cancelInput() {
            // 切换窗口或最小化时只释放吸呼方向，自动吐纳继续运行。
            canvas.releaseInput();
        }
    }
}
