package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.List;
import java.util.Locale;

/** 场景内的资源控件；每个控件独立命中，空白处仍交给三维相机与气息操作。 */
final class BreathingMeters {
    private static final Identifier LUNGS = new Identifier("meridian_ui", "textures/gui/lungs.png");
    private static final Identifier AIR = new Identifier("meridian_ui", "textures/gui/lungs_air.png");
    private static final int AIR_TOP = 20;
    private static final int AIR_BOTTOM = 54;
    static final int COMPACT_WIDTH = 420;
    private final AirGauge air = new AirGauge();
    private final ReserveGauge reserve = new ReserveGauge();
    private final QiSummary qi = new QiSummary();
    private final ActionRow action;
    private BreathingView breath = BreathingView.getDefaultInstance();

    BreathingMeters(FlowLayout scene, ActionRow action) {
        // 相对场景四边定位，窗口缩放时仍保持顶部余力、左下空气和右下纳入量。
        this.action = action;
        reserve.positioning(Positioning.relative(50, 0));
        reserve.margins(Insets.top(12));
        air.positioning(Positioning.relative(0, 100));
        air.margins(Insets.of(0, 12, 12, 0));
        qi.positioning(Positioning.relative(100, 100));
        qi.margins(Insets.of(0, 12, 0, 12));

        // 按钮位于底部操作提示上方；直接挂载各控件，保留场景空白处的鼠标输入。
        action.sizing(Sizing.fixed(120), Sizing.fixed(30));
        action.positioning(Positioning.relative(50, 100));
        action.margins(Insets.bottom(34));
        for (Component control : List.of(reserve, air, action, qi)) {
            control.zIndex(1);
            scene.child(control);
        }
    }

    void arrange(int width, int height) {
        // 紧凑场景把纳入量抬到右下按钮上方，避免肺图、按钮和数字争用同一条底边。
        boolean compact = width < COMPACT_WIDTH;
        air.sizing(Sizing.fixed(compact ? 72 : 80), Sizing.fixed(84));
        air.margins(Insets.of(0, 12, compact ? 6 : 12, 0));
        qi.sizing(Sizing.fixed(compact ? 128 : 136), Sizing.fixed(26));
        qi.margins(Insets.of(0, compact ? 88 : 12, 0, compact ? 6 : 12));
        action.sizing(Sizing.fixed(compact ? 104 : 120), Sizing.fixed(compact ? 26 : 30));
        reserve.horizontalSizing(Sizing.fill(compact ? 78 : 64));
    }

    void update(GatheringView view, String error) {
        // 快照到达时刷新提示与总账，逐帧仅绘制空气插值，不重复汇总身体明细。
        if (view == null || !view.hasBreathing()) {
            breath = BreathingView.getDefaultInstance();
            action.tooltip(Text.literal(error.isEmpty() ? UiErrors.BREATHING_UNAVAILABLE : error));
            air.tooltip(List.<Text>of());
            reserve.tooltip(List.<Text>of());
            qi.tooltip(List.<Text>of());
            return;
        }
        breath = view.getBreathing();
        air.tooltip(List.of(Text.literal(String.format(Locale.ROOT, "空气 %.1f / %.1f L",
                breath.getAirLiters(), breath.getCapacityLiters()))));
        double remainingRatio = breath.getAutomatic() ? breath.getStaminaRatio()
                : Math.min(breath.getStaminaRatio(), breath.getAttentionRatio());
        reserve.tooltip(List.of(
                Text.literal(String.format(Locale.ROOT, "维持 %.0f%%", remainingRatio * 100)),
                Text.literal(String.format(Locale.ROOT, "横膈肌耐力 %.0f%%", breath.getStaminaRatio() * 100)),
                Text.literal(String.format(Locale.ROOT, "精力 %.0f%%", breath.getAttentionRatio() * 100))));

        // 每个账户各计一次；在途占用目的容量，不能再增加最大值。
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
        var qiDetails = new java.util.ArrayList<Text>(List.of(
                Text.literal(String.format(Locale.ROOT, "本次连接摄入灵气 %.2f 元", breath.getAbsorbedQi())),
                Text.literal(String.format(Locale.ROOT, "炼化后累计入脉 %.2f 元", breath.getStoredQi())),
                Text.literal(String.format(Locale.ROOT, "呼吸器官缓存 %.2f 元 · 运往经脉 %.2f 元", bufferedQi, inTransitQi)),
                Text.literal(String.format(Locale.ROOT, "当前经脉内腔 %.2f 元", QiDisplay.lumen(body))),
                Text.literal(String.format(Locale.ROOT, "身体总真元量 %.2f 元", totalQi)),
                Text.literal(String.format(Locale.ROOT, "最大真元量 %.2f 元", maximumQi))));

        String visibleError = error;
        if (visibleError.isEmpty() && breath.getFailureValue() != 0) {
            visibleError = UiErrors.gatheringFailure(breath.getFailure());
        }
        String feedback = breath.getStatus();
        if (!visibleError.isEmpty()) {
            feedback = visibleError;
            qiDetails.add(Text.literal(visibleError));
        }
        qi.tooltip(qiDetails);
        // 详细状态随按钮悬浮显示，底部始终只保留操作按钮和相机提示。
        action.tooltip(Text.literal(feedback));
    }

    private final class AirGauge extends BaseComponent {
        private double shownRatio;
        private long frameNanos;

        AirGauge() {
            // 像素图使用整倍数绘制，轮廓下方只保留简短标签。
            sizing(Sizing.fixed(80), Sizing.fixed(84));
            id("breathing-air");
        }

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 内部掩码限制蓝色填充，保持边框和外部透明；插值只改变展示高度。
            long now = System.nanoTime();
            double seconds = frameNanos == 0 ? 0 : Math.min(.1, (now - frameNanos) / 1e9);
            frameNanos = now;
            double ratio = breath.getCapacityLiters() > 0 ? breath.getAirLiters() / breath.getCapacityLiters() : 0;
            shownRatio += (ratio - shownRatio) * (1 - Math.exp(-seconds * 14));
            int left = x + (width - 64) / 2;
            // 填充从肺底升到肺顶，透明外边距不计入空气量的显示高度。
            int fillHeight = (int) Math.round((AIR_BOTTOM - AIR_TOP) * Math.max(0, Math.min(1, shownRatio)));
            int fillTop = AIR_BOTTOM - fillHeight;
            context.drawTexture(LUNGS, left, y, 0, 0, 64, 64, 64, 64);
            context.draw();
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(.47f, .77f, .93f, .85f);
            if (fillHeight > 0) {
                context.drawTexture(AIR, left, y + fillTop, 0, fillTop, 64, fillHeight, 64, 64);
            }
            context.draw();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var font = MinecraftClient.getInstance().textRenderer;
            String caption = String.format(Locale.ROOT, "空气 %.0f%%", ratio * 100);
            context.drawText(font, caption, x + (width - font.getWidth(caption)) / 2, y + 73, 0xFF8DBBD3, false);
        }
    }

    private final class ReserveGauge extends BaseComponent {
        ReserveGauge() {
            // 黄色条保留原有含义，分别消耗的来源放在悬浮提示。
            sizing(Sizing.fill(64), Sizing.fixed(24));
            id("breathing-reserve");
        }

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 总余力取先耗尽的一项，避免另一项仍高时掩盖实际限制。
            double remainingRatio = breath.getAutomatic() ? breath.getStaminaRatio()
                    : Math.min(breath.getStaminaRatio(), breath.getAttentionRatio());
            var font = MinecraftClient.getInstance().textRenderer;
            // 自动吐纳只看呼吸耐力，标题直接写明，并画出服务端停止线。
            String label = breath.getAutomatic()
                    ? String.format(Locale.ROOT, "呼吸耐力 %.0f%%（不耗精力）", breath.getStaminaRatio() * 100) : "余力";
            context.drawText(font, font.trimToWidth(label, width), x, y, 0xFFD5B878, false);
            context.fill(x, y + 16, x + width, y + 20, 0xFF272A26);
            int fillWidth = (int) Math.round(width * Math.max(0, Math.min(1, remainingRatio)));
            context.fill(x, y + 16, x + fillWidth, y + 20, 0xFFD5B878);
            if (breath.getAutomatic()) {
                int stopX = x + (int) Math.round(width * breath.getAutomaticMinimumStaminaRatio());
                context.fill(stopX, y + 13, stopX + 1, y + 23, 0xFFFF5858);
                String stop = String.format(Locale.ROOT, "低于%.0f%%自动停", breath.getAutomaticMinimumStaminaRatio() * 100);
                context.drawText(font, stop, Math.min(x + width - font.getWidth(stop), stopX - font.getWidth(stop) / 2),
                        y, 0xFFFF8A80, false);
            }
        }
    }

    private final class QiSummary extends BaseComponent {
        QiSummary() {
            // 整个统计区域都能命中总账提示。
            sizing(Sizing.fixed(136), Sizing.fixed(26));
            id("breathing-qi");
        }

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 纳入量保留两位，能看出每次小批吐纳的变化。
            var font = MinecraftClient.getInstance().textRenderer;
            String caption = String.format(Locale.ROOT, "纳入 %.2f 真元", breath.getStoredQi());
            context.drawText(font, font.trimToWidth(caption, width), x, y + 8, 0xFFA8C8C0, false);
        }
    }
}
