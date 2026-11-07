package dev.kizuna.meridian.ui.api;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;
import io.wispforest.owo.ui.core.UIComponent;

/** 必装生物表现接线；输入、控件和规则解释归生物附属，UI 只管理窗口生命周期。 */
public interface MeridianSceneExtension {
    String ENTRYPOINT = "meridian_ui_scene";
    String title();
    String lockedReason();
    String bodySummary(BodySnapshot body);
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
