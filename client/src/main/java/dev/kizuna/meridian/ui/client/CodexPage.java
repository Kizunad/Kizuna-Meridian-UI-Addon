package dev.kizuna.meridian.ui.client;

import static dev.kizuna.meridian.ui.client.WindowText.get;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog.NamedTechnique;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.TreeMap;
import static dev.kizuna.meridian.ui.client.CombatBookStyle.*;

/** 右页一本书：已学的卷写全文，未得的卷剪影加来源，够不着的卷写境界要求和一句威力。 */
record CodexPage(FlowLayout page, CodexProgress progress, CodexCatalog catalog, int width,
                 java.util.Set<String> expanded, java.util.function.Consumer<String> toggle) {
    void fill(CodexCatalog.Book book) {
        // 书有引言就放在最前；各卷按目录顺序往下排。
        String intro = "codex.intro." + book.id().replace(':', '.');
        if (Language.getInstance().has(intro)) {
            page.child(label(Language.getInstance().getOrDefault(intro), paper(ThemeTokens.MUTED_TEXT), 0));
        }
        for (var volume : book.volumes()) {
            page.child(block(volume));
        }
    }

    private FlowLayout block(CodexCatalog.Volume volume) {
        // 每卷一块：卷头一行，下面按状态写正文。
        var block = UIContainers.verticalFlow(Sizing.fill(FULL), Sizing.content()).gap(2);
        var state = progress.state(volume);
        block.child(header(volume, state));
        switch (state) {
            case LEARNED -> learned(block, volume);
            case MISSING -> missing(block, volume);
            case OUT_OF_REACH -> outOfReach(block, volume);
        }
        return block;
    }

    private FlowLayout header(CodexCatalog.Volume volume, CodexProgress.State state) {
        // 卷头：图鉴格、卷名、状态；状态是界面文字，境界照实写。
        var row = UIContainers.horizontalFlow(Sizing.fill(FULL), Sizing.content()).gap(GAP);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(new CodexCell(volume, state, tooltip(volume, progress), null));
        int nameColor = state == CodexProgress.State.LEARNED ? paper(ThemeTokens.ACCENT) : paper(ThemeTokens.TEXT);
        var text = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        text.child(headerLabel(volume.name(), nameColor));
        text.child(headerLabel(stateText(volume, state), stateColor(state)));
        row.child(text);
        if (state == CodexProgress.State.LEARNED) {
            row.cursorStyle(io.wispforest.owo.ui.core.CursorStyle.HAND);
            row.mouseDown().subscribe((event, doubleClick) -> {
                // 点已学的卷头展开或收起全文。
                toggle.accept(volume.id());
                return true;
            });
        }
        return row;
    }

    private String stateText(CodexCatalog.Volume volume, CodexProgress.State state) {
        // 已学写式数；未得写式数和境界分布；够不着写最低一式要的境界。
        return switch (state) {
            case LEARNED -> {
                String toggle = get("codex.expand", "展开全文");
                if (expanded.contains(volume.id())) {
                    toggle = get("codex.collapse", "收起");
                }
                yield get("codex.learned_details", "已学 · %s 式 · %s", volume.techniques().size(), toggle);
            }
            case MISSING -> get("codex.missing_details", "未得 · %s", realmSpread(volume));
            case OUT_OF_REACH -> get("codex.realm_details", "%s方可施展 · %s 式",
                    CodexSources.realmName(volume.minimumRealm()), volume.techniques().size());
        };
    }

    private static int stateColor(CodexProgress.State state) {
        // 够不着用醒目的错误色，其余用浅墨。
        if (state == CodexProgress.State.OUT_OF_REACH) {
            return paper(ThemeTokens.INPUT_ERROR);
        }
        return paper(ThemeTokens.MUTED_TEXT);
    }

    private static String realmSpread(CodexCatalog.Volume volume) {
        // 「三式」或「游炁四式、真炁三式、化元三式」，让人看得到这卷往上还有什么。
        var counts = new TreeMap<Integer, Integer>();
        volume.techniques().forEach(named -> counts.merge(CodexCatalog.Volume.realmOf(named), 1, Integer::sum));
        if (counts.size() == 1) {
            return get("codex.forms", "%s 式", volume.techniques().size());
        }
        var parts = counts.entrySet().stream()
                .map(entry -> get("codex.realm_forms", "%s %s 式",
                        CodexSources.realmName(entry.getKey()), entry.getValue())).toList();
        return String.join(get("codex.separator", "、"), parts);
    }

    private void learned(FlowLayout block, CodexCatalog.Volume volume) {
        // 平时列式名与简介；展开后加上服务端写好的数值与条件。境界不够的式标出要什么境界。
        for (var named : volume.techniques()) {
            var entry = progress.known().get(named.reference().getId());
            String name = techniqueName(named);
            int realm = CodexCatalog.Volume.realmOf(named);
            if (realm > progress.realm()) {
                name += get("codex.realm_suffix", "（%s方可施展）", CodexSources.realmName(realm));
            }
            block.child(label(name, paper(ThemeTokens.TEXT), GAP));
            String description = entry != null ? entry.getDescription() : named.description();
            if (!description.isBlank()) {
                block.child(label(description, paper(ThemeTokens.MUTED_TEXT), GAP * 2));
            }
            if (expanded.contains(volume.id()) && entry != null && entry.getDetailsCount() > 0) {
                block.child(label(String.join("\n", entry.getDetailsList()), paper(ThemeTokens.MUTED_TEXT), GAP * 2));
            }
        }
    }

    private void missing(FlowLayout block, CodexCatalog.Volume volume) {
        // 剪影：式名不露，只给来源暗示和前置。
        block.child(label("？？？", paper(ThemeTokens.MUTED_TEXT), GAP));
        block.child(label(CodexSources.hint(volume), paper(ThemeTokens.TEXT), GAP));
        prerequisites(block, volume);
    }

    private void outOfReach(FlowLayout block, CodexCatalog.Volume volume) {
        // 够不着：功法包写的一句威力，再给来源暗示和前置；学是能先学的，施展要等境界。缺句子时显示键名，验收一眼可见。
        String power = Language.getInstance().getOrDefault("codex.power." + volume.id().replace(':', '.'));
        block.child(label(power, paper(ThemeTokens.TEXT), GAP));
        block.child(label(CodexSources.hint(volume), paper(ThemeTokens.TEXT), GAP));
        prerequisites(block, volume);
    }

    private void prerequisites(FlowLayout block, CodexCatalog.Volume volume) {
        // 还没学会的前置卷写出来；都学会了就不占行。
        List<String> missing = volume.prerequisiteIds().stream()
                .filter(id -> !progress.learned(id, catalog))
                .map(id -> "《" + volume.prerequisiteNames().get(id) + "》").toList();
        if (!missing.isEmpty()) {
            String names = String.join(get("codex.separator", "、"), missing);
            String hint = get("codex.prerequisites", "须先学会%s", names);
            block.child(label(hint, paper(ThemeTokens.MUTED_TEXT), GAP));
        }
    }

    static String tooltip(CodexCatalog.Volume volume, CodexProgress progress) {
        // 目录格子的悬停说明：卷全名和状态。
        return switch (progress.state(volume)) {
            case LEARNED -> get("codex.learned_hint", "%s\n已学", volume.fullName());
            case MISSING -> get("codex.missing_hint", "%s\n未得", volume.fullName());
            case OUT_OF_REACH -> get("codex.realm_hint", "%s\n%s方可施展", volume.fullName(),
                    CodexSources.realmName(volume.minimumRealm()));
        };
    }

    private static String techniqueName(NamedTechnique named) {
        // 资源包翻译优先，目录名兜底，与功法配置一致。
        String key = "technique." + named.reference().getId().replace(':', '.');
        return Component.translatableWithFallback(key, named.fallbackName()).getString();
    }

    private LabelComponent headerLabel(String text, int color) {
        // 卷头文字紧贴格子右侧：折行宽度扣掉格子，但不再额外缩进。
        var label = label(text, color, CodexCell.SIZE + GAP);
        label.margins(io.wispforest.owo.ui.core.Insets.none());
        return label;
    }

    private LabelComponent label(String text, int color, int indent) {
        // 正文按页宽自己折好（避头尾），缩进用左边距表示层级；折好的行都不超宽，标签不会再折。
        int lineWidth = Math.max(1, width - indent);
        var font = Minecraft.getInstance().font;
        var label = UIComponents.label(Component.literal(CodexLines.wrap(text, lineWidth, font::width)));
        label.maxWidth(lineWidth);
        label.color(Color.ofArgb(color));
        label.shadow(false /* shadow */);
        label.margins(io.wispforest.owo.ui.core.Insets.left(indent));
        return label;
    }
}
