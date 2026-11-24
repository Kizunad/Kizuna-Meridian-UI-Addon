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
    private static final int TRACK_TOP = LINE + 2;
    private static final int TRACK_HEIGHT = 2;
    private static final int WELL_PADDING_X = 6;
    private static final int WELL_PADDING_Y = 6;
    private static final int BAR_WIDTH = 2;
    private static final int BAR_GAP = 2;

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

    /** 这么多行说明一共多高。 */
    public static int notesHeight(int count) {
        return count * LINE + Math.max(0, count - 1) * NOTE_GAP;
    }

    /** 受伤部位和说明行一共多高。 */
    public static int listHeight(SurfaceReadout readout) {
        return Span.all(readout).height();
    }

    /** 受伤部位最多露 maxRows 处时，连说明行一共多高。 */
    public static int listHeight(SurfaceReadout readout, int maxRows) {
        return Math.min(readout.wounds().size(), maxRows) * ROW + notesHeight(readout.notes().size());
    }

    /** 列表露出来的一段：从第几处伤起、露几处、说明行露几行。都是整行。 */
    public record Span(int first, int rows, int notes) {
        /** 全部露出来。 */
        public static Span all(SurfaceReadout readout) {
            return new Span(0, readout.wounds().size(), readout.notes().size());
        }

        /** 这么高的地方能放下的一段：先给说明行留位，剩下的放受伤部位，至少一处；说明行放不全就只留放得下的几行。 */
        public static Span within(SurfaceReadout readout, int height) {
            int wounds = readout.wounds().size();
            int notes = readout.notes().size();
            int rows = Math.clamp((height - notesHeight(notes)) / ROW, Math.min(1, wounds), wounds);
            int left = height - rows * ROW;
            return new Span(0, rows, Math.clamp((left + NOTE_GAP) / (LINE + NOTE_GAP), 0, notes));
        }

        /** 改从第 start 处伤起；越界时夹回头尾。 */
        public Span from(int start, int wounds) {
            return new Span(Math.clamp(start, 0, Math.max(0, wounds - rows)), rows, notes);
        }

        /** 这一段画出来多高。 */
        public int height() {
            return rows * ROW + notesHeight(notes);
        }
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
        list(canvas, width, readout, Span.all(readout));
    }

    /** 只画 span 这一段：受伤部位从上往下，说明行接在后面。 */
    public static void list(SummaryCanvas canvas, int width, SurfaceReadout readout, Span span) {
        int y = 0;
        for (var wound : readout.wounds().subList(span.first(), span.first() + span.rows())) {
            canvas.text(0, y, wound.name(), canvas.color(ThemeTokens.TEXT));
            int valueLeft = width - canvas.width(wound.value());
            canvas.text(valueLeft, y, wound.value(), canvas.color(ThemeTokens.ACCENT));
            int fill = (int) Math.round(width * Math.clamp(wound.fraction(), 0, 1));
            int trackTop = y + TRACK_TOP;
            canvas.fill(0, trackTop, width, trackTop + TRACK_HEIGHT, canvas.color(ThemeTokens.PROGRESS_TRACK));
            canvas.fill(0, trackTop, fill, trackTop + TRACK_HEIGHT, canvas.color(ThemeTokens.PROGRESS_FILL));
            y += ROW;
        }
        for (String note : readout.notes().subList(0, span.notes())) {
            canvas.text(0, y, note, canvas.color(ThemeTokens.MUTED_TEXT));
            y += LINE + NOTE_GAP;
        }
    }

    /** 列表右边的位置条：槽和受伤部位那几行一样高，滑块标出露着的是哪一段。 */
    public static void bar(SummaryCanvas canvas, int right, Span span, int wounds) {
        int height = span.rows() * ROW - (ROW - TRACK_TOP - TRACK_HEIGHT);
        int top = height * span.first() / wounds;
        int bottom = height * (span.first() + span.rows()) / wounds;
        canvas.fill(right - BAR_WIDTH, 0, right, height, canvas.color(ThemeTokens.HEADER_RULE));
        canvas.fill(right - BAR_WIDTH, top, right, bottom, canvas.color(ThemeTokens.MUTED_TEXT));
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

    /** 受伤部位那一块：放不下时只露整行的一段，滚轮一格挪一处伤，右边让出位置条。 */
    static final class Wounds extends BaseUIComponent {
        private final Supplier<SurfaceReadout> readout;
        private int first;

        Wounds(Supplier<SurfaceReadout> readout) {
            this.readout = readout;
        }

        /** 现在露出来的一段。 */
        Span span() {
            var now = readout.get();
            return Span.within(now, height).from(first, now.wounds().size());
        }

        private boolean scrolls(Span span) {
            return span.rows() < readout.get().wounds().size();
        }

        /** 实际画出来的宽度：读数的右端贴在这里。 */
        int drawnWidth() {
            if (scrolls(span())) {
                return width - BAR_WIDTH - BAR_GAP;
            }
            return width;
        }

        @Override
        public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
            var span = span();
            if (!scrolls(span)) {
                return false;
            }
            int step = (int) Math.signum(amount);
            first = span.from(span.first() - step, readout.get().wounds().size()).first();
            return true;
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            var now = readout.get();
            var span = span();
            // 伤好了几处以后，起点跟着夹回来。
            first = span.first();
            var canvas = new WindowCanvas(graphics, x, y);
            list(canvas, drawnWidth(), now, span);
            if (scrolls(span)) {
                bar(canvas, width, span, now.wounds().size());
            }
        }
    }
}
