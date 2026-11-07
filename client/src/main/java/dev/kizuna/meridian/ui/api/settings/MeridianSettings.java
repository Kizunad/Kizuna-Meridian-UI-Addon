package dev.kizuna.meridian.ui.api.settings;

import dev.kizuna.meridian.ui.client.settings.SettingsUiModule;
import net.minecraft.resources.Identifier;

/** 集中设置客户端打开接口，pageId 不参与窗口身份。 */
public final class MeridianSettings {
    public enum Status { OPENED, FOCUSED, DIRTY_PAGE, UNKNOWN_PAGE, UNAVAILABLE }
    public record Result(Status status, String message) { }

    private MeridianSettings() {
        // 通过工作区模块公开路由，禁止创建第二套窗口。
    }

    public static Result open() {
        // 首次打开选目录首项，已有窗口保留当前页及草稿。
        return SettingsUiModule.open(null);
    }

    public static Result open(Identifier pageId) {
        // 未知身份直接反馈，不回退到其他页。
        return SettingsUiModule.open(java.util.Objects.requireNonNull(pageId));
    }
}
