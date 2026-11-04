package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import java.util.Set;

/** 在现有工作台内配置本地功法十槽，不创建独立游戏屏幕。 */
public final class CombatUiModule implements ClientModule {
    public static final String FEATURE = "meridian_ui:combat";
    private static final String WINDOW = "meridian_ui:combat_window";
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 240;
    private static final int PREFERRED_WIDTH = 600;
    private static final int PREFERRED_HEIGHT = 390;

    @Override
    public FrameworkCatalog.Module definition() {
        // 十槽需要固定底边，最小尺寸容纳一整排槽位及两页各自的滚动区域。
        var window = new UiWindowDefinition(WINDOW, FEATURE, MIN_WIDTH, MIN_HEIGHT,
                PREFERRED_WIDTH, PREFERRED_HEIGHT, Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of(FEATURE, Set.of("kiui:core"),
                new FrameworkCatalog.Window(window),
                new FrameworkCatalog.Feature(FEATURE, "功法配置", "修炼", "meridian_ui:body_icon", WINDOW));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 每个窗口持有自己的选择和拖动，唯一配置来源仍是 Combat。
        bindings.window(WINDOW, (runtime, state) -> {
            var panel = new CombatBookPanel(runtime, state.key());
            state.scope().addCleanup(panel::close);
            return panel;
        });
    }
}
