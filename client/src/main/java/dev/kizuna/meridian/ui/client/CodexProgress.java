package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.combat.fabric.client.CombatSlotAccess;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 已同步的已学列表和这具身体的境界，换算成每卷的三种状态与完成度；只读快照。 */
record CodexProgress(Map<String, KnownTechnique> known, int realm) {
    enum State { LEARNED, MISSING, OUT_OF_REACH }

    static CodexProgress current() {
        // 已学列表来自 Combat 的权威投影，境界来自身体窗口已校验的同一份快照。
        var entries = new LinkedHashMap<String, KnownTechnique>();
        CombatSlotAccess.snapshot().known().getTechniquesList().forEach(entry -> entries.put(entry.getId(), entry));
        return new CodexProgress(Map.copyOf(entries), BodyUiModule.realm());
    }

    State state(CodexCatalog.Volume volume) {
        // 学全了就点亮；没学且最低一式的境界也够不着时标境界要求，否则是剪影。
        if (learned(volume)) {
            return State.LEARNED;
        }
        if (volume.minimumRealm() > realm) {
            return State.OUT_OF_REACH;
        }
        return State.MISSING;
    }

    boolean learned(CodexCatalog.Volume volume) {
        // 卷里每一式都在已学列表里才算学会。
        return known.keySet().containsAll(volume.techniqueIds());
    }

    boolean learned(String volumeId, CodexCatalog catalog) {
        // 前置卷按身份查，目录里没有的前置视作未学。
        return catalog.volume(volumeId).map(this::learned).orElse(false);
    }

    int learnedCount(CodexCatalog.Book book) {
        // 一本书的分子。
        return (int) book.volumes().stream().filter(this::learned).count();
    }

    int learnedCount(CodexCatalog catalog) {
        // 全谱的分子。
        return catalog.books().stream().mapToInt(this::learnedCount).sum();
    }

    Set<String> learnedVolumes(CodexCatalog catalog) {
        // 点亮提示比较前后两份已学卷。
        return catalog.books().stream().flatMap(book -> book.volumes().stream()).filter(this::learned)
                .map(CodexCatalog.Volume::id).collect(java.util.stream.Collectors.toSet());
    }
}
