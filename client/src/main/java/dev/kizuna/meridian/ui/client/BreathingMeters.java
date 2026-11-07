package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import dev.kizuna.inventoryui.theme.ThemeTokens;

/** 场景内的资源控件；每个控件独立命中，空白处仍交给三维相机与气息操作。 */
final class BreathingMeters {
    private static final Identifier LUNGS = Identifier.fromNamespaceAndPath("meridian_ui", "textures/gui/lungs.png");
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("meridian_ui", "textures/gui/lungs_air.png");
    private static final int AIR_TOP = 20;
    private static final int AIR_BOTTOM = 54;
    private static final int LUNGS_SIZE = 64;
    private static final int AIR_CAPTION_TOP = 73;
    private static final int QI_CAPTION_TOP = 8;
    private static final int PERCENT = 100;
    private static final int RESERVE_TOP = 16;
    private static final int RESERVE_HEIGHT = 4;
    private static final int STOP_OVERHANG = 3;
    private static final int FOOTER_CLEARANCE = 28;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double MAX_FRAME_SECONDS = 0.1;
    private static final double AIR_SMOOTHING_RATE = 14;
    /** 空气填充的染色：浅蓝、85% 不透明，与肺部轮廓贴图叠加。 */
    private static final int AIR_TINT = ARGB.colorFromFloat(.85f, .47f, .77f, .93f);
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
        air.margins(Insets.of(0, FOOTER_CLEARANCE, 12, 0));
        qi.positioning(Positioning.relative(100, 100));
        qi.margins(Insets.of(0, FOOTER_CLEARANCE, 0, 12));

        // 按钮位于底部操作提示上方；直接挂载各控件，保留场景空白处的鼠标输入。
        // 各控件晚于画布加入，按子组件顺序绘制在画布之上（owo 0.13 已无 zIndex）。
        action.sizing(Sizing.fixed(120), Sizing.fixed(30));
        action.positioning(Positioning.relative(50, 100));
        action.margins(Insets.bottom(34));
        for (UIComponent control : List.of(reserve, air, action, qi)) {
            scene.child(control);
        }
    }

    void arrange(int width, int height) {
        // 紧凑场景把纳入量抬到右下按钮上方，避免肺图、按钮和数字争用同一条底边。
        boolean compact = width < COMPACT_WIDTH;
        air.sizing(Sizing.fixed(compact ? 72 : 80), Sizing.fixed(84));
        air.margins(Insets.of(0, FOOTER_CLEARANCE, compact ? 6 : 12, 0));
        qi.sizing(Sizing.fixed(compact ? 128 : 136), Sizing.fixed(26));
        qi.margins(Insets.of(0, compact ? 88 : FOOTER_CLEARANCE, 0, compact ? 6 : 12));
        action.sizing(Sizing.fixed(compact ? 104 : 120), Sizing.fixed(compact ? 26 : 30));
        reserve.horizontalSizing(Sizing.fill(compact ? 78 : 64));
    }

    void update(GatheringView view, String error) {
        // 快照到达时刷新提示与总账，逐帧仅绘制空气插值，不重复汇总身体明细。
        if (view == null || !view.hasBreathing()) {
            breath = BreathingView.getDefaultInstance();
            action.tooltip(net.minecraft.network.chat.Component.literal(error.isEmpty() ? UiErrors.BREATHING_UNAVAILABLE : error));
            air.tooltip(List.<net.minecraft.network.chat.Component>of());
            reserve.tooltip(List.<net.minecraft.network.chat.Component>of());
            qi.tooltip(List.<net.minecraft.network.chat.Component>of());
            return;
        }
        breath = view.getBreathing();
        air.tooltip(List.of(net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "空气 %.1f / %.1f L",
                breath.getAirLiters(), breath.getCapacityLiters()))));
        double remainingRatio = breath.getAutomatic() ? breath.getStaminaRatio()
                : Math.min(breath.getStaminaRatio(), breath.getAttentionRatio());
        reserve.tooltip(List.of(
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "维持 %.0f%%", remainingRatio * 100)),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "横膈肌耐力 %.0f%%", breath.getStaminaRatio() * 100)),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "精力 %.0f%%", breath.getAttentionRatio() * 100))));

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
        var qiDetails = new java.util.ArrayList<net.minecraft.network.chat.Component>(List.of(
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "本次连接摄入灵气 %.2f 元", breath.getAbsorbedQi())),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "炼化后累计入脉 %.2f 元", breath.getStoredQi())),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "呼吸器官缓存 %.2f 元 · 运往经脉 %.2f 元", bufferedQi, inTransitQi)),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "当前经脉内腔 %.2f 元", QiDisplay.lumen(body))),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "身体总真元量 %.2f 元", totalQi)),
                net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "最大真元量 %.2f 元", maximumQi))));

        String visibleError = error;
        if (visibleError.isEmpty() && breath.getFailureValue() != 0) {
            visibleError = UiErrors.gatheringFailure(breath.getFailure());
        }
        String feedback = breath.getStatus();
        if (!visibleError.isEmpty()) {
            feedback = visibleError;
            qiDetails.add(net.minecraft.network.chat.Component.literal(visibleError));
        }
        qi.tooltip(qiDetails);
        // 详细状态随按钮悬浮显示，底部始终只保留操作按钮和相机提示。
        action.tooltip(net.minecraft.network.chat.Component.literal(feedback));
    }

    private final class AirGauge extends BaseUIComponent {
        private double shownRatio;
        private long frameNanos;

        AirGauge() {
            // 像素图使用整倍数绘制，轮廓下方只保留简短标签。
            sizing(Sizing.fixed(80), Sizing.fixed(84));
            id("breathing-air");
        }

        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 内部掩码限制蓝色填充，保持边框和外部透明；插值只改变展示高度。
            long now = System.nanoTime();
            double seconds = 0;
            if (frameNanos != 0) {
                seconds = Math.min(MAX_FRAME_SECONDS, (now - frameNanos) / NANOS_PER_SECOND);
            }
            frameNanos = now;
            double capacity = breath.getCapacityLiters();
            double ratio = 0;
            if (capacity > 0) {
                ratio = breath.getAirLiters() / capacity;
            }
            shownRatio += (ratio - shownRatio) * (1 - Math.exp(-seconds * AIR_SMOOTHING_RATE));
            int left = x + (width - LUNGS_SIZE) / 2;
            // 填充从肺底升到肺顶，透明外边距不计入空气量的显示高度。
            int fillHeight = (int) Math.round((AIR_BOTTOM - AIR_TOP) * Math.max(0, Math.min(1, shownRatio)));
            int fillTop = AIR_BOTTOM - fillHeight;
            context.blit(RenderPipelines.GUI_TEXTURED, LUNGS, left, y, 0, 0,
                    LUNGS_SIZE, LUNGS_SIZE, LUNGS_SIZE, LUNGS_SIZE);
            if (fillHeight > 0) {
                // 染色写进顶点颜色，只作用于这一次填充。
                context.blit(RenderPipelines.GUI_TEXTURED, AIR, left, y + fillTop, 0, fillTop,
                        LUNGS_SIZE, fillHeight, LUNGS_SIZE, LUNGS_SIZE, AIR_TINT);
            }
            var font = Minecraft.getInstance().font;
            String caption = String.format(Locale.ROOT, "空气 %.0f%%", ratio * PERCENT);
            context.text(font, caption, x + (width - font.width(caption)) / 2, y + AIR_CAPTION_TOP,
                    MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
        }
    }

    private final class ReserveGauge extends BaseUIComponent {
        ReserveGauge() {
            // 余力条使用当前主题，分别消耗的来源放在悬浮提示。
            sizing(Sizing.fill(64), Sizing.fixed(24));
            id("breathing-reserve");
        }

        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 手动取先耗尽的一项；自动用不同标记区分暂停线和恢复线。
            double remainingRatio;
            String label;
            boolean automatic = breath.getAutomatic();
            if (automatic) {
                remainingRatio = breath.getStaminaRatio();
                label = String.format(Locale.ROOT, "呼吸耐力 %.0f%%（不耗精力）", remainingRatio * PERCENT);
            } else {
                remainingRatio = Math.min(breath.getStaminaRatio(), breath.getAttentionRatio());
                label = "余力";
            }
            var font = Minecraft.getInstance().font;
            context.text(font, font.plainSubstrByWidth(label, width), x, y,
                    MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
            context.fill(x, y + RESERVE_TOP, x + width, y + RESERVE_TOP + RESERVE_HEIGHT,
                    MeridianTheme.color(ThemeTokens.PROGRESS_TRACK));
            int fillWidth = (int) Math.round(width * Math.max(0, Math.min(1, remainingRatio)));
            context.fill(x, y + RESERVE_TOP, x + fillWidth, y + RESERVE_TOP + RESERVE_HEIGHT,
                    MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
            if (automatic) {
                int stopX = x + (int) Math.round(width * breath.getAutomaticMinimumStaminaRatio());
                context.fill(stopX, y + RESERVE_TOP - STOP_OVERHANG,
                        stopX + 1, y + RESERVE_TOP + RESERVE_HEIGHT + STOP_OVERHANG,
                        MeridianTheme.color(ThemeTokens.INPUT_ERROR));
                int resumeX = x + (int) Math.round(width * breath.getAutomaticResumeStaminaRatio());
                context.fill(resumeX, y + RESERVE_TOP - STOP_OVERHANG,
                        resumeX + 1, y + RESERVE_TOP + RESERVE_HEIGHT + STOP_OVERHANG,
                        MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
            }
        }
    }

    private final class QiSummary extends BaseUIComponent {
        QiSummary() {
            // 整个统计区域都能命中总账提示。
            sizing(Sizing.fixed(136), Sizing.fixed(26));
            id("breathing-qi");
        }

        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 纳入量保留两位，能看出每次小批吐纳的变化。
            var font = Minecraft.getInstance().font;
            String caption = String.format(Locale.ROOT, "纳入 %.2f 真元", breath.getStoredQi());
            context.text(font, font.plainSubstrByWidth(caption, width), x, y + QI_CAPTION_TOP,
                    MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
        }
    }
}
