package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.ui.api.SurfaceReadout;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.Supplier;

/**
 * 体表窗口正文的两块：左边垫深色底的人形，右边受伤部位逐行（名字靠左、读数靠右、底下一条 HP 条）再接说明行。
 * 两块都画在 SummaryCanvas 上，文字只有原字号。
 */
public final class SurfaceSheet {
    /** 一行文字的高度。 */
    public static final int LINE = 9;
    /** 一处受伤部位占的高度：一行字、HP 条和条下的空。 */
    public static final int ROW = 16;
    /** 说明行之间的空。 */
    public static final int NOTE_GAP = 3;
    private static final int FULL = 100;
    private static final int TRACK_TOP = LINE + 2;
    private static final int TRACK_HEIGHT = 2;
    private static final int WELL_PADDING_X = 6;
    private static final int WELL_PADDING_Y = 6;

    private SurfaceSheet() {
    }

    /** 人形连同深色底一共多宽。 */
    public static int figureWidth(SurfaceReadout.Figure figure) {
        return figure.width() + WELL_PADDING_X * 2;
    }

    /** 人形连同深色底一共多高。 */
    public static int figureHeight(SurfaceReadout.Figure figure) {
        return figure.height() + WELL_PADDING_Y * 2;
    }

    /** 受伤部位和说明行一共多高。 */
    public static int listHeight(SurfaceReadout readout) {
        int notes = readout.notes().size();
        return readout.wounds().size() * ROW + notes * LINE + Math.max(0, notes - 1) * NOTE_GAP;
    }

    /** 深色底、一圈细边，人形摆在正中。 */
    public static void figure(SummaryCanvas canvas, SurfaceReadout.Figure figure) {
        int width = figureWidth(figure);
        int height = figureHeight(figure);
        canvas.fill(0, 0, width, height, canvas.color(ThemeTokens.HEADER_RULE));
        canvas.fill(1, 1, width - 1, height - 1, canvas.color(ThemeTokens.PREVIEW_BACKGROUND));
        figure.draw(new Shifted(canvas, WELL_PADDING_X, WELL_PADDING_Y));
    }

    /** 受伤部位自上而下各占一行，说明行接在后面；右端都贴在 width。 */
    public static void list(SummaryCanvas canvas, int width, SurfaceReadout readout) {
        int y = 0;
        for (var wound : readout.wounds()) {
            canvas.text(0, y, wound.name(), canvas.color(ThemeTokens.TEXT));
            int valueLeft = width - canvas.width(wound.value());
            canvas.text(valueLeft, y, wound.value(), canvas.color(ThemeTokens.ACCENT));
            int fill = (int) Math.round(width * Math.clamp(wound.fraction(), 0, 1));
            int trackTop = y + TRACK_TOP;
            canvas.fill(0, trackTop, width, trackTop + TRACK_HEIGHT, canvas.color(ThemeTokens.PROGRESS_TRACK));
            canvas.fill(0, trackTop, fill, trackTop + TRACK_HEIGHT, canvas.color(ThemeTokens.PROGRESS_FILL));
            y += ROW;
        }
        for (String note : readout.notes()) {
            canvas.text(0, y, note, canvas.color(ThemeTokens.MUTED_TEXT));
            y += LINE + NOTE_GAP;
        }
    }

    /** 把原点挪开一段再画。 */
    private record Shifted(SummaryCanvas canvas, int left, int top) implements SummaryCanvas {
        @Override
        public int width(String text) {
            return canvas.width(text);
        }

        @Override
        public int color(String token) {
            return canvas.color(token);
        }

        @Override
        public void text(int x, int y, String text, int color) {
            canvas.text(left + x, top + y, text, color);
        }

        @Override
        public void fill(int fromX, int fromY, int toX, int toY, int color) {
            canvas.fill(left + fromX, top + fromY, left + toX, top + toY, color);
        }
    }

    /** 人形那一块；大小跟着人形走。 */
    static final class Figure extends BaseUIComponent {
        private final Supplier<SurfaceReadout> readout;

        Figure(Supplier<SurfaceReadout> readout) {
            this.readout = readout;
            var figure = readout.get().figure();
            sizing(Sizing.fixed(figureWidth(figure)), Sizing.fixed(figureHeight(figure)));
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            figure(new WindowCanvas(graphics, x, y), readout.get().figure());
        }
    }

    /** 受伤部位那一块；宽度跟着所在的滚动区，高度是内容高，行数变了由窗口重新给。 */
    static final class Wounds extends BaseUIComponent {
        private final Supplier<SurfaceReadout> readout;
        private int rightInset;

        Wounds(Supplier<SurfaceReadout> readout) {
            this.readout = readout;
            fit();
        }

        /** 按当前内容重定高度，返回这个高度。 */
        int fit() {
            int content = Math.max(1, listHeight(readout.get()));
            sizing(Sizing.fill(FULL), Sizing.fixed(content));
            return content;
        }

        /** 右边让出多少像素不画（留给滚动条）。 */
        void rightInset(int pixels) {
            rightInset = pixels;
        }

        /** 实际画出来的宽度：读数的右端贴在这里。 */
        int drawnWidth() {
            return width - rightInset;
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            list(new WindowCanvas(graphics, x, y), drawnWidth(), readout.get());
        }
    }
}
