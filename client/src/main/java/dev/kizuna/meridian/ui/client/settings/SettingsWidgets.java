package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

/** 设置页面使用的中性主题控件，不包含业务字段与校验。 */
public final class SettingsWidgets {
    public static final int FULL = 100;
    public static final int GAP = 6;
    public static final int BUTTON_HEIGHT = 20;

    private SettingsWidgets() {
        // 保持集中宿主与各适配页使用同一工作区主题。
    }

    public static LabelComponent label(ClientRuntime runtime, String text) {
        // 文本颜色由工作区主题提供，不采用书页配色。
        var label = UIComponents.label(Component.literal(text)).color(Color.ofArgb(runtime.color(ThemeTokens.TEXT)));
        label.horizontalSizing(Sizing.fill(FULL));
        return label;
    }

    public static ButtonComponent button(ClientRuntime runtime, String title, Runnable action) {
        // 按钮用主题令牌绘制，行为只由调用方传入。
        var button = UIComponents.button(Component.literal(title), ignored -> action.run());
        button.renderer((graphics, component, delta) -> {
            String token = ThemeTokens.CONTROL;
            if (component.isHoveredOrFocused()) {
                token = ThemeTokens.CONTROL_HOVER;
            }
            int x = component.getX();
            int y = component.getY();
            graphics.fill(x, y, x + component.getWidth(), y + component.getHeight(), runtime.color(token));
        });
        button.horizontalSizing(Sizing.fill(FULL));
        button.verticalSizing(Sizing.fixed(BUTTON_HEIGHT));
        return button;
    }
}
