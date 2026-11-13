package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.input.MouseButtonEvent;

/** 明细表的一行；点击展开这条经脉的详情，再点收起。 */
final class MeridianDataRow extends BaseUIComponent {
    static final String ID_PREFIX = "meridian-data-row-";
    private static final int FULL = 100;
    private static final int HEIGHT = 18;
    private static final int TEXT_TOP = 3;
    private static final int CELL_BAR_TOP = 13;
    private static final int CELL_BAR_HEIGHT = 2;
    private static final int DETAIL_LINES = 5;
    private static final int STACK_WIDTH = 120;
    private static final int STACK_HEIGHT = 6;
    private static final int STACK_TOP = 2;
    private static final int LABEL_WIDTH = 36;
    private static final double PERCENT = 100;
    private static final String SPACE = "　";
    private MeridianData.Row row;
    private boolean expanded;
    private final Consumer<String> toggled;

    MeridianDataRow(MeridianData.Row row, boolean expanded, Consumer<String> toggled) {
        // 展开状态由数据页记住，快照刷新后仍保持。
        this.row = row;
        this.expanded = expanded;
        this.toggled = toggled;
        id(ID_PREFIX + row.definition().getId());
        cursorStyle(CursorStyle.HAND);
        resize();
    }

    void update(MeridianData.Row next) {
        // 同一条经脉的新数值。
        row = next;
    }

    private void resize() {
        // 收起时一行，展开时多出详情几行。
        int height = HEIGHT;
        if (expanded) {
            height += DETAIL_LINES * DataDrawing.LINE_HEIGHT + DataDrawing.PADDING;
        }
        sizing(Sizing.fill(FULL), Sizing.fixed(height));
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 悬停或展开时换底色，底边一像素分隔。
        String background = ThemeTokens.PANEL;
        if (hovered) {
            background = ThemeTokens.CONTROL_HOVER;
        } else if (expanded) {
            background = ThemeTokens.CONTROL;
        }
        context.fill(x, y, x + width, y + height, MeridianTheme.color(background));
        context.fill(x, y + height - 1, x + width, y + height, MeridianTheme.color(ThemeTokens.BORDER));
        cells(context);
        if (expanded) {
            detail(context, y + HEIGHT);
        }
    }

    private void cells(OwoUIGraphics context) {
        // 六列：名称、开拓、内腔、外壁、平均密度、主属性。
        int top = y + TEXT_TOP;
        var font = DataDrawing.font();
        String name = font.plainSubstrByWidth(row.definition().getDisplayName(),
                MeridianDataHeader.width(width, MeridianDataHeader.NAME));
        DataDrawing.text(context, name, column(MeridianDataHeader.NAME), top, ThemeTokens.TEXT);
        DataDrawing.text(context, opening(), column(MeridianDataHeader.OPENING), top, openingToken());
        account(context, row.lumen(), MeridianDataHeader.LUMEN);
        account(context, row.wall(), MeridianDataHeader.WALL);
        DataDrawing.text(context, DataDrawing.number(row.density()), column(MeridianDataHeader.DENSITY), top,
                ThemeTokens.TEXT);
        String main = row.mainElement();
        int mainColor = MeridianTheme.color(ThemeTokens.MUTED_TEXT);
        if (!row.elements().isEmpty()) {
            mainColor = MeridianData.elementColor(row.elements().getFirst().key());
        }
        context.text(font, main, column(MeridianDataHeader.MAIN), top, mainColor, false /* shadow */);
    }

    private void account(OwoUIGraphics context, MeridianData.Account account, int column) {
        // 「已有/可装」加一条细装载条，宽度贴着本列。
        int left = column(column);
        String caption = DataDrawing.number(account.amount()) + "/" + DataDrawing.number(account.capacity());
        DataDrawing.text(context, caption, left, y + TEXT_TOP, ThemeTokens.TEXT);
        var bounds = new MeridianTheme.Bounds(left, y + CELL_BAR_TOP, MeridianDataHeader.width(width, column),
                CELL_BAR_HEIGHT);
        DataDrawing.bar(context, bounds, account.fill(), MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
    }

    private void detail(OwoUIGraphics context, int top) {
        // 展开后的详情：状态与开拓、吞吐与疲劳、两个账户、相态、属性分段条。
        var definition = row.definition();
        var state = row.state();
        int left = x + DataDrawing.PADDING;
        String status = "状态：" + BodyInformation.conditionName(state.getCondition()) + SPACE
                + "开拓：" + BodyInformation.stageName(state.getOpening().getStage()) + SPACE
                + "进度：" + DataDrawing.number(state.getOpening().getProgress() * PERCENT) + "%";
        String limits = "吞吐：" + DataDrawing.number(definition.getThroughputPerSecond()) + " 元/秒" + SPACE
                + "承受度：" + DataDrawing.number(definition.getResilience()) + SPACE
                + "疲劳：" + DataDrawing.number(state.getFatigue()) + " / "
                + DataDrawing.number(definition.getFatigueCapacity());
        String accounts = "内腔：" + DataDrawing.amountOf(row.lumen()) + SPACE
                + "外壁：" + DataDrawing.amountOf(row.wall());
        List<String> lines = List.of(status, limits, accounts, "相态：" + joined(row.phases()));
        int line = top;
        for (String text : lines) {
            DataDrawing.text(context, text, left, line, ThemeTokens.MUTED_TEXT);
            line += DataDrawing.LINE_HEIGHT;
        }
        DataDrawing.text(context, "属性：", left, line, ThemeTokens.MUTED_TEXT);
        var bounds = new MeridianTheme.Bounds(left + LABEL_WIDTH, line + STACK_TOP, STACK_WIDTH, STACK_HEIGHT);
        DataDrawing.stacked(context, bounds, row.elements());
        DataDrawing.text(context, joined(row.elements()), bounds.x() + STACK_WIDTH + DataDrawing.PADDING, line,
                ThemeTokens.MUTED_TEXT);
    }

    private String joined(List<MeridianData.Share> shares) {
        // 「气态 1.20 · 液态 3.00」；空经脉写「无」。
        if (shares.isEmpty()) {
            return "无";
        }
        double total = shares.stream().mapToDouble(MeridianData.Share::amount).sum();
        return shares.stream()
                .map(share -> share.name() + " " + DataDrawing.number(share.amount())
                        + "（" + DataDrawing.share(share.amount(), total) + "）")
                .collect(Collectors.joining(" · "));
    }

    private String opening() {
        // 进行中的经脉附上进度，便于和整页其他行比较。
        var opening = row.state().getOpening();
        String stage = BodyInformation.stageName(opening.getStage());
        if (opening.getStage() == MeridianOpeningStage.IN_PROGRESS) {
            return stage + " " + DataDrawing.percent(opening.getProgress());
        }
        return stage;
    }

    private String openingToken() {
        // 已开拓用正文色，其余淡色。
        if (row.state().getOpening().getStage() == MeridianOpeningStage.OPENED) {
            return ThemeTokens.TEXT;
        }
        return ThemeTokens.MUTED_TEXT;
    }

    private int column(int column) {
        // 本行第 column 列的左边界。
        return MeridianDataHeader.left(x, width, column);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 左键展开或收起。
        if (click.button() != 0) {
            return false;
        }
        expanded = !expanded;
        resize();
        toggled.accept(row.definition().getId());
        return true;
    }
}
