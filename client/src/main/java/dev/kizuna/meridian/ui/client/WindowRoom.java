package dev.kizuna.meridian.ui.client;

/** 窗口正文向它所在的窗口要地方。玩家自己定过尺寸的窗口不理会。 */
interface WindowRoom {
    /** 正文要这么大的地方，不含标题栏和边框。 */
    void content(int width, int height);

    /** 整个窗口的外框要这么大。 */
    void frame(int width, int height);
}
