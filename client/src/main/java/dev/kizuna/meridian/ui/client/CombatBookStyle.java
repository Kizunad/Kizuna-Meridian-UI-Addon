package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 书页使用现有浅色主题令牌，图标路径与 Combat HUD 共用解析规则。 */
final class CombatBookStyle {
    static final int MARGIN = 12;
    static final int GAP = 4;
    static final int TITLE_HEIGHT = 22;
    static final int FOOTER_HEIGHT = 88;
    static final int TILE_WIDTH = 62;
    static final int TILE_HEIGHT = 48;
    static final int ICON_SIZE = 32;
    static final int SLOT_HEIGHT = 42;
    static final int LINE_HEIGHT = 14;
    static final int FULL = 100;
    private static final Identifier BOOK = Identifier.fromNamespaceAndPath("meridian_ui",
            "textures/gui/combat_book.png");
    private static final int BOOK_WIDTH = 1536;
    private static final int BOOK_HEIGHT = 1024;
    private static final int INK = paper(ThemeTokens.TEXT);

    private CombatBookStyle() {
        // 书本绘制不建立新的主题注册表或资源缓存。
    }

    static int paper(String token) {
        // 纸面和墨色取同一调色板，切换工作台深色主题也保持文字对比度。
        return BuiltinThemes.color(BuiltinThemes.LIGHT, token);
    }

    static Component name(KnownTechnique entry) {
        // 资源包翻译优先，缺少翻译时使用目录提供的独立名称。
        String fallback = entry.getName();
        if (fallback.isBlank()) {
            fallback = "未命名招式";
        }
        String key = "technique." + entry.getId().replace(':', '.');
        return Component.translatableWithFallback(key, fallback);
    }

    static void book(OwoUIGraphics context, Bounds bounds) {
        // 背景只含空白书页和书脊，文字与交互内容始终由组件绘制。
        context.blit(RenderPipelines.GUI_TEXTURED, BOOK, bounds.x(), bounds.y(), 0, 0,
                bounds.width(), bounds.height(), BOOK_WIDTH, BOOK_HEIGHT, BOOK_WIDTH, BOOK_HEIGHT);
    }

    static void icon(OwoUIGraphics context, KnownTechnique entry, Bounds bounds) {
        // 不复制 HUD 的状态渲染；真实招式资源与 HUD 使用同一个安全路径解析器。
        var client = Minecraft.getInstance();
        String texture = CombatHud.texture(entry.getIconId());
        if (!texture.isEmpty()) {
            var id = Identifier.parse(texture);
            if (client.getResourceManager().getResource(id).isPresent()) {
                context.blit(RenderPipelines.GUI_TEXTURED, id, bounds.x(), bounds.y(), 0, 0,
                        bounds.width(), bounds.height(), ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                return;
            }
        }
        context.centeredText(client.font, "?", bounds.x() + bounds.width() / 2, bounds.y() + GAP, INK);
    }

    record Bounds(int x, int y, int width, int height) { }
}
