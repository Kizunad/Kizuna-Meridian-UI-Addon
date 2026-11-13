package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import java.util.Locale;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

/** 数据页的界面文字，按当前语言取 {@code meridian_ui.data.*}；中文叫法与检视、吐纳窗口一致，由单测对照。 */
final class DataText {
    static final String PREFIX = "meridian_ui.data.";
    private static final String UNKNOWN = "unknown";

    private DataText() {
        // 只查语言表，不缓存，切换语言后下一帧即换。
    }

    static String get(String key, Object... args) {
        // 数据页自有的一句话，参数按语言表里的 %s 顺序填入。
        return I18n.get(PREFIX + key, args);
    }

    static String share(MeridianData.Share share) {
        // 相态和五行有译名；附属自带的属性没有译名，照写属性 ID。
        String key = PREFIX + "element." + share.key().replace(':', '.');
        if (share.kind() == MeridianData.Kind.PHASE) {
            key = PREFIX + "phase." + share.key().toLowerCase(Locale.ROOT);
        }
        if (!Language.getInstance().has(key)) {
            return share.key();
        }
        return I18n.get(key);
    }

    static String condition(MeridianCondition condition) {
        // 经脉完好、破漏、断裂；未定义的值写「未知」。
        return named("condition.", condition.name());
    }

    static String stage(MeridianOpeningStage stage) {
        // 未开拓、进行中、已开拓；未定义的值写「未知」。
        return named("stage.", stage.name());
    }

    private static String named(String group, String value) {
        // 枚举名小写作键尾；语言表里没有的枚举值归到「未知」。
        String key = PREFIX + group + value.toLowerCase(Locale.ROOT);
        if (!Language.getInstance().has(key)) {
            return get(UNKNOWN);
        }
        return I18n.get(key);
    }
}
