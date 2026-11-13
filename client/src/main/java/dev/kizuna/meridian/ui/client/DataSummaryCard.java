package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;

/** 「整体」卡：经脉真元总数、内腔外壁各自的装载，以及按相态分的柱状图。 */
final class DataSummaryCard extends BaseUIComponent {
    private static final int FULL = 100;
    private static final int ACCOUNT_HEIGHT = 20;
    private static final int BAR_TOP = 10;
    private static final int COLUMN_WIDTH = 28;
    private static final int COLUMN_AREA = 34;
    private static final int COLUMN_GAP = 10;
    private static final int MIN_COLUMN = 1;
    private static final int LABEL_GAP = 2;
    private MeridianData.Page page;

    DataSummaryCard(MeridianData.Page page) {
        // 尺寸由数据页排布，内容随快照替换。
        this.page = page;
        sizing(Sizing.fill(FULL), Sizing.fill(FULL));
    }

    void update(MeridianData.Page next) {
        // 新快照到达时整卡重画，不保留旧值。
        page = next;
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 从上到下：标题、总数、内腔、外壁、相态柱、器官与在途。
        MeridianTheme.panel(context, new MeridianTheme.Bounds(x, y, width, height));
        int left = x + DataDrawing.PADDING;
        int right = x + width - DataDrawing.PADDING;
        int inner = right - left;
        int top = y + DataDrawing.PADDING;
        DataDrawing.heading(context, "整体", left, top, inner);
        top += DataDrawing.HEADING_HEIGHT;
        DataDrawing.text(context, "经脉真元", left, top, ThemeTokens.TEXT);
        DataDrawing.textRight(context, DataDrawing.number(page.meridianTotal()) + DataDrawing.UNIT, right, top,
                ThemeTokens.TEXT);
        top += DataDrawing.LINE_HEIGHT;
        account(context, "内腔", page.lumen(), top);
        top += ACCOUNT_HEIGHT;
        account(context, "外壁", page.wall(), top);
        top += ACCOUNT_HEIGHT;
        DataDrawing.heading(context, "按相态", left, top, inner);
        top += DataDrawing.HEADING_HEIGHT;
        phases(context, top);
        String footer = "器官缓存 " + DataDrawing.number(page.organs()) + DataDrawing.UNIT
                + "　在途 " + DataDrawing.number(page.transits()) + DataDrawing.UNIT;
        DataDrawing.text(context, footer, left, y + height - DataDrawing.PADDING - DataDrawing.LINE_HEIGHT,
                ThemeTokens.MUTED_TEXT);
    }

    private void account(OwoUIGraphics context, String name, MeridianData.Account account, int top) {
        // 一行名称和「已有 / 可装」，下面一条按体积算的装载条。
        int left = x + DataDrawing.PADDING;
        int right = x + width - DataDrawing.PADDING;
        String caption = DataDrawing.amountOf(account) + "（" + DataDrawing.percent(account.fill()) + "）";
        DataDrawing.text(context, name, left, top, ThemeTokens.TEXT);
        DataDrawing.textRight(context, caption, right, top, ThemeTokens.MUTED_TEXT);
        var bounds = new MeridianTheme.Bounds(left, top + BAR_TOP, right - left, DataDrawing.BAR_HEIGHT);
        DataDrawing.bar(context, bounds, account.fill(), MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
    }

    private void phases(OwoUIGraphics context, int top) {
        // 只画身体里实际有的相态；柱高按最多的那一相归一，柱顶写占比，柱下写相态名。
        if (page.phases().isEmpty()) {
            DataDrawing.text(context, "经脉里没有真元", x + DataDrawing.PADDING, top, ThemeTokens.MUTED_TEXT);
            return;
        }
        double max = page.phases().stream().mapToDouble(MeridianData.Share::amount).max().orElse(0);
        double total = page.meridianTotal();
        int baseline = top + DataDrawing.LINE_HEIGHT + COLUMN_AREA;
        int column = x + DataDrawing.PADDING;
        for (MeridianData.Share phase : page.phases()) {
            int barHeight = Math.max(MIN_COLUMN, (int) Math.round(COLUMN_AREA * QiDisplay.ratio(phase.amount(), max)));
            context.fill(column, baseline - barHeight, column + COLUMN_WIDTH, baseline,
                    MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
            DataDrawing.text(context, DataDrawing.share(phase.amount(), total), column, baseline - barHeight
                    - DataDrawing.LINE_HEIGHT + LABEL_GAP, ThemeTokens.MUTED_TEXT);
            DataDrawing.text(context, phase.name(), column, baseline + LABEL_GAP, ThemeTokens.TEXT);
            column += COLUMN_WIDTH + COLUMN_GAP;
        }
    }
}
