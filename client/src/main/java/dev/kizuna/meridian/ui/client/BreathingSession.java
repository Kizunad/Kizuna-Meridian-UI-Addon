package dev.kizuna.meridian.ui.client;

import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.BEGIN_BREATHING;
import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.END_BREATHING;
import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.SET_AUTOMATIC_BREATHING;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import java.util.Locale;
import java.util.function.Consumer;

/** 吐纳窗口里和排版无关的那部分：下一次用不用自动、首次教学、主按钮现在写什么和按下去做什么。两版窗口共用。 */
final class BreathingSession {
    private static final int FULL = 100;
    private static final double AIM_CENTER = 0.5;
    private static final double AIM_HEIGHT = 0.4;
    private final BreathingCanvas canvas;
    private final Consumer<BreathControl> sender;
    private GatheringView view;
    private boolean pending;
    private boolean tutorial;
    private boolean automaticSelected;
    private boolean startRequested;

    BreathingSession(BreathingCanvas canvas, Consumer<BreathControl> sender) {
        this.canvas = canvas;
        this.sender = sender;
    }

    void toggleAutomatic() {
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

    boolean automaticSelected() {
        return automaticSelected;
    }

    /** 首次教学正显示着。 */
    boolean tutorial() {
        return tutorial;
    }

    /** 快照里带着呼吸视图，自动吐纳的代价说明写得出来。 */
    boolean described() {
        return view != null && view.hasBreathing();
    }

    String automaticDescription() {
        // 暂停线、恢复线与效率来自服务端视图，客户端不写死自动规则。
        BreathingView breath = view.getBreathing();
        return String.format(Locale.ROOT,
                "低于%.0f%%暂停，恢复至%.0f%%继续。\n仅耗呼吸耐力，效率为手动的%.0f%%。",
                breath.getAutomaticMinimumStaminaRatio() * FULL,
                breath.getAutomaticResumeStaminaRatio() * FULL,
                breath.getAutomaticCaptureEfficiency() * FULL);
    }

    void update(GatheringView next, boolean nextPending) {
        // 吐纳进行中或刚停下时，勾选跟着服务端实际用的模式走。
        boolean wasActive = view != null && view.getBreathing().getActive();
        view = next;
        pending = nextPending;
        if (view != null && (view.getBreathing().getActive() || wasActive)) {
            automaticSelected = view.getBreathing().getAutomatic();
        }
    }

    String actionLabel() {
        // 教学确认与练习开关使用一个明确入口，不在场景内堆放表单。
        if (tutorial) {
            return "开始引导";
        }
        if (view != null && view.getBreathing().getActive()) {
            return "结束吐纳";
        }
        return "开始吐纳";
    }

    boolean canAct() {
        // 未装配呼吸能力时不可启动；停止输入优先于等待中的状态请求。
        return view != null && view.getBreathing().getAvailable() && (!pending || view.getBreathing().getActive());
    }

    void act() {
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

    void close() {
        // 关闭场景结束手动输入；已入账加工和在途仍由宿主完成。
        canvas.stopInput();
        if (startRequested && (view == null || !view.getBreathing().getActive())) {
            sender.accept(control(END_BREATHING));
        }
        canvas.dispose();
    }
}
