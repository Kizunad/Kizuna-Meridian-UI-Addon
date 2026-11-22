package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 小窗摘要：整屏界面和游戏画面画的是同一份，文字一律原字号，标签靠左、数值靠右。 */
public class CompactSummary implements CompactProvider {
    /** 一行文字的高度，含字下的阴影。 */
    public static final int LINE = 9;
    /** 「标题行、进度条、脚注行」这种摘要的总高。 */
    public static final int METER_HEIGHT = 25;
    private static final int TRACK_TOP = LINE + 2;
    private static final int TRACK_HEIGHT = 2;
    private static final int FOOT_TOP = TRACK_TOP + TRACK_HEIGHT + 3;
    private final List<CompactVariant> variants;
    private final Painter painter;

    @FunctionalInterface
    public interface Painter {
        void draw(SummaryCanvas canvas, CompactVariant variant);
    }

    /** 一段同色的文字。 */
    public record Run(String text, int color) { }

    /** 「标题行、进度条、脚注行」摘要的内容；每行左边是标签，右边是数值。 */
    public record Meter(List<Run> head, Run headValue, double fraction, Run foot, Run footValue) { }

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
        paint(new Screen(graphics), variant);
    }

    /** 把某个样式画到任意画布上；量宽和用例用它重放同一份摘要。 */
    public void paint(SummaryCanvas canvas, String variantId) {
        var variant = variants.stream()
                .filter(candidate -> candidate.id().equals(variantId))
                .findFirst()
                .orElse(variants.getFirst());
        painter.draw(canvas, variant);
    }

    public static Run main(SummaryCanvas canvas, String text) {
        return new Run(text, canvas.color(ThemeTokens.COMPACT_TEXT));
    }

    public static Run dim(SummaryCanvas canvas, String text) {
        return new Run(text, canvas.color(ThemeTokens.COMPACT_MUTED_TEXT));
    }

    public static Run accent(SummaryCanvas canvas, String text) {
        return new Run(text, canvas.color(ThemeTokens.ACCENT));
    }

    /** 从 x 起依次画几段文字，返回画完后的右端。 */
    public static int left(SummaryCanvas canvas, int x, int y, List<Run> runs) {
        int cursor = x;
        for (Run run : runs) {
            canvas.text(cursor, y, run.text(), run.color());
            cursor += canvas.width(run.text());
        }
        return cursor;
    }

    /** 画一段文字，右端贴在 right。 */
    public static void right(SummaryCanvas canvas, int right, int y, Run run) {
        canvas.text(right - canvas.width(run.text()), y, run.text(), run.color());
    }

    /** 进度条：槽占满 left 到 right，填充按比例从左起。 */
    public static void track(SummaryCanvas canvas, int left, int right, int top, double fraction) {
        int fill = (int) Math.round((right - left) * Math.clamp(fraction, 0, 1));
        canvas.fill(left, top, right, top + TRACK_HEIGHT, canvas.color(ThemeTokens.COMPACT_TRACK));
        canvas.fill(left, top, left + fill, top + TRACK_HEIGHT, canvas.color(ThemeTokens.PROGRESS_FILL));
    }

    public static void meter(SummaryCanvas canvas, CompactVariant variant, Meter meter) {
        left(canvas, 0, 0, meter.head());
        right(canvas, variant.width(), 0, meter.headValue());
        track(canvas, 0, variant.width(), TRACK_TOP, meter.fraction());
        left(canvas, 0, FOOT_TOP, List.of(meter.foot()));
        right(canvas, variant.width(), FOOT_TOP, meter.footValue());
    }

    /** 真正画到屏幕上的画布：带阴影，透明底压在明亮地形上也看得清字形。 */
    private record Screen(GuiGraphicsExtractor graphics) implements SummaryCanvas {
        @Override
        public int width(String text) {
            return Minecraft.getInstance().font.width(text);
        }

        @Override
        public int color(String token) {
            return MeridianTheme.color(token);
        }

        @Override
        public void text(int x, int y, String text, int color) {
            graphics.text(Minecraft.getInstance().font, text, x, y, color, true /* dropShadow */);
        }

        @Override
        public void fill(int left, int top, int right, int bottom, int color) {
            graphics.fill(left, top, right, bottom, color);
        }
    }
}
