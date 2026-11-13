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
    private final HoverNotes notes = new HoverNotes(this);
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
        // 从上到下：标题、总数、内腔、外壁、相态柱、器官与在途；每块登记一条悬停说明。
        notes.begin();
        MeridianTheme.panel(context, new MeridianTheme.Bounds(x, y, width, height));
        int left = x + DataDrawing.PADDING;
        int right = x + width - DataDrawing.PADDING;
        int inner = right - left;
        int top = y + DataDrawing.PADDING;
        DataDrawing.heading(context, DataText.get("summary"), left, top, inner);
        top += DataDrawing.HEADING_HEIGHT;
        DataDrawing.text(context, DataText.get("total"), left, top, ThemeTokens.TEXT);
        DataDrawing.textRight(context, DataDrawing.amount(page.meridianTotal()), right, top, ThemeTokens.TEXT);
        notes.add(new MeridianTheme.Bounds(left, top, inner, DataDrawing.LINE_HEIGHT),
                () -> DataText.get("tip.total"));
        top += DataDrawing.LINE_HEIGHT;
        account(context, DataText.get("lumen"), page.lumen(), top);
        top += ACCOUNT_HEIGHT;
        account(context, DataText.get("wall"), page.wall(), top);
        top += ACCOUNT_HEIGHT;
        DataDrawing.heading(context, DataText.get("by_phase"), left, top, inner);
        top += DataDrawing.HEADING_HEIGHT;
        phases(context, top);
        String footer = DataText.get("footer", DataDrawing.number(page.organs()), DataDrawing.number(page.transits()));
        int footerTop = y + height - DataDrawing.PADDING - DataDrawing.LINE_HEIGHT;
        DataDrawing.text(context, footer, left, footerTop, ThemeTokens.MUTED_TEXT);
        notes.add(new MeridianTheme.Bounds(left, footerTop, inner, DataDrawing.LINE_HEIGHT),
                () -> DataText.get("tip.footer"));
        notes.finish(mouseX, mouseY);
    }

    private void account(OwoUIGraphics context, String name, MeridianData.Account account, int top) {
        // 一行名称和「已有 / 可装」，下面一条按体积算的装载条。
        int left = x + DataDrawing.PADDING;
        int right = x + width - DataDrawing.PADDING;
        String caption = DataText.get("account", DataDrawing.amountOf(account), DataDrawing.percent(account.fill()));
        DataDrawing.text(context, name, left, top, ThemeTokens.TEXT);
        DataDrawing.textRight(context, caption, right, top, ThemeTokens.MUTED_TEXT);
        var bounds = new MeridianTheme.Bounds(left, top + BAR_TOP, right - left, DataDrawing.BAR_HEIGHT);
        DataDrawing.bar(context, bounds, account.fill(), MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
        notes.add(new MeridianTheme.Bounds(left, top, right - left, ACCOUNT_HEIGHT), () -> DataText.get("tip.account",
                name, DataDrawing.number(account.amount()), DataDrawing.number(account.capacity()),
                DataDrawing.percent(account.fill())));
    }

    private void phases(OwoUIGraphics context, int top) {
        // 只画身体里实际有的相态；柱高按最多的那一相归一，柱顶写占比，柱下写相态名；放不下的柱不画。
        if (page.phases().isEmpty()) {
            DataDrawing.text(context, DataText.get("no_qi"), x + DataDrawing.PADDING, top, ThemeTokens.MUTED_TEXT);
            return;
        }
        double max = page.phases().stream().mapToDouble(MeridianData.Share::amount).max().orElse(0);
        double total = page.meridianTotal();
        int baseline = top + DataDrawing.LINE_HEIGHT + COLUMN_AREA;
        int column = x + DataDrawing.PADDING;
        int right = x + width - DataDrawing.PADDING;
        for (MeridianData.Share phase : page.phases()) {
            if (column + COLUMN_WIDTH > right) {
                return;
            }
            int barHeight = Math.max(MIN_COLUMN, (int) Math.round(COLUMN_AREA * QiDisplay.ratio(phase.amount(), max)));
            context.fill(column, baseline - barHeight, column + COLUMN_WIDTH, baseline,
                    MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
            String share = DataDrawing.share(phase.amount(), total);
            DataDrawing.text(context, share, column, baseline - barHeight - DataDrawing.LINE_HEIGHT + LABEL_GAP,
                    ThemeTokens.MUTED_TEXT);
            String name = phase.name();
            DataDrawing.text(context, name, column, baseline + LABEL_GAP, ThemeTokens.TEXT);
            var area = new MeridianTheme.Bounds(column, top, COLUMN_WIDTH + COLUMN_GAP,
                    baseline + LABEL_GAP + DataDrawing.LINE_HEIGHT - top);
            notes.add(area, () -> DataText.get("tip.phase", name, DataDrawing.number(phase.amount()), share));
            column += COLUMN_WIDTH + COLUMN_GAP;
        }
    }
}
