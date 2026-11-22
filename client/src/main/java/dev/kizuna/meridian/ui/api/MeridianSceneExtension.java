package dev.kizuna.meridian.ui.api;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** 必装生物表现接线；输入、控件和规则解释归生物附属，UI 只管理窗口生命周期。 */
public interface MeridianSceneExtension {
    String ENTRYPOINT = "meridian_ui_scene";
    String title();
    String lockedReason();
    String bodySummary(BodySnapshot body);

    List<String> inspectionLayers(InspectionSection section);

    List<String> inspectionRows(InspectionSection section, BodySnapshot body);

    /** 概况小窗的宽度，GUI 像素；overviewLine 给出的一行按原字号不能比它宽。 */
    int OVERVIEW_WIDTH = 160;

    String overviewLine(BodySnapshot body);

    CompactProvider compactBody(InspectionSection section, Supplier<BodySnapshot> snapshot);

    CompactProvider compactPractice(Supplier<BodySnapshot> snapshot);

    /** 检视窗口顶上的摘要，一项一行，可以带悬浮说明；不实现时就是 bodySummary 那一行。 */
    default java.util.List<net.minecraft.network.chat.Component> bodySummaryLines(BodySnapshot body) {
        return java.util.List.of(net.minecraft.network.chat.Component.literal(bodySummary(body)));
    }
    /** 新版体表窗口的摘要：窗口窄，每项单独一行；不实现时和 bodySummaryLines 相同。 */
    default List<Component> surfaceSummaryLines(BodySnapshot body) {
        return bodySummaryLines(body);
    }

    /** 经脉窗口「i」里的开脉流程说明，多行文字；不提供时只显示快捷键。 */
    default String meridianGuide() {
        return "";
    }

    java.util.List<MeridianAction> actions(BodySnapshot body);
    Session create(BodyScene scene, BodyCanvas canvas);

    static MeridianSceneExtension required() {
        // 整套装配必须有唯一生物场景提供者，通用UI不猜测生物身份。
        var providers = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getEntrypoints(ENTRYPOINT, MeridianSceneExtension.class);
        if (providers.size() != 1) {
            throw new IllegalStateException("需要唯一的生物修炼场景接线");
        }
        return providers.get(0);
    }

    interface Session {
        /** 完整练习场景，包含 create 传入的画布；空白区域应保留模型交互。 */
        UIComponent component();
        void update(BodySnapshot body);
        boolean keyPress(int key, int modifiers);
        void keyRelease(int key);
        void cancelInput();
        void close();
    }
}
