package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.WorkspaceScreen;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.ui.api.settings.MeridianSettings;
import dev.kizuna.meridian.ui.api.settings.SettingsPageCatalog;
import dev.kizuna.meridian.ui.api.settings.SettingsPageContext;
import dev.kizuna.meridian.ui.api.settings.SettingsRegistration;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Meridian 设置入口；依赖页面契约，不依赖具体业务模块。 */
public final class SettingsUiModule implements ClientModule {
    public static final String FEATURE = "meridian_ui:settings";
    private static final String WINDOW = "meridian_ui:settings_window";
    private static final int MIN_WIDTH = 280;
    private static final int MIN_HEIGHT = 180;
    private static final int WIDTH = 500;
    private static final int HEIGHT = 390;
    private static SettingsUiModule instance;
    private final SettingsPageCatalog catalog = new SettingsPageCatalog();
    private ClientRuntime runtime;
    private SettingsPanel panel;
    private WindowKey key;

    @Override
    public FrameworkCatalog.Module definition() {
        // 固定窗口身份使不同设置页始终复用一个窗口。
        var window = new UiWindowDefinition(WINDOW, FEATURE, MIN_WIDTH, MIN_HEIGHT, WIDTH, HEIGHT,
                Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of(FEATURE, Set.of("kiui:core"), new FrameworkCatalog.Window(window),
                new FrameworkCatalog.Feature(FEATURE, "设置", "Meridian", "meridian_ui:body_icon", WINDOW));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 显式客户端入口注册一次，随后冻结；宿主不扫描业务字段。
        FabricLoader.getInstance().getEntrypoints(SettingsRegistration.ENTRYPOINT, SettingsRegistration.class)
                .forEach(registration -> registration.register(catalog));
        catalog.freeze();
        instance = this;
        bindings.window(WINDOW, (owner, state) -> {
            key = state.key();
            panel = new SettingsPanel(catalog, new SettingsPageContext(owner, this::closeWindow));
            state.scope().addCleanup(() -> {
                panel.close();
                panel = null;
                key = null;
            });
            return panel;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof WorkspaceScreen) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((active, event) ->
                        panel == null || !panel.captureKey(event));
            }
        });
    }

    @Override
    public void connected(ClientRuntime runtime) {
        // 工作区运行时只对当前连接有效，不排队等待未来登录。
        this.runtime = runtime;
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 断线不保留未提交页面，也不在下一次连接突然弹窗。
        closeWindow();
        this.runtime = null;
    }

    public static MeridianSettings.Result open(Identifier pageId) {
        // 打开失败通过结果明确表达，错误线程不触碰窗口管理器。
        var client = Minecraft.getInstance();
        var module = instance;
        if (!client.isSameThread() || module == null || module.runtime == null || client.level == null) {
            return new MeridianSettings.Result(MeridianSettings.Status.UNAVAILABLE, "当前无有效客户端工作区");
        }
        if (pageId != null && module.catalog.find(pageId) == null) {
            return new MeridianSettings.Result(MeridianSettings.Status.UNKNOWN_PAGE, "未知设置页：" + pageId);
        }
        return module.openPage(pageId);
    }

    private MeridianSettings.Result openPage(Identifier pageId) {
        // pageId 只控制选中页，绝不作为 objectId 创建额外窗口。
        var status = MeridianSettings.Status.FOCUSED;
        if (panel == null) {
            status = MeridianSettings.Status.OPENED;
        }
        runtime.open(FEATURE, null);
        if (pageId != null && panel.select(pageId) == SettingsNavigation.Change.NEEDS_DISCARD) {
            status = MeridianSettings.Status.DIRTY_PAGE;
        }
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof WorkspaceScreen) && !panel.ownsScreen(screen)) {
            runtime.openWorkspace();
        }
        return new MeridianSettings.Result(status, status.name());
    }

    private void tick(Minecraft client) {
        // 离开工作区按顶层取消处理，页面拥有的预览屏幕是明确例外。
        var screen = client.gui.screen();
        if (panel != null && !(screen instanceof WorkspaceScreen) && !panel.ownsScreen(screen)) {
            closeWindow();
        }
    }

    private void closeWindow() {
        // 只经公开窗口管理器关闭，由 scope 清理页面一次。
        if (runtime != null && key != null) {
            runtime.workspace().windows().close(key);
        }
    }
}
