package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;

/** 同一客户端 tick 内记录展开页的数据行及实际摘要绘制参数。 */
final class CompactReadings extends GuiGraphicsExtractor {
    private final List<String> labels = new ArrayList<>();
    private final List<Integer> fills = new ArrayList<>();

    private CompactReadings(Minecraft client) {
        // 使用独立绘制状态，采样不能改变玩家当前帧。
        super(client, new GuiRenderState(), 0, 0);
    }

    static void record(Minecraft c, BodyUiModule module, ClientRuntime runtime) {
        // 整段同步执行，选样期间不让网络回调替换身体或吐纳消息。
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
            String type = runtime.catalog().features().get(compact.feature()).windowType();
            var state = runtime.workspace().windowBar().stream()
                    .filter(window -> window.key().windowType().equals(type))
                    .findFirst()
                    .orElseThrow();
            var provider = providers.get(compact.feature());
            for (var variant : provider.variants()) {
                var output = new CompactReadings(c);
                provider.draw(output, state, variant.id(), 0);
                line("小窗", variant.id(), output.labels);
                line("填色", variant.id(), output.fills);
            }
        }
    }

    private static Map<String, CompactProvider> providers(BodyUiModule module, MeridianSceneExtension extension) {
        // 摘要只读模块当前持有的快照，和登记给框架的那一份由同一组工厂构造。
        return Map.of(
                BodyFeature.SURFACE.id, extension.compactBody(InspectionSection.SURFACE, module::snapshot),
                BodyFeature.MUSCLE.id, extension.compactBody(InspectionSection.MUSCLES, module::snapshot),
                BodyFeature.ORGAN.id, extension.compactBody(InspectionSection.ORGANS, module::snapshot),
                BodyFeature.MERIDIAN.id, MeridianSummaries.channels(module),
                BodyFeature.OVERVIEW.id, MeridianSummaries.overview(module, extension),
                BodyFeature.BREATH.id, MeridianSummaries.breath(module),
                BodyFeature.PRACTICE.id, extension.compactPractice(module::snapshot));
    }

    private static void line(String source, String group, List<?> values) {
        // 分隔符只用于验收输出。
        System.out.println("S5_DATA " + source + "\t" + group + "\t"
                + String.join("|", values.stream().map(Object::toString).toList()));
    }

    @Override
    public void text(Font font, String value, int x, int y, int color, boolean shadow) {
        // 数字保留实际绘制精度。
        labels.add(value);
    }

    @Override
    public void text(Font font, Component value, int x, int y, int color, boolean shadow) {
        // 保留带伤情着色的三连数。
        labels.add(value.getString());
    }

    @Override
    public void fill(int left, int top, int right, int bottom, int color) {
        // 经脉格、器官加工及人形伤处都以主题色区分。
        fills.add(color);
    }
}
