package dev.kizuna.meridian.ui.api.settings;

/** 在 meridian_settings 客户端入口注册，所有入口执行完毕后目录冻结。 */
@FunctionalInterface
public interface SettingsRegistration {
    String ENTRYPOINT = "meridian_settings";
    /** 仅在客户端注册阶段加入页面，返回后由宿主统一冻结目录。 */
    void register(SettingsPageCatalog catalog);
}
