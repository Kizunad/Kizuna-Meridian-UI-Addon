package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static dev.kizuna.meridian.ui.client.CombatBookStyle.paper;

/** 图鉴里的一格：已学是实色卷轴，未得是剪影加问号，够不着的剪影角上标境界；新学的一格播一次点亮。 */
final class CodexCell extends BaseUIComponent {
    static final int SIZE = 22;
    private static final int ITEM = 16;
    private static final int VEIL = 0xD8;
    private static final int VEIL_FADE = 4;
    private static final double PULSE_MILLIS = 300.0;
    private final CodexCatalog.Volume volume;
    private final CodexProgress.State state;
    private final Runnable onClick;

    CodexCell(CodexCatalog.Volume volume, CodexProgress.State state, String tooltip, Runnable onClick) {
        // 状态在面板刷新时算好；点击只翻页，不触碰学习或配置。
        this.volume = volume;
        this.state = state;
        this.onClick = onClick;
        sizing(Sizing.fixed(SIZE), Sizing.fixed(SIZE));
        tooltip(Component.literal(tooltip));
        if (onClick != null) {
            cursorStyle(CursorStyle.HAND);
        }
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 先画卷轴图标，再按状态盖一层纸色：未得盖得最厚，新点亮的这层在一秒内褪掉。
        float glow = state == CodexProgress.State.LEARNED ? CodexMoments.glow(volume.id()) : 0;
        context.fill(x, y, x + width, y + height, paper(ThemeTokens.PANEL_BOTTOM));
        int inset = (SIZE - ITEM) / 2;
        context.item(volume.icon(), x + inset, y + inset);
        int veil = veil(glow);
        if (veil > 0) {
            context.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                    (veil << 24) | (paper(ThemeTokens.PANEL_BOTTOM) & 0xFFFFFF));
        }
        drawMarks(context);
        context.drawRectOutline(x, y, width, height, border(glow));
    }

    private int veil(float glow) {
        // 剪影盖层；新学的格子在动画头四分之一由剪影褪成实色。
        if (state != CodexProgress.State.LEARNED) {
            return VEIL;
        }
        float fade = Math.max(0, glow * VEIL_FADE - (VEIL_FADE - 1));
        return Math.round(VEIL * fade);
    }

    private void drawMarks(OwoUIGraphics context) {
        // 问号表示还没到手；境界字表示目前够不着；「新」表示刚添上、还没看过。
        var font = Minecraft.getInstance().font;
        int textY = y + (height - font.lineHeight) / 2 + 1;
        if (state == CodexProgress.State.MISSING) {
            context.text(font, "?", x + (width - font.width("?")) / 2, textY, paper(ThemeTokens.MUTED_TEXT), false);
        } else if (state == CodexProgress.State.OUT_OF_REACH) {
            String realm = CodexSources.realmName(volume.minimumRealm()).substring(0, 1);
            context.text(font, realm, x + (width - font.width(realm)) / 2, textY,
                    paper(ThemeTokens.INPUT_ERROR), false);
        } else if (CodexMoments.fresh(volume.id())) {
            context.fill(x + width - 9, y - 3, x + width + 3, y + 7, paper(ThemeTokens.ACCENT));
            context.text(font, "新", x + width - 8, y - 2, 0xFFFFFFFF, false);
        }
    }

    private int border(float glow) {
        // 点亮动画期间边框随时间明暗起伏；平时悬停高亮。
        if (glow > 0) {
            double wave = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / PULSE_MILLIS);
            return wave > 0.35 ? paper(ThemeTokens.ACCENT) : paper(ThemeTokens.PROGRESS_FILL);
        }
        if (hovered && onClick != null) {
            return paper(ThemeTokens.ACCENT);
        }
        return paper(ThemeTokens.BORDER);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent event, boolean doubleClick) {
        // 左键翻到这一卷所在的书。
        if (onClick != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            onClick.run();
            return true;
        }
        return super.onMouseDown(event, doubleClick);
    }
}
