package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.OperationUiMessages.MeridianOperationMeter;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Color;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.kizuna.inventoryui.theme.ThemeTokens;

/** 条件印记；状态常显，精确比例和在途信息放在悬浮说明中。 */
final class OperationMeter extends BaseUIComponent {
    private static final int FULL = 100;
    private static final int HEIGHT = 28;
    private static final int MARK_LEFT = 10;
    private static final int MARK_RADIUS = 4;
    private static final int MARK_SEGMENTS = 16;
    private static final int LABEL_LEFT = 22;
    private static final int TEXT_TOP = 9;
    private static final int STATUS_SPACE = 58;
    private static final int EDGE = 6;
    private MeridianOperationMeter meter;

    OperationMeter(MeridianOperationMeter meter) {
        // 稳定身份保留悬浮与焦点，不把每项准备条件都画成进度条。
        sizing(Sizing.fill(FULL), Sizing.fixed(HEIGHT));
        id("meter-" + meter.getId());
        update(meter);
    }

    void update(MeridianOperationMeter value) {
        // 精确进度可查；预约在途单独标注，不算作已到账。
        meter = value;
        String detail = String.format(Locale.ROOT, "%s\n%.0f%%", value.getHint(), value.getFraction() * FULL);
        if (value.getIncomingFraction() > 0) {
            detail += String.format(Locale.ROOT, " · 在途 %.0f%%", value.getIncomingFraction() * FULL);
        }
        tooltip(Component.literal(detail));
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 状态以服务端枚举为准，接近满格不冒充条件已经满足。
        String token = switch (meter.getState()) {
            case METER_READY, METER_RUNNING -> ThemeTokens.PROGRESS_FILL;
            case METER_BLOCKED -> ThemeTokens.INPUT_ERROR;
            default -> ThemeTokens.MUTED_TEXT;
        };
        int color = MeridianTheme.color(token);
        String status = switch (meter.getState()) {
            case METER_READY -> "已满足";
            case METER_RUNNING -> "进行中";
            case METER_BLOCKED -> "受阻";
            default -> "待补足";
        };
        context.drawCircle(x + MARK_LEFT, y + HEIGHT / 2, MARK_SEGMENTS, MARK_RADIUS, Color.ofArgb(color));
        var font = Minecraft.getInstance().font;
        String caption = font.plainSubstrByWidth(meter.getLabel(), Math.max(1, width - LABEL_LEFT - STATUS_SPACE));
        context.text(font, caption, x + LABEL_LEFT, y + TEXT_TOP,
                MeridianTheme.color(ThemeTokens.TEXT), false /* shadow */);
        context.text(font, status, x + width - font.width(status) - EDGE, y + TEXT_TOP,
                color, false /* shadow */);
    }
}
