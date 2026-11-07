package dev.kizuna.meridian.ui.client.settings.combat;

import dev.kizuna.meridian.ui.api.settings.SettingsPageCatalog;
import dev.kizuna.meridian.ui.api.settings.SettingsPageContext;
import dev.kizuna.meridian.ui.api.settings.SettingsPageProvider;
import dev.kizuna.meridian.ui.api.settings.SettingsPageSession;
import dev.kizuna.meridian.ui.api.settings.SettingsRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 独立 Combat UI 适配器，集中宿主通过通用注册契约发现此页。 */
public final class CombatSettingsProvider implements SettingsRegistration, SettingsPageProvider {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_combat", "stance");

    @Override
    public void register(SettingsPageCatalog catalog) {
        // 必装模块显式注册，不以可选依赖判断掩盖缺少接线。
        catalog.register(this);
    }

    @Override
    public Identifier id() {
        // 外部调用可稳定定向到本页。
        return ID;
    }

    @Override
    public Component title() {
        // 提供者负责本地化标题，宿主不解释业务 ID。
        return Component.translatable("settings.meridian_ui.combat_stance");
    }

    @Override
    public int order() {
        // 当前唯一业务页使用首位，后续页可以独立设置排序。
        return 0;
    }

    @Override
    public SettingsPageSession create(SettingsPageContext context) {
        // 每次开页独立创建草稿，重复聚焦由宿主保留本会话。
        return new CombatSettingsPage(context);
    }
}
