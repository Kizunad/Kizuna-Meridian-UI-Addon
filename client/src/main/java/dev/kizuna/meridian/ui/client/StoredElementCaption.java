package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** 吐纳窗口纳入量下面的「其中火行 0.05」：纳入里有五行真元时才写，哪一行有就写哪一行；一行排不下就折行。 */
final class StoredElementCaption {
    private static final String PREFIX = "其中";
    private static final String SEPARATOR = " · ";
    // 两位小数下显示成 0.00 的不写。
    private static final double VISIBLE_AMOUNT = 0.005;
    /** 按金木水火土的顺序写。 */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("meridian:metal", "金行");
        NAMES.put("meridian:wood", "木行");
        NAMES.put("meridian:water", "水行");
        NAMES.put("meridian:fire", "火行");
        NAMES.put("meridian:earth", "土行");
    }

    private StoredElementCaption() {
        // 只从吐纳视图派生一行文字，不保存状态。
    }

    static Optional<String> of(BreathingView breath) {
        // 不管宽度，全写在一行里。
        List<String> parts = parts(breath);
        return parts.isEmpty() ? Optional.empty() : Optional.of(PREFIX + String.join(SEPARATOR, parts));
    }

    static List<String> lines(BreathingView breath, ToIntFunction<String> widthOf, int width) {
        // 照给定的宽度折行：能并排的并排，排不下的换到下一行，五行都在的时候不会有哪一行被截掉。
        List<String> lines = new ArrayList<>();
        String line = PREFIX;
        for (String part : parts(breath)) {
            boolean opening = line.equals(PREFIX);
            String joined = opening ? line + part : line + SEPARATOR + part;
            if (!opening && widthOf.applyAsInt(joined) > width) {
                lines.add(line);
                line = part;
            } else {
                line = joined;
            }
        }
        if (!line.equals(PREFIX)) {
            lines.add(line);
        }
        return lines;
    }

    private static List<String> parts(BreathingView breath) {
        // 按属性合计入脉明细（不分相态）；无属性和认不得的属性不写。
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Qi entry : breath.getStoredByKind().getEntriesList()) {
            totals.merge(entry.getAttribute(), entry.getAmount(), Double::sum);
        }
        List<String> parts = new ArrayList<>();
        NAMES.forEach((attribute, name) -> {
            double amount = totals.getOrDefault(attribute, 0.0);
            if (amount >= VISIBLE_AMOUNT) {
                parts.add(String.format(Locale.ROOT, "%s %.2f", name, amount));
            }
        });
        return parts;
    }
}
