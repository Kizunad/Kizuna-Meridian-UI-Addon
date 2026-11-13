package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 吐纳窗口顶上那行「此地灵气：充盈，偏木」：只读服务端随吐纳视图发来的当地环境，不在客户端推算。 */
final class EnvironmentCaption {
    /** 每格不到这么多元算稀薄：沙漠、恶地、雪原一带。 */
    static final double THIN_BELOW = 0.5;
    /** 每格达到这么多元算充盈：森林深处、沼泽、丛林一带。 */
    static final double RICH_FROM = 1.2;
    private static final int PERCENT = 100;
    private static final Map<String, String> ELEMENTS = Map.of(
            "meridian:metal", "金", "meridian:wood", "木", "meridian:water", "水",
            "meridian:fire", "火", "meridian:earth", "土");

    private EnvironmentCaption() {
        // 只从环境视图派生文字，不保存状态。
    }

    static String of(GatheringEnvironment environment) {
        // 浓淡看每格的元数，偏向看哪一行最多；不在主世界时当地没有灵气。
        if (!environment.getAvailable()) {
            return "此地没有灵气可纳";
        }
        String level = "寻常";
        if (environment.getConcentrationPerBlock() < THIN_BELOW) {
            level = "稀薄";
        } else if (environment.getConcentrationPerBlock() >= RICH_FROM) {
            level = "充盈";
        }
        var shares = shares(environment);
        if (shares.isEmpty()) {
            return "此地灵气：" + level;
        }
        return "此地灵气：" + level + "，偏" + shares.get(0).getKey();
    }

    static List<String> details(GatheringEnvironment environment) {
        // 悬浮里给出读数本身：浓度、五行各占几成、这一带每秒最多纳多少。
        if (!environment.getAvailable()) {
            return List.of("只有主世界的地界有灵气。");
        }
        var lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT, "浓度 %.2f 元/格", environment.getConcentrationPerBlock()));
        var parts = new ArrayList<String>();
        for (var share : shares(environment)) {
            parts.add(String.format(Locale.ROOT, "%s %.0f%%", share.getKey(), share.getValue() * PERCENT));
        }
        if (!parts.isEmpty()) {
            lines.add(String.join(" · ", parts));
        }
        lines.add(String.format(Locale.ROOT, "这一带每秒最多纳 %.2f 元，在场的人共用", environment.getSupplyPerSecond()));
        lines.add("地形不同，浓淡和五行都不同；纳走的会变少，身上散出的真元会回到当地。");
        return List.copyOf(lines);
    }

    private static List<Map.Entry<String, Double>> shares(GatheringEnvironment environment) {
        // 按五行合计后从多到少排；没有可读名字的属性不列。
        var totals = new LinkedHashMap<String, Double>();
        double total = 0;
        for (Qi entry : environment.getQi().getEntriesList()) {
            total += entry.getAmount();
            String name = ELEMENTS.get(entry.getAttribute());
            if (name != null) {
                totals.merge(name, entry.getAmount(), Double::sum);
            }
        }
        if (total <= 0) {
            return List.of();
        }
        double all = total;
        return totals.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue() / all))
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .toList();
    }
}
