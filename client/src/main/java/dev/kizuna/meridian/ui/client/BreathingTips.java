package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** 吐纳窗口各读数的悬浮说明。两版窗口排法不同，说明是同一份。 */
final class BreathingTips {
    private static final int PERCENT = 100;

    private BreathingTips() {
    }

    /** 肺里现在有多少升空气、最多多少升。 */
    static List<Component> air(BreathingView breath) {
        // 升数保留一位小数，两版沿用相同精度。
        return List.of(line("air", "空气 %s / %s L",
                String.format(Locale.ROOT, "%.1f", breath.getAirLiters()),
                String.format(Locale.ROOT, "%.1f", breath.getCapacityLiters())));
    }

    /** 还能维持多久，以及耐力、精力各剩多少；自动吐纳只看耐力。 */
    static List<Component> reserve(BreathingView breath) {
        // 自动吐纳只取呼吸耐力，手动还要受精力限制。
        double remaining = breath.getStaminaRatio();
        if (!breath.getAutomatic()) {
            remaining = Math.min(breath.getStaminaRatio(), breath.getAttentionRatio());
        }
        return List.of(
                line("reserve", "维持 %s", percent(remaining)),
                line("diaphragm", "横膈肌耐力 %s", percent(breath.getStaminaRatio())),
                line("attention", "精力 %s", percent(breath.getAttentionRatio())));
    }

    /** 纳入量的总账，返回的列表可以再往后接行。 */
    static List<Component> qi(GatheringView view) {
        // 每个账户各计一次；在途占用目的容量，不能再增加最大值。
        BreathingView breath = view.getBreathing();
        BodySnapshot body = view.getBody();
        double totalQi = QiDisplay.total(body);
        double maximumQi = body.getStructure().getMeridiansList().stream()
                .mapToDouble(meridian -> meridian.getLumenVolume() + meridian.getWallVolume()).sum();
        maximumQi += body.getStructure().getOrgansList().stream().mapToDouble(OrganDefinition::getVolumeCapacity).sum();
        // 将尚未入脉的实际缓存和器官出口在途单独列出，方便区分捕获成功与配送等待。
        double bufferedQi = body.getOrganStatesList().stream()
                .filter(organ -> breath.getOrganIdsList().contains(organ.getId()))
                .flatMap(organ -> organ.getBuffer().getEntriesList().stream()).mapToDouble(Qi::getAmount).sum();
        var outputPorts = body.getStructure().getOrgansList().stream()
                .filter(organ -> breath.getOrganIdsList().contains(organ.getId()))
                .flatMap(organ -> organ.getPortsList().stream()).map(Port::getId).toList();
        var outputRoutes = body.getStructure().getConnectionsList().stream()
                .filter(route -> outputPorts.contains(route.getFrom())).map(Connection::getId).toList();
        double inTransitQi = body.getTransitsList().stream()
                .filter(transit -> outputRoutes.contains(transit.getConnectionId()))
                .flatMap(transit -> transit.getCargo().getEntriesList().stream()).mapToDouble(Qi::getAmount).sum();
        return new ArrayList<>(List.of(
                line("absorbed", "本次连接摄入灵气 %s 元", amount(breath.getAbsorbedQi())),
                line("stored", "炼化后累计入脉 %s 元", amount(breath.getStoredQi())),
                line("buffer", "呼吸器官缓存 %s 元 · 运往经脉 %s 元",
                        amount(bufferedQi), amount(inTransitQi)),
                line("lumen", "当前经脉内腔 %s 元", amount(QiDisplay.lumen(body))),
                line("total", "身体总真元量 %s 元", amount(totalQi)),
                line("maximum", "最大真元量 %s 元", amount(maximumQi))));
    }

    private static String amount(double value) {
        // 先固定显示精度，再交给语言模板排版。
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double ratio) {
        // 两项余力用同一百分比精度，便于比较。
        return String.format(Locale.ROOT, "%.0f%%", ratio * PERCENT);
    }

    private static Component line(String key, String original, String... readings) {
        // 在客户端语言确定后填入已经格式化的读数。
        return Component.literal(WindowText.get("breath.tip." + key, original, (Object[]) readings));
    }

    /** 这会儿该告诉玩家的受阻原因：请求被拒的那句优先，其次是呼吸视图报的；都没有是空串。 */
    static String blocked(BreathingView breath, String error) {
        if (error.isEmpty() && breath.getFailureValue() != 0) {
            return UiErrors.gatheringFailure(breath.getFailure());
        }
        return error;
    }
}
