package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 小窗在宿主与 HUD 共用同一局部坐标和主题。 */
public class CompactSummary implements CompactProvider {
    public static final int LINE = 10;
    private static final int BAR_HEIGHT = 3;
    private final List<CompactVariant> variants;
    private final Painter painter;

    @FunctionalInterface
    public interface Painter {
        void draw(GuiGraphicsExtractor graphics, String variant);
    }

    public CompactSummary(List<CompactVariant> variants, Painter painter) {
        // 首项也是新配置的默认样式。
        this.variants = List.copyOf(variants);
        this.painter = painter;
    }

    @Override
    public List<CompactVariant> variants() {
        return variants;
    }

    @Override
    public void draw(GuiGraphicsExtractor graphics, WindowState state, String variant, float delta) {
        // 权威状态由模块持有，绘制入口不触发订阅和输入。
        painter.draw(graphics, variant);
    }

    public static void text(GuiGraphicsExtractor graphics, int x, int y, String value) {
        // 阴影在透明底和明亮地形上保留字形。
        graphics.text(Minecraft.getInstance().font, value, x, y, MeridianTheme.color(ThemeTokens.TEXT),
                true /* dropShadow */);
    }

    public static void fit(GuiGraphicsExtractor graphics, int x, int y, int width, String value) {
        // 固定宽度的数字列缩排整行，保留末尾的单位与读数。
        var pose = graphics.pose();
        float scale = Math.min(1f, (float) width / Math.max(1, Minecraft.getInstance().font.width(value)));
        pose.pushMatrix();
        try {
            pose.translate(x, y);
            pose.scale(scale, scale);
            text(graphics, 0, 0, value);
        } finally {
            pose.popMatrix();
        }
    }

    public static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, double fraction) {
        // 余力与疲劳均按同一比例夹取到可见条宽。
        int fill = (int) Math.round(width * Math.clamp(fraction, 0, 1));
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, MeridianTheme.color(ThemeTokens.PROGRESS_TRACK));
        graphics.fill(x, y, x + fill, y + BAR_HEIGHT, MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
    }
}
