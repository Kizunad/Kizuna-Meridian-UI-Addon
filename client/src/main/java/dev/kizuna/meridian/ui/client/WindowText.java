package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import java.util.Locale;
import net.minecraft.locale.Language;

/** 共用窗口在新版按语言取词，旧版保留原有的固定文案和数字格式。 */
public enum WindowText {
    CLASSIC, MODERN;

    static WindowText current() {
        // 两份窗口模型沿用各自的展示策略。
        return InventoryUiClient.usesModernWindows() ? MODERN : CLASSIC;
    }

    public static String get(String key, String original, Object... values) {
        // 窗口打开后才查询当前版本，运行中切换版本也会用到对应文案。
        return current().format(key, original, values);
    }

    String format(String key, String original, Object... values) {
        // 译文和原文使用相同的占位符，数值精度由调用方在传参前确定。
        String pattern = original;
        if (this == MODERN) {
            pattern = Language.getInstance().getOrDefault("meridian_ui." + key, original);
        }
        if (values.length == 0) {
            return pattern;
        }
        return String.format(Locale.ROOT, pattern, values);
    }
}
