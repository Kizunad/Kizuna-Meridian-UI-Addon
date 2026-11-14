package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.technique.fabric.client.TechniqueVolumeSourcesClient;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ScrollDrop;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeKnower;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 未得之卷的来源暗示：谁身上有、好不好找，不写概率。句式在语言文件 {@code codex.hint.*}，由 Gemini 按事实写成。
 *
 * <p>来源是服务器进服时发来的那份（{@link TechniqueVolumeSourcesClient}）：各附属在服务端登记「哪类生人修到哪一境会这卷」
 * 和「哪个出身的仇家身上带着这卷」。这里只拼展示句子，不参与掉落判定。</p>
 */
final class CodexSources {
    private static final int WIDE_SPREAD = 4;
    private static final double COMMON_DROP = 0.2;
    private static final double EVERYONE = 1;

    private CodexSources() {
        // 只做展示文字。
    }

    static String hint(CodexCatalog.Volume volume) {
        // 功法包自己写的来历优先；否则按会它的生人拼一句；有出身仇家的另起一句；都没有就是下落不明。
        String key = "codex.source." + volume.id().replace(':', '.');
        if (Language.getInstance().has(key)) {
            return text(key);
        }
        var sources = TechniqueVolumeSourcesClient.current().getSourcesList().stream()
                .filter(source -> source.getVolumeId().equals(volume.id())).toList();
        var lines = new ArrayList<String>();
        var holders = holders(volume, sources);
        if (!holders.isEmpty()) {
            lines.add(knowerHint(volume, holders));
        }
        sources.stream().filter(VolumeSource::hasFoe).map(source -> text(source.getFoe().getOriginKey())).distinct()
                .forEach(origin -> lines.add(text("codex.hint.origin_foe").replace("{出身}", origin)));
        if (lines.isEmpty()) {
            return text("codex.hint.unknown");
        }
        return String.join("\n", lines);
    }

    private static String knowerHint(CodexCatalog.Volume volume, List<VolumeKnower> holders) {
        // 四种以上生人都会的用「各路」句式；否则写出生人名，再按人人会还是少数人会、好不好找选句式。
        String find = common(volume) ? "_common" : "_rare";
        String realm = realmName(holders.getFirst().getRealmValue());
        if (holders.size() >= WIDE_SPREAD) {
            return text("codex.hint.wide" + find).replace("{境界}", realm);
        }
        String share = holders.stream().allMatch(knower -> knower.getShare() >= EVERYONE) ? "everyone" : "some";
        var names = holders.stream().map(knower -> entityName(knower.getEntityType())).distinct().toList();
        return text("codex.hint." + share + find).replace("{生人}", String.join("、", names))
                .replace("{境界}", realm);
    }

    static List<VolumeKnower> holders(CodexCatalog.Volume volume, List<VolumeSource> sources) {
        // 只有声明了掉落的生人才能把卷交到玩家手上，会而不掉的不算；每种生人只留最低会它的那一境，低境界的排前。
        Set<String> dropping = volume.drops().stream().map(ScrollDrop::getEntityType).collect(Collectors.toSet());
        var lowest = new LinkedHashMap<String, VolumeKnower>();
        sources.stream().filter(VolumeSource::hasKnower).map(VolumeSource::getKnower)
                .filter(knower -> dropping.contains(knower.getEntityType()))
                .sorted(Comparator.comparingInt(VolumeKnower::getRealmValue))
                .forEach(knower -> lowest.putIfAbsent(knower.getEntityType(), knower));
        return List.copyOf(lowest.values());
    }

    private static boolean common(CodexCatalog.Volume volume) {
        // 掉落声明里最大的那份决定说「常能找到」还是「难得找到」。
        return volume.drops().stream().mapToDouble(ScrollDrop::getChance).max().orElse(0) >= COMMON_DROP;
    }

    private static String text(String key) {
        // 句式都在语言文件里，缺键时直接显示键名，验收一眼可见。
        return Language.getInstance().getOrDefault(key);
    }

    static String entityName(String entityType) {
        // 生物名跟着游戏语言走，客户端没有这种实体时保留中性说法。
        var id = Identifier.tryParse(entityType);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return "生人";
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(id).getDescription().getString();
    }

    static String realmName(int realm) {
        // 与功法库同一套境界叫法。
        return dev.kizuna.meridian.technique.server.TechniqueNames.realm(realm);
    }
}
