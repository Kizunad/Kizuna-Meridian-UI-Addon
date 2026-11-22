package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactView;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

import static com.google.common.base.Preconditions.checkState;

/** 用游戏里的真字体重放游戏画面上每个小窗的摘要：打出每行文字，并核对没有画出小窗、同一行没有相叠。 */
final class CompactReadings {
    private CompactReadings() {
    }

    static void record(Minecraft c, BodyUiModule module, ClientRuntime runtime) {
        // 整段同步执行，取样期间不让网络回调替换身体或吐纳消息。
        var body = module.snapshot();
        var extension = MeridianSceneExtension.required();
        for (var section : InspectionSection.values()) {
            line("展开", section.name(), extension.inspectionRows(section, body));
        }
        line("展开", "概况", List.of(extension.overviewLine(body)));
        ExpandedReadings.log(module);
        var view = c.getWindow();
        var providers = providers(module, extension);
        for (var compact : runtime.hudCompacts(view.getGuiScaledWidth(), view.getGuiScaledHeight())) {
            replay(c, compact, providers.get(compact.feature()));
        }
    }

    private static void replay(Minecraft c, CompactView compact, CompactSummary summary) {
        // 每个样式都量一遍；正在显示的那个样式还要和屏幕上的矩形一样大，也就是没有缩放。
        for (var variant : summary.variants()) {
            var probe = new SummaryProbe(c.font::width);
            summary.paint(probe, variant.id());
            line("小窗", variant.id() + " " + variant.width() + "x" + variant.height(), probe.lines());
            var problems = probe.problems(variant);
            checkState(problems.isEmpty(), "小窗 %s 的文字不合规 %s", variant.id(), problems);
            if (variant.id().equals(compact.variant())) {
                var bounds = compact.bounds();
                checkState(bounds.width() == variant.width() && bounds.height() == variant.height(),
                        "小窗 %s 被缩放了 %s", variant.id(), bounds);
            }
        }
    }

    private static Map<String, CompactSummary> providers(BodyUiModule module, MeridianSceneExtension extension) {
        // 摘要只读模块当前持有的快照，和登记给框架的那一份由同一组工厂构造。
        return Map.of(
                BodyFeature.SURFACE.id, summary(extension.compactBody(InspectionSection.SURFACE, module::snapshot)),
                BodyFeature.MUSCLE.id, summary(extension.compactBody(InspectionSection.MUSCLES, module::snapshot)),
                BodyFeature.ORGAN.id, summary(extension.compactBody(InspectionSection.ORGANS, module::snapshot)),
                BodyFeature.MERIDIAN.id, summary(MeridianSummaries.channels(module)),
                BodyFeature.OVERVIEW.id, summary(MeridianSummaries.overview(module, extension)),
                BodyFeature.BREATH.id, summary(MeridianSummaries.breath(module)),
                BodyFeature.PRACTICE.id, summary(extension.compactPractice(module::snapshot)));
    }

    private static CompactSummary summary(CompactProvider provider) {
        return (CompactSummary) provider;
    }

    private static void line(String source, String group, List<?> values) {
        // 分隔符只用于验收输出。
        System.out.println("S5_DATA " + source + "\t" + group + "\t"
                + String.join("|", values.stream().map(Object::toString).toList()));
    }
}
