package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** 未解锁窗口的统一提示卡；只展示客户端投影给出的原因，实际权限仍由服务端裁定。 */
final class LockedNotice extends BaseComponent {
    private static final int BACKGROUND = 0xFF0B1115;
    private static final int SHACKLE = 0xFFB9C3C0;
    private static final int LOCK_BODY = 0xFFE0B55A;
    private static final int KEYHOLE = 0xFF3A2A12;
    private static final int HEADING = 0xFFFFD27A;
    private static final int BODY_TEXT = 0xFFE6EAE7;
    private static final float HEADING_SCALE = 1.5f;
    private static final int LINE_HEIGHT = 12;
    private final String title;
    private final String reason;

    LockedNotice(String title, String reason) {
        // 标题和原因由调用方提供，卡片本身不判断任何解锁条件。
        this.title = title;
        this.reason = reason;
        sizing(Sizing.fill(100), Sizing.fill(100));
        id("locked-notice");
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 锁形图标、标题、找卷说明自上而下居中，窗口缩放后仍完整可读。
        var font = MinecraftClient.getInstance().textRenderer;
        context.fill(x, y, x + width, y + height, BACKGROUND);
        int centerX = x + width / 2;
        int top = y + Math.max(8, height / 2 - 64);
        lock(context, centerX, top);

        String heading = title + "　未解锁";
        float headingLeft = centerX - font.getWidth(heading) * HEADING_SCALE / 2;
        context.drawText(Text.literal(heading), headingLeft, top + 42, HEADING_SCALE, HEADING);

        // 原因按窗口宽度折行，窄窗口也不会截掉要找的残卷名称。
        var lines = font.wrapLines(Text.literal(reason), Math.max(40, width - 48));
        for (int line = 0; line < lines.size(); line++) {
            var wrapped = lines.get(line);
            context.drawText(font, wrapped, centerX - font.getWidth(wrapped) / 2, top + 66 + line * LINE_HEIGHT,
                    BODY_TEXT, false);
        }
    }

    private static void lock(OwoUIDrawContext context, int centerX, int top) {
        // 用像素块画一把锁，不依赖额外贴图，也不会被资源包替换成别的含义。
        context.fill(centerX - 8, top, centerX + 8, top + 3, SHACKLE);
        context.fill(centerX - 8, top, centerX - 5, top + 14, SHACKLE);
        context.fill(centerX + 5, top, centerX + 8, top + 14, SHACKLE);
        context.fill(centerX - 13, top + 12, centerX + 13, top + 32, LOCK_BODY);
        context.fill(centerX - 2, top + 18, centerX + 2, top + 26, KEYHOLE);
    }
}
