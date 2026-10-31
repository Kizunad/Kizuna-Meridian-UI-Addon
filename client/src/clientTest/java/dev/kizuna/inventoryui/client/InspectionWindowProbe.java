package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import io.wispforest.owo.ui.core.UIComponent;

/** 测试源内查看真实工作台组件，不向发行 API 增加验收专用入口。 */
public final class InspectionWindowProbe {
    private InspectionWindowProbe() {
        // 仅提供对已挂载窗口的只读检查。
    }

    public static UIComponent content(WindowKey key) {
        // 从实际窗口视图读取业务根组件，确保验收经过正式工厂与挂载流程。
        return InventoryUiClient.runtime().view(key).adapter.rootComponent.children().get(0);
    }
}
