package dev.kizuna.meridian.ui.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.ui.api.SurfaceReadout;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 体表窗口正文的排法：受伤部位一处一行（名字左、读数右、条在下），说明行接在后面；人形垫着深色底。 */
public class SurfaceSheetTest {
    private static final int WIDTH = 96;
    private static final int FIGURE_WIDTH = 26;
    private static final int FIGURE_HEIGHT = 54;
    private static final int WELL_WIDTH = 38;
    private static final int WELL_HEIGHT = 66;
    private static final int MARK_COLOR = 0xFF7C8A86;
    private static final int TRACK_TOP = 11;
    private static final int TRACK_BOTTOM = 13;
    private static final int NOTE_PITCH = 12;
    private static final double FOREARM_LEFT = 0.25;
    private static final double CHEST_LEFT = 0.775;
    private static final double OVERFULL = 1.2;
    private static final int CAP = 6;
    private static final int FOUR = 4;
    private static final int EIGHT = 8;
    private static final int NOTES_HEIGHT = 21;
    private static final int THREE_ROW_VIEW = 71;
    private static final int TIGHT_VIEW = 30;
    private static final int ROW_TAIL = 3;
    private static final int BAR_WIDTH = 2;

    /** 只画一个占满声明范围的色块的人形。 */
    private record Slab() implements SurfaceReadout.Figure {
        @Override
        public int width() {
            return FIGURE_WIDTH;
        }

        @Override
        public int height() {
            return FIGURE_HEIGHT;
        }

        @Override
        public void draw(SummaryCanvas canvas) {
            canvas.fill(0, 0, FIGURE_WIDTH, FIGURE_HEIGHT, MARK_COLOR);
        }
    }

    private static SurfaceReadout readout() {
        var forearm = new SurfaceReadout.Row("左前臂", "3/12", FOREARM_LEFT);
        var wounds = List.of(forearm, new SurfaceReadout.Row("胸", "31/40", CHEST_LEFT));
        return new SurfaceReadout(new Slab(), wounds, List.of("其余十五处无伤", "游炁 · 18/62 年"), List.of());
    }

    @Test
    public void woundsTakeOneRowEachWithNameLeftReadingRightAndBarBelow() {
        var probe = SummaryProbe.reference();
        SurfaceSheet.list(probe, WIDTH, readout());
        assertEquals(List.of("左前臂 | 3/12", "胸 | 31/40", "其余十五处无伤", "游炁 · 18/62 年"), probe.lines());
        var texts = probe.texts();
        assertEquals(probe.color(ThemeTokens.TEXT), texts.get(0).color());
        assertEquals(probe.color(ThemeTokens.ACCENT), texts.get(1).color());
        assertEquals(WIDTH, texts.get(1).x() + texts.get(1).width());
        assertEquals(SurfaceSheet.ROW, texts.get(2).y());
        assertEquals(WIDTH, texts.get(3).x() + texts.get(3).width());
        int trackColor = probe.color(ThemeTokens.PROGRESS_TRACK);
        int fillColor = probe.color(ThemeTokens.PROGRESS_FILL);
        var track = new SummaryProbe.Block(0, TRACK_TOP, WIDTH, TRACK_BOTTOM, trackColor);
        var fill = new SummaryProbe.Block(0, TRACK_TOP, (int) (WIDTH * FOREARM_LEFT), TRACK_BOTTOM, fillColor);
        assertEquals(List.of(track, fill), probe.blocks().subList(0, 2));
        assertEquals(SurfaceSheet.ROW + TRACK_TOP, probe.blocks().get(2).top());
        int notesTop = SurfaceSheet.ROW * 2;
        assertEquals(probe.color(ThemeTokens.MUTED_TEXT), texts.get(4).color());
        assertEquals(notesTop, texts.get(4).y());
        assertEquals(notesTop + NOTE_PITCH, texts.get(5).y());
        assertEquals(notesTop + NOTE_PITCH + SurfaceSheet.LINE, SurfaceSheet.listHeight(readout()));
    }

    private static SurfaceReadout wounded(int count) {
        var wounds = new ArrayList<SurfaceReadout.Row>();
        for (int index = 0; index < count; index++) {
            wounds.add(new SurfaceReadout.Row("部位" + index, index + "/12", FOREARM_LEFT));
        }
        return new SurfaceReadout(new Slab(), wounds, List.of("其余九处无伤", "游炁 · 18/62 年"), List.of());
    }

    @Test
    public void windowGrowsWithWoundsUpToTheCap() {
        assertEquals(NOTES_HEIGHT, SurfaceSheet.listHeight(wounded(0), CAP));
        assertEquals(SurfaceSheet.ROW * FOUR + NOTES_HEIGHT, SurfaceSheet.listHeight(wounded(FOUR), CAP));
        assertEquals(SurfaceSheet.ROW * CAP + NOTES_HEIGHT, SurfaceSheet.listHeight(wounded(CAP), CAP));
        assertEquals(SurfaceSheet.ROW * CAP + NOTES_HEIGHT, SurfaceSheet.listHeight(wounded(EIGHT), CAP));
        assertEquals(SurfaceSheet.ROW * EIGHT + NOTES_HEIGHT, SurfaceSheet.listHeight(wounded(EIGHT)));
    }

    @Test
    public void crampedListShowsWholeRowsOnly() {
        var eight = wounded(EIGHT);
        int capped = SurfaceSheet.listHeight(eight, CAP);
        assertEquals(new SurfaceSheet.Span(0, CAP, 2), SurfaceSheet.Span.within(eight, capped));
        assertEquals(new SurfaceSheet.Span(0, CAP - 1, 2), SurfaceSheet.Span.within(eight, capped - 1));
        var three = SurfaceSheet.Span.within(eight, THREE_ROW_VIEW);
        assertEquals(new SurfaceSheet.Span(0, 3, 2), three);
        assertTrue(three.height() <= THREE_ROW_VIEW);
        // 连说明行都放不全时先保一处伤，说明行只留放得下的。
        var tight = SurfaceSheet.Span.within(eight, TIGHT_VIEW);
        assertEquals(new SurfaceSheet.Span(0, 1, 1), tight);
        assertTrue(tight.height() <= TIGHT_VIEW);
        assertEquals(new SurfaceSheet.Span(0, 1, 0), SurfaceSheet.Span.within(eight, SurfaceSheet.ROW));
        assertEquals(new SurfaceSheet.Span(0, 2, 2), SurfaceSheet.Span.within(wounded(2), capped));
        assertEquals(new SurfaceSheet.Span(0, 0, 2), SurfaceSheet.Span.within(wounded(0), capped));
    }

    @Test
    public void scrollingMovesOneWoundAtATimeAndStopsAtBothEnds() {
        var eight = wounded(EIGHT);
        var span = SurfaceSheet.Span.within(eight, SurfaceSheet.listHeight(eight, CAP));
        assertEquals(1, span.from(1, EIGHT).first());
        assertEquals(EIGHT - CAP, span.from(EIGHT, EIGHT).first());
        assertEquals(0, span.from(-1, EIGHT).first());
        var probe = SummaryProbe.reference();
        SurfaceSheet.list(probe, WIDTH, eight, new SurfaceSheet.Span(2, 3, 1));
        assertEquals(List.of("部位2 | 2/12", "部位3 | 3/12", "部位4 | 4/12", "其余九处无伤"), probe.lines());
        assertEquals(0, probe.texts().getFirst().y());
        assertEquals(SurfaceSheet.ROW * 3, probe.texts().getLast().y());
    }

    @Test
    public void positionBarMarksTheShownStretch() {
        var probe = SummaryProbe.reference();
        SurfaceSheet.bar(probe, WIDTH, new SurfaceSheet.Span(2, 3, 2), EIGHT);
        int height = SurfaceSheet.ROW * 3 - ROW_TAIL;
        var track = new SummaryProbe.Block(WIDTH - BAR_WIDTH, 0, WIDTH, height, probe.color(ThemeTokens.HEADER_RULE));
        var thumb = new SummaryProbe.Block(WIDTH - BAR_WIDTH, height * 2 / EIGHT, WIDTH, height * (2 + 3) / EIGHT,
                probe.color(ThemeTokens.MUTED_TEXT));
        assertEquals(List.of(track, thumb), probe.blocks());
    }

    @Test
    public void barNeverRunsPastItsTrack() {
        var over = new SurfaceReadout(new Slab(), List.of(new SurfaceReadout.Row("胸", "41/40", OVERFULL)), List.of(),
                List.of());
        var probe = SummaryProbe.reference();
        SurfaceSheet.list(probe, WIDTH, over);
        assertEquals(WIDTH, probe.blocks().getLast().right());
        assertEquals(SurfaceSheet.ROW, SurfaceSheet.listHeight(over));
    }

    @Test
    public void figureSitsCentredOnItsBacking() {
        var probe = SummaryProbe.reference();
        var figure = readout().figure();
        SurfaceSheet.figure(probe, figure);
        assertEquals(WELL_WIDTH, SurfaceSheet.figureWidth(figure));
        assertEquals(WELL_HEIGHT, SurfaceSheet.figureHeight(figure));
        var blocks = probe.blocks();
        assertEquals(new SummaryProbe.Block(0, 0, WELL_WIDTH, WELL_HEIGHT, probe.color(ThemeTokens.HEADER_RULE)),
                blocks.get(0));
        assertEquals(probe.color(ThemeTokens.PREVIEW_BACKGROUND), blocks.get(1).color());
        int left = (WELL_WIDTH - FIGURE_WIDTH) / 2;
        int top = (WELL_HEIGHT - FIGURE_HEIGHT) / 2;
        assertEquals(new SummaryProbe.Block(left, top, left + FIGURE_WIDTH, top + FIGURE_HEIGHT, MARK_COLOR),
                blocks.get(2));
    }
}
