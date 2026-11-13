package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** 数据页共用的小图元：小标题、横条、分段条和右对齐数字，颜色都取当前主题。 */
final class DataDrawing {
    static final int HEADING_HEIGHT = 14;
    static final int LINE_HEIGHT = 12;
    static final int BAR_HEIGHT = 4;
    static final int PADDING = 6;
    static final String UNIT = " 元";
    private static final int RULE_OFFSET = 11;
    private static final double PERCENT = 100;

    private DataDrawing() {
        // 只画，不持有状态。
    }

    static Font font() {
        // 原版字体，与窗口其余文字一致。
        return Minecraft.getInstance().font;
    }

    static void heading(OwoUIGraphics graphics, String text, int x, int y, int width) {
        // 小标题下面一条细线，把卡片里的几块分开。
        graphics.text(font(), text, x, y, MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
        graphics.fill(x, y + RULE_OFFSET, x + width, y + RULE_OFFSET + 1, MeridianTheme.color(ThemeTokens.BORDER));
    }

    static void text(OwoUIGraphics graphics, String text, int x, int y, String token) {
        // 左对齐的一段字。
        graphics.text(font(), text, x, y, MeridianTheme.color(token), false /* shadow */);
    }

    static void textRight(OwoUIGraphics graphics, String text, int right, int y, String token) {
        // 数字靠右，上下几行的小数点对得齐。
        graphics.text(font(), text, right - font().width(text), y, MeridianTheme.color(token), false /* shadow */);
    }

    static void bar(OwoUIGraphics graphics, MeridianTheme.Bounds bounds, double fraction, int color) {
        // 底槽加已占部分；比例先夹到 [0, 1]。
        int right = bounds.x() + bounds.width();
        int bottom = bounds.y() + bounds.height();
        graphics.fill(bounds.x(), bounds.y(), right, bottom, MeridianTheme.color(ThemeTokens.PROGRESS_TRACK));
        int filled = (int) Math.round(bounds.width() * QiDisplay.ratio(fraction, 1));
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + filled, bottom, color);
    }

    static void stacked(OwoUIGraphics graphics, MeridianTheme.Bounds bounds, List<MeridianData.Share> shares) {
        // 各属性按元数占比依次铺满一条；没有真元时只画底槽。
        int bottom = bounds.y() + bounds.height();
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bottom,
                MeridianTheme.color(ThemeTokens.PROGRESS_TRACK));
        double total = shares.stream().mapToDouble(MeridianData.Share::amount).sum();
        if (total <= 0) {
            return;
        }
        double start = 0;
        for (MeridianData.Share share : shares) {
            int left = bounds.x() + (int) Math.round(bounds.width() * start / total);
            start += share.amount();
            int right = bounds.x() + (int) Math.round(bounds.width() * start / total);
            graphics.fill(left, bounds.y(), right, bottom, MeridianData.elementColor(share.key()));
        }
    }

    static String number(double value) {
        // 和检视窗口一样保留两位，0.01 元也看得见。
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String percent(double fraction) {
        // 整数百分比。
        return String.format(Locale.ROOT, "%.0f%%", fraction * PERCENT);
    }

    static String amountOf(MeridianData.Account account) {
        // 「已有 / 可装 元」。
        return number(account.amount()) + " / " + number(account.capacity()) + UNIT;
    }

    static String share(double amount, double total) {
        // 占全身的百分比；全身为空时写 0%。
        return percent(QiDisplay.ratio(amount, total));
    }
}
