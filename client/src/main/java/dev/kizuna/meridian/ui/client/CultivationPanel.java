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
        root = new ResponsiveLayout(content, meters::arrange);
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
        this.view = view;
        this.pending = pending;
        if (view != null) {
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
        // 首次指导保存在客户端偏好中，后续练习每次仍需亲自吸气与呼气。
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
                .setAimX(.5).setAimY(.4).setAimZ(.5).build());
    }

    public void close() {
        // 关闭场景结束手动输入；已入账加工和在途仍由宿主完成。
        canvas.stopInput();
        canvas.dispose();
    }
}
