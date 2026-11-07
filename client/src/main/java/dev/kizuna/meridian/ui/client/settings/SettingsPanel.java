package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.inventoryui.client.WindowInputHandler;
import dev.kizuna.meridian.ui.api.settings.SettingsPageCatalog;
import dev.kizuna.meridian.ui.api.settings.SettingsPageContext;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 只处理导航、脏页确认与生命周期，具体字段由页提供者构造。 */
final class SettingsPanel extends FlowLayout implements WindowInputHandler, AutoCloseable {
    private final SettingsPageContext context;
    private final SettingsNavigation navigation;
    private final FlowLayout page;
    private final FlowLayout confirmation;

    SettingsPanel(SettingsPageCatalog catalog, SettingsPageContext context) {
        // XML 定义导航与内容区，组件树只随开页和切页变化。
        super(Sizing.fill(SettingsWidgets.FULL), Sizing.fill(SettingsWidgets.FULL), Algorithm.VERTICAL);
        this.context = context;
        navigation = new SettingsNavigation(id -> catalog.find(id).create(context));
        var model = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "settings"))
                .expandTemplate(FlowLayout.class, "settings-host", Map.of());
        var menu = model.childById(FlowLayout.class, "settings-navigation");
        page = model.childById(FlowLayout.class, "settings-page");
        confirmation = model.childById(FlowLayout.class, "settings-confirmation");
        for (var provider : catalog.pages()) {
            menu.child(SettingsWidgets.button(context.runtime(), provider.title().getString(),
                    () -> select(provider.id())));
        }
        child(model);
        select(catalog.pages().getFirst().id());
    }

    SettingsNavigation.Change select(Identifier id) {
        // 外部 open(pageId) 与左侧按钮使用相同确认逻辑。
        var change = navigation.select(id);
        if (change == SettingsNavigation.Change.NEEDS_DISCARD) {
            confirm();
        } else if (change == SettingsNavigation.Change.SELECTED) {
            showPage();
        }
        return change;
    }

    private void confirm() {
        // 不自动保存、不静默抛弃，选择仍停留在旧页面。
        var runtime = context.runtime();
        confirmation.clearChildren();
        confirmation.child(SettingsWidgets.label(runtime, "当前页有未保存修改"));
        confirmation.child(SettingsWidgets.button(runtime, "放弃修改并切换", () -> {
            navigation.discardAndSwitch();
            showPage();
        }).id("settings-discard"));
        confirmation.child(SettingsWidgets.button(runtime, "继续编辑", () -> {
            navigation.keepEditing();
            confirmation.clearChildren();
        }).id("settings-keep"));
    }

    private void showPage() {
        // 换页只替换组件引用，草稿关闭已由导航执行。
        confirmation.clearChildren();
        page.clearChildren();
        page.child(navigation.session().component());
    }

    boolean ownsScreen(Screen screen) {
        // 页面可暂时持有独立预览屏幕，宿主不关心预览类型。
        var session = navigation.session();
        return session != null && session.ownsScreen(screen);
    }

    boolean captureKey(KeyEvent event) {
        // 专用录入抢在工作区控件前执行，Esc 只取消录入。
        var session = navigation.session();
        return session != null && session.captureKey(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 顶层 Esc 视为取消本窗口，功法等其他窗口不受影响。
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            context.closeWindow().run();
            return true;
        }
        return false;
    }

    @Override
    public void keyReleased(KeyEvent event) {
        // 宿主不持有按键轴，预览屏幕负责自己的释放事件。
    }

    @Override
    public void cancelInput() {
        // 切焦点只取消持续输入，不丢弃页面草稿。
        var session = navigation.session();
        if (session != null) {
            session.cancelInput();
        }
    }

    @Override
    public void close() {
        // 窗口 scope 和离开工作区共用幂等关闭。
        navigation.close();
    }
}
