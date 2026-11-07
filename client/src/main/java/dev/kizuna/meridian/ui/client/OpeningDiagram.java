package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.MeridianOperationMeter;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.MeridianOperationView;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.OperationMeterState;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 经脉图像与适应光环；动画只连接已经收到的权威进度。 */
final class OpeningDiagram extends BaseUIComponent {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath(
            "meridian_ui", "textures/gui/actions/opening.png");
    private static final int FULL = 100;
    private static final int HEIGHT = 144;
    private static final int CENTER_TOP = 58;
    private static final int IMAGE_SIZE = 76;
    private static final int TEXTURE_SIZE = 64;
    private static final int RADIUS = 48;
    private static final int SEGMENTS = 72;
    private static final int DOT_RADIUS = 2;
    private static final int CAPTION_TOP = 117;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double BLEND_SECONDS = 0.2;
    private static final double ORBIT_SECONDS = 3;
    private MeridianOperationMeter primary = MeridianOperationMeter.getDefaultInstance();
    private double previous;
    private long receivedNanos;

    OpeningDiagram() {
        // 主图有独立悬浮提示，连续适应的详细时长不挤占模型区域。
        sizing(Sizing.fill(FULL), Sizing.fixed(HEIGHT));
        id("opening-visual");
    }

    void update(MeridianOperationView view) {
        // 主进度由发布者声明，通用界面不根据人体字段或文字猜测含义。
        MeridianOperationMeter next = view.getMetersList().stream()
                .filter(meter -> meter.getId().equals(view.getPrimaryMeterId()))
                .findFirst().orElse(MeridianOperationMeter.getDefaultInstance());
        long now = System.nanoTime();
        previous = shown(now);
        primary = next;
        receivedNanos = now;
        tooltip(Component.literal(next.getHint()));
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 经脉图形保持稳定，金色环点只表示服务端确认的连续适应进度。
        int centerX = x + width / 2;
        int centerY = y + CENTER_TOP;
        long now = System.nanoTime();
        context.blit(RenderPipelines.GUI_TEXTURED, ICON, centerX - IMAGE_SIZE / 2, centerY - IMAGE_SIZE / 2,
                0, 0, IMAGE_SIZE, IMAGE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        drawProgress(context, new Center(centerX, centerY), shown(now));
        if (primary.getState() == OperationMeterState.METER_RUNNING && !EffectSettings.reducedMotion()) {
            double angle = now / NANOS_PER_SECOND / ORBIT_SECONDS * Math.TAU;
            dot(context, new Center(centerX, centerY), angle, MeridianTheme.color(ThemeTokens.ACCENT));
        }
        String caption = "经脉状态";
        if (!primary.getId().isEmpty()) {
            caption = String.format(Locale.ROOT, "%s  %.0f%%", primary.getLabel(), primary.getFraction() * FULL);
        }
        var font = Minecraft.getInstance().font;
        context.text(font, caption, centerX - font.width(caption) / 2, y + CAPTION_TOP,
                MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
    }

    private void drawProgress(OwoUIGraphics context, Center center, double fraction) {
        // 灰色轨迹表示尚未完成，暂停和清零立刻停止推进，不本地预测剩余时间。
        Color track = Color.ofArgb(MeridianTheme.color(ThemeTokens.PROGRESS_TRACK));
        context.drawRing(center.x(), center.y(), SEGMENTS, RADIUS - 1, RADIUS + 1, track, track);
        int completed = (int) Math.floor(fraction * SEGMENTS);
        for (int segment = 0; segment < completed; segment++) {
            double angle = segment * Math.TAU / SEGMENTS - Math.PI / 2;
            dot(context, center, angle, MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
        }
    }

    private void dot(OwoUIGraphics context, Center center, double angle, int color) {
        // 环上的亮点与轨迹共用半径，窗口移动和缩放不会让光点漂离主图。
        int left = center.x() + (int) Math.round(Math.cos(angle) * RADIUS);
        int top = center.y() + (int) Math.round(Math.sin(angle) * RADIUS);
        context.fill(left - DOT_RADIUS, top - DOT_RADIUS, left + DOT_RADIUS, top + DOT_RADIUS, color);
    }

    private double shown(long now) {
        // 减少动态直接显示权威值；进度清零立即响应，不能残留完成光环。
        double target = primary.getFraction();
        if (EffectSettings.reducedMotion() || target < previous) {
            return target;
        }
        double elapsed = (now - receivedNanos) / NANOS_PER_SECOND;
        double blend = Math.clamp(elapsed / BLEND_SECONDS, 0, 1);
        return previous + (target - previous) * blend;
    }

    private record Center(int x, int y) { }
}
