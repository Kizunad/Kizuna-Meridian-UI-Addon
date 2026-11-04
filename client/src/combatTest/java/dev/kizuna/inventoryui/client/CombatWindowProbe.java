package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import io.wispforest.owo.ui.core.UIComponent;

/** 测试源码内读取实际窗口树，不给发布 API 增加调试入口。 */
public final class CombatWindowProbe {
    private CombatWindowProbe() {
        // 只读探针不改变窗口路由或焦点。
    }

    public static UIComponent content(WindowKey key) {
        // 必须经过真实模块工厂、XML 解析及 owo 挂载后才能访问。
        return InventoryUiClient.runtime().view(key).adapter.rootComponent.children().getFirst();
    }
}
