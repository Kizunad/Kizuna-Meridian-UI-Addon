package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.meridian.ui.api.settings.SettingsPageCatalog;
import dev.kizuna.meridian.ui.api.settings.SettingsPageContext;
import dev.kizuna.meridian.ui.api.settings.SettingsPageProvider;
import dev.kizuna.meridian.ui.api.settings.SettingsPageSession;
import dev.kizuna.meridian.ui.api.settings.SettingsRegistration;
import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 只存在于测试模组的第二页，验证扩展不需要修改集中设置宿主。 */
public final class SettingsSmokeProvider implements SettingsRegistration, SettingsPageProvider {
    static final Identifier ID = Identifier.fromNamespaceAndPath("test", "settings");

    @Override
    public void register(SettingsPageCatalog catalog) {
        // 沿正式入口注册，测试页不会进入生产 JAR。
        catalog.register(this);
    }

    @Override
    public Identifier id() {
        // 测试身份与任何生产页面隔离。
        return ID;
    }

    @Override
    public Component title() {
        // 明确标记测试用途，截图不会误认为已有第二个业务设置页。
        return Component.literal("测试页");
    }

    @Override
    public int order() {
        // 放在 Combat 页之后，不改变首次打开的业务默认页。
        return 1;
    }

    @Override
    public SettingsPageSession create(SettingsPageContext context) {
        // 简单会话足以验证宿主只通过契约导航与清理。
        return new Session(SettingsWidgets.label(context.runtime(), "测试提供者已接入"));
    }

    private record Session(UIComponent component) implements SettingsPageSession {
        @Override
        public boolean dirty() {
            // 只读测试页没有业务草稿。
            return false;
        }

        @Override
        public void close() {
            // 此测试页不持有订阅、预览或持续输入。
        }
    }
}
