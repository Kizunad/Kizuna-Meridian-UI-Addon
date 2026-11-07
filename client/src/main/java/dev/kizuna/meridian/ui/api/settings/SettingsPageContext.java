package dev.kizuna.meridian.ui.api.settings;

import dev.kizuna.inventoryui.client.ClientRuntime;

/** 宿主只提供主题运行时和关闭本窗口的能力，不接管业务保存。 */
public record SettingsPageContext(ClientRuntime runtime, Runnable closeWindow) { }
