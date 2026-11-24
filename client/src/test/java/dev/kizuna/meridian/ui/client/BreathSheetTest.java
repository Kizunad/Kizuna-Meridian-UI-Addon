package dev.kizuna.meridian.ui.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import java.util.List;
import org.junit.Test;

/** 吐纳窗口余力一行的排法：名字左、百分数右端贴边、条在下，自动吐纳时条上多两道标线。 */
public class BreathSheetTest {
    private static final int WIDTH = 160;
    private static final int TRACK_TOP = 11;
    private static final int TRACK_BOTTOM = 13;
    private static final double ALMOST_FULL = 0.94;
    private static final double PAUSE = 0.2;
    private static final double RESUME = 0.6;
    private static final double OVERFULL = 1.3;

    @Test
    public void reserveRowPutsNameLeftPercentRightAndBarBelow() {
        var probe = SummaryProbe.reference();
        BreathSheet.reserve(probe, WIDTH, new BreathSheet.Reserve("余力", "94%", ALMOST_FULL, List.of()));
        assertEquals(List.of("余力 | 94%"), probe.lines());
        var texts = probe.texts();
        assertEquals(0, texts.get(0).x());
        assertEquals(probe.color(ThemeTokens.TEXT), texts.get(0).color());
        assertEquals(WIDTH, texts.get(1).x() + texts.get(1).width());
        assertEquals(probe.color(ThemeTokens.ACCENT), texts.get(1).color());
        var track = new SummaryProbe.Block(0, TRACK_TOP, WIDTH, TRACK_BOTTOM, probe.color(ThemeTokens.PROGRESS_TRACK));
        var fill = new SummaryProbe.Block(0, TRACK_TOP, (int) Math.round(WIDTH * ALMOST_FULL), TRACK_BOTTOM,
                probe.color(ThemeTokens.PROGRESS_FILL));
        assertEquals(List.of(track, fill), probe.blocks());
        assertTrue(TRACK_BOTTOM < BreathSheet.RESERVE_HEIGHT);
    }

    @Test
    public void automaticBreathingMarksPauseAndResumeOnTheBar() {
        var probe = SummaryProbe.reference();
        var marks = BreathSheet.automaticMarks(PAUSE, RESUME);
        BreathSheet.reserve(probe, WIDTH, new BreathSheet.Reserve("呼吸耐力", "50%", RESUME - PAUSE, marks));
        var blocks = probe.blocks();
        int pauseLeft = (int) Math.round(WIDTH * PAUSE);
        int resumeLeft = (int) Math.round(WIDTH * RESUME);
        assertEquals(new SummaryProbe.Block(pauseLeft, TRACK_TOP - 1, pauseLeft + 1, TRACK_BOTTOM + 1,
                probe.color(ThemeTokens.INPUT_ERROR)), blocks.get(2));
        assertEquals(new SummaryProbe.Block(resumeLeft, TRACK_TOP - 1, resumeLeft + 1, TRACK_BOTTOM + 1,
                probe.color(ThemeTokens.PROGRESS_FILL)), blocks.get(3));
    }

    @Test
    public void barAndMarksStayInsideTheRow() {
        var probe = SummaryProbe.reference();
        var marks = List.of(new BreathSheet.Mark(OVERFULL, ThemeTokens.INPUT_ERROR));
        BreathSheet.reserve(probe, WIDTH, new BreathSheet.Reserve("余力", "130%", OVERFULL, marks));
        var blocks = probe.blocks();
        assertEquals(WIDTH, blocks.get(1).right());
        assertEquals(WIDTH, blocks.get(2).right());
        assertEquals(WIDTH - 1, blocks.get(2).left());
    }
}
