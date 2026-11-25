package dev.kizuna.meridian.ui.client;

import java.util.Locale;

/** 小窗里的数：位数随大小收短，最长四个数字宽，过万写成「万」。完整精度在展开窗里看。 */
public final class CompactNumbers {
    private static final double TEN_THOUSAND = 10_000;
    private static final double TWO_DECIMAL_LIMIT = 9.995;
    private static final double ONE_DECIMAL_LIMIT = 99.95;
    private static final double INTEGER_LIMIT = 9999.5;
    private static final double ONE_DECIMAL_WAN_LIMIT = 9.95 * TEN_THOUSAND;
    private static final String UNKNOWN = "—";
    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final int TEN = 10;
    private static final int CHINESE_LIMIT = 100;

    private CompactNumbers() {
    }

    /** 小额保留两位小数，例如在途和累计入脉。负数表示读数还没到。 */
    public static String fine(double value) {
        if (value >= 0 && value < TWO_DECIMAL_LIMIT) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return coarse(value);
    }

    /** 最多一位小数，例如真元余额和容量。负数表示读数还没到。 */
    public static String coarse(double value) {
        if (value < 0) {
            return UNKNOWN;
        }
        if (value < ONE_DECIMAL_LIMIT) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return whole(value);
    }

    /** 整数读数，例如部位 HP 和周天圈数。负数表示读数还没到。 */
    public static String whole(double value) {
        // 大额读数按万缩短，以免数字挤占相邻读数。
        if (value < 0) {
            return UNKNOWN;
        }
        if (value < INTEGER_LIMIT) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value < ONE_DECIMAL_WAN_LIMIT) {
            String units = String.format(Locale.ROOT, "%.1f", value / TEN_THOUSAND);
            return WindowText.get("compact.ten_thousand", "%s万", units);
        }
        String units = String.format(Locale.ROOT, "%.0f", value / TEN_THOUSAND);
        return WindowText.get("compact.ten_thousand", "%s万", units);
    }

    /** 受伤部位的 HP 往下取整，没满的读数不会显示成满的。 */
    public static String floored(double value) {
        if (value < 0) {
            return UNKNOWN;
        }
        return whole(Math.floor(value));
    }

    public static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", Math.clamp(ratio, 0, 1) * CHINESE_LIMIT);
    }

    /** 一百以内的个数写成汉字，例如「十四」；再大就写数字。 */
    public static String chinese(int count) {
        if (count < 0 || count >= CHINESE_LIMIT) {
            return Integer.toString(count);
        }
        if (count < TEN) {
            return DIGITS[count];
        }
        String tens = "十";
        if (count / TEN > 1) {
            tens = DIGITS[count / TEN] + "十";
        }
        if (count % TEN == 0) {
            return tens;
        }
        return tens + DIGITS[count % TEN];
    }
}
