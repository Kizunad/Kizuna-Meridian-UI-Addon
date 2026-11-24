package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import java.util.List;

/**
 * 新版吐纳窗口场景下面的余力一行：名字靠左、百分数靠右、底下一条细条，自动吐纳时条上多两道标线。
 * 画在 SummaryCanvas 上，文字只有原字号。
 */
public final class BreathSheet {
    /** 一行文字的高度。 */
    public static final int LINE = 9;
    /** 余力那一行连条带条下的空一共多高。 */
    public static final int RESERVE_HEIGHT = 16;
    private static final int TRACK_TOP = LINE + 2;
    private static final int TRACK_HEIGHT = 2;
    private static final int MARK_OVERHANG = 1;

    private BreathSheet() {
    }

    /** 余力一行要写要画的：名字、百分数、条填到几成，以及条上要竖着标出来的几处比例（自动吐纳的暂停线、恢复线）。 */
    public record Reserve(String label, String value, double fraction, List<Mark> marks) {
    }

    /** 条上的一道竖线：在几成的位置、用哪个颜色。 */
    public record Mark(double at, String token) {
    }

    /** 自动吐纳时的两道线：低于暂停线停下（错误色），回到恢复线继续（进度色）。 */
    public static List<Mark> automaticMarks(double pause, double resume) {
        return List.of(new Mark(pause, ThemeTokens.INPUT_ERROR), new Mark(resume, ThemeTokens.PROGRESS_FILL));
    }

    /** 余力一行：名字靠左，百分数右端贴在 width，下面一条槽和一段填充，再画标线。 */
    public static void reserve(SummaryCanvas canvas, int width, Reserve reserve) {
        canvas.text(0, 0, reserve.label(), canvas.color(ThemeTokens.TEXT));
        canvas.text(width - canvas.width(reserve.value()), 0, reserve.value(), canvas.color(ThemeTokens.ACCENT));
        int bottom = TRACK_TOP + TRACK_HEIGHT;
        int fill = (int) Math.round(width * Math.clamp(reserve.fraction(), 0, 1));
        canvas.fill(0, TRACK_TOP, width, bottom, canvas.color(ThemeTokens.PROGRESS_TRACK));
        canvas.fill(0, TRACK_TOP, fill, bottom, canvas.color(ThemeTokens.PROGRESS_FILL));
        for (var mark : reserve.marks()) {
            int at = Math.min(width - 1, (int) Math.round(width * Math.clamp(mark.at(), 0, 1)));
            canvas.fill(at, TRACK_TOP - MARK_OVERHANG, at + 1, bottom + MARK_OVERHANG, canvas.color(mark.token()));
        }
    }
}
