package dev.kizuna.meridian.ui.client;

/** 小窗摘要的画布：坐标是小窗内的 GUI 像素，文字只有原字号一种大小。 */
public interface SummaryCanvas {
    /** 一段文字按原字号画出来的宽度。 */
    int width(String text);

    /** 当前主题里某个令牌的颜色。 */
    int color(String token);

    void text(int x, int y, String text, int color);

    void fill(int left, int top, int right, int bottom, int color);
}
