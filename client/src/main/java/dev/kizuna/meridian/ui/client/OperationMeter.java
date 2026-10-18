package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.OperationUiMessages.MeridianOperationMeter;
import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** 紧凑状态条：实体填充是到账进度，细淡线是入向预约；不逐帧计算身体状态。 */
final class OperationMeter extends BaseComponent {
    private MeridianOperationMeter meter;
    private double shown;
    private long frame;

    OperationMeter(MeridianOperationMeter meter) {
        // 控件身份来自附属的稳定 meter ID，快照刷新无需重建整棵树。
        sizing(Sizing.fill(100), Sizing.fixed(30));
        id("meter-" + meter.getId());
        update(meter);
        shown = meter.getFraction();
    }

    void update(MeridianOperationMeter value) {
        // 提示解释操作原因，不把整页重新变成数值清单。
        meter = value;
        tooltip(Text.literal(value.getHint()));
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 平滑填充仅用于展示；状态颜色立即跟随权威条件，临界满格仍能显示等待。
        long now = System.nanoTime();
        double seconds = frame == 0 ? 0 : Math.min(.1, (now - frame) / 1e9);
        frame = now;
        shown += (meter.getFraction() - shown) * -Math.expm1(-12 * seconds);
        int color = switch (meter.getState()) {
            case METER_READY -> 0xFF96C7AF;
            case METER_RUNNING -> 0xFFE6CA79;
            case METER_BLOCKED -> 0xFFCF8E83;
            default -> 0xFFB4A47A;
        };
        var font = MinecraftClient.getInstance().textRenderer;
        context.drawText(font, font.trimToWidth(meter.getLabel(), Math.max(1, width - 12)), x, y + 1, 0xFFCBD6D1, false);
        context.fill(x + width - 5, y + 3, x + width - 1, y + 7, color);
        context.fill(x, y + 17, x + width, y + 22, 0xFF26343A);
        int fill = (int) Math.round(width * shown);
        int incoming = (int) Math.round(width * Math.min(1, shown + meter.getIncomingFraction()));
        context.fill(x, y + 17, x + fill, y + 22, color);
        for (int offset = fill; offset < incoming; offset += 4) {
            context.fill(x + offset, y + 18, x + Math.min(incoming, offset + 2), y + 21, 0xFF77969A);
        }
    }
}
