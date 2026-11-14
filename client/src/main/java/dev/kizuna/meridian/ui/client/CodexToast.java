package dev.kizuna.meridian.ui.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** 学会一卷时右上角的一张原版样式小卡：卷轴图标、「功法谱 · 新添一卷」和这本书的新进度；凑齐一本由进度小卡报。 */
final class CodexToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/advancement");
    private static final long DISPLAY_MILLIS = 5000;
    private static final int ICON_X = 8;
    private static final int TEXT_X = 30;
    private static final int TITLE_Y = 7;
    private static final int LINE_Y = 18;
    private static final int TITLE_COLOR = 0xFFFFFF00;
    private static final int LINE_COLOR = 0xFFFFFFFF;
    private final ItemStack icon;
    private final String title;
    private final String line;
    private Visibility visibility = Visibility.HIDE;

    CodexToast(ItemStack icon, String title, String line) {
        // 文案在学会时一次算好，显示期间不再读已学列表。
        this.icon = icon;
        this.title = title;
        this.line = line;
    }

    @Override
    public Visibility getWantedVisibility() {
        // 由原版通知管理器按显示时长收起。
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleMillis) {
        // 显示时长跟随玩家在辅助功能里设置的通知时长倍数。
        double limit = DISPLAY_MILLIS * manager.getNotificationDisplayTimeMultiplier();
        visibility = visibleMillis >= limit ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleMillis) {
        // 背景沿用原版进度卡，标题用原版进度的黄色。
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
        graphics.fakeItem(icon, ICON_X, ICON_X);
        graphics.text(font, title, TEXT_X, TITLE_Y, TITLE_COLOR, false /* shadow */);
        graphics.text(font, line, TEXT_X, LINE_Y, LINE_COLOR, false /* shadow */);
    }
}
