package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import io.wispforest.owo.ui.core.ParentUIComponent;

/** 只存在于客户端测试的窗口组件探针，不进入发布 API。 */
public final class SettingsWindowProbe {
    private SettingsWindowProbe() {
        // 测试从公开窗口路由打开后读取真实组件树。
    }

    public static ParentUIComponent content(WindowKey key) {
        // 必须经过模块工厂及 XML 挂载，不能用测试替身代替实际窗口。
        return (ParentUIComponent) InventoryUiClient.runtime().view(key).adapter.rootComponent.children().getFirst();
    }
}
