package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 吐纳窗口纳入量下面那行「游离态 0.12 · 气态 0.12」：纳入里有游离态以外的相态时才分开写。 */
final class StoredPhaseCaption {
    private static final String SEPARATOR = " · ";
    private static final Map<QiPhase, String> NAMES = new EnumMap<>(Map.of(
            QiPhase.FREE, "游离态",
            QiPhase.GAS, "气态",
            QiPhase.LIQUID, "液态",
            QiPhase.SOLID, "固态",
            QiPhase.ORDERED, "有序态",
            QiPhase.COHERENT, "相干态"));

    private StoredPhaseCaption() {
        // 只从吐纳视图派生一行文字，不保存状态。
    }

    static Optional<String> of(BreathingView breath) {
        // 按相态合计入脉明细；全是游离态时纳入量本身已经说清，不再多写一行。
        var totals = new EnumMap<QiPhase, Double>(QiPhase.class);
        for (Qi entry : breath.getStoredByKind().getEntriesList()) {
            totals.merge(QiDensity.phase(entry.getDensity()), entry.getAmount(), Double::sum);
        }
        boolean onlyFree = totals.keySet().stream().allMatch(phase -> phase == QiPhase.FREE);
        if (onlyFree) {
            return Optional.empty();
        }
        totals.putIfAbsent(QiPhase.FREE, 0.0);
        List<String> parts = new ArrayList<>();
        totals.forEach((phase, amount) ->
                parts.add(String.format(Locale.ROOT, "%s %.2f", NAMES.get(phase), amount)));
        return Optional.of(String.join(SEPARATOR, parts));
    }
}
