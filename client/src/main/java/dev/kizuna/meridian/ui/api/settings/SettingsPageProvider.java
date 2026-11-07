package dev.kizuna.meridian.ui.api.settings;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 客户端设置页工厂；业务参数及配置文件由提供者所属模块维护。 */
public interface SettingsPageProvider {
    /** 返回跨版本稳定且全局唯一的页面身份。 */
    Identifier id();
    /** 提供本地化标题，宿主不解释具体业务名称。 */
    Component title();
    /** 返回导航排序值，相同值由稳定 ID 决胜。 */
    int order();
    /** 为一次开页构造独立组件和编辑草稿。 */
    SettingsPageSession create(SettingsPageContext context);
}
