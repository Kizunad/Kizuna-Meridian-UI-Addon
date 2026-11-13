package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;

/**
 * 经脉窗口的「数据」页：上方整体与元素两张卡，下方逐条经脉的明细表。
 * 窗口窄时两张卡上下叠放，矮到放不下时整页套一层滚动；正常尺寸下排布与外层滚动都不出现。
 */
final class MeridianDataPage {
    static final String NOTICE_ID = "meridian-data-notice";
    private static final int FULL = 100;
    private static final int TOP_BAND = 164;
    private static final int GAP = 6;
    private static final int SCROLLBAR = 5;
    private static final int CARD_MIN_WIDTH = 200;
    private static final int MIN_TABLE_ROWS = 4;
    private static final int HIDDEN_SCROLLBAR = 0;
    private final FlowLayout content;
    private final ScrollContainer<FlowLayout> frame;
    private final int frameScrollbar;
    private final UIComponent root;
    private final DataSummaryCard summary;
    private final ElementCard elements;
    private final FlowLayout rows;
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, MeridianDataRow> rowViews = new LinkedHashMap<>();
    private boolean noticeShown;

    MeridianDataPage(BodySnapshot body) {
        // 结构来自 XML，卡片和行是自绘组件，挂进对应占位；外层滚动平时不显示滚动条。
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "meridian_data"))
                .expandTemplate(FlowLayout.class, "meridian-data", Map.of());
        MeridianData.Page page = MeridianData.of(body);
        summary = new DataSummaryCard(page);
        elements = new ElementCard(page);
        content.childById(FlowLayout.class, "data-summary").child(summary);
        content.childById(FlowLayout.class, "data-elements").child(elements);
        content.childById(FlowLayout.class, "data-header").child(new MeridianDataHeader());
        rows = content.childById(FlowLayout.class, "data-rows");
        frame = UIContainers.verticalScroll(Sizing.fill(FULL), Sizing.fill(FULL), content);
        frame.id("meridian-data-frame");
        frameScrollbar = frame.scrollbarThiccness();
        root = new ResponsiveLayout(frame, this::arrange);
        fill(page);
    }

    UIComponent root() {
        // 经脉窗口切到「数据」时挂上这棵树。
        return root;
    }

    void update(BodySnapshot body) {
        // 每份新快照重新投影一次，两张卡和各行换上新数值。
        MeridianData.Page page = MeridianData.of(body);
        summary.update(page);
        elements.update(page);
        fill(page);
    }

    private void fill(MeridianData.Page page) {
        // 经脉集合和开拓提示都没变时只换数值，保留滚动位置和展开状态；否则整表重建。
        List<String> ids = page.rows().stream().map(row -> row.definition().getId()).toList();
        boolean notice = page.opened() == 0;
        if (ids.equals(List.copyOf(rowViews.keySet())) && notice == noticeShown) {
            page.rows().forEach(row -> rowViews.get(row.definition().getId()).update(row));
            return;
        }
        rows.clearChildren();
        rowViews.clear();
        noticeShown = notice;
        if (notice) {
            rows.child(new Notice(() -> noticeText(ids)));
        }
        for (MeridianData.Row row : page.rows()) {
            String id = row.definition().getId();
            var view = new MeridianDataRow(row, expanded.contains(id), this::toggled);
            rowViews.put(id, view);
            rows.child(view);
        }
    }

    private static String noticeText(List<String> ids) {
        // 还没同步到经脉时说没有数据；有经脉但一条都没开拓时指到「经脉」页。
        if (ids.isEmpty()) {
            return DataText.get("empty.no_rows");
        }
        return DataText.get("empty.unopened");
    }

    private void toggled(String id) {
        // 记住哪几行展开着，重建表格后照样展开。
        if (!expanded.remove(id)) {
            expanded.add(id);
        }
    }

    private void arrange(int width, int height) {
        // 宽时两张卡平分上方一条，窄时上下叠放；表格至少露出几行，不够高就整页滚动并让出滚动条。
        boolean stacked = width < CARD_MIN_WIDTH * 2 + GAP;
        int band = TOP_BAND;
        if (stacked) {
            band = TOP_BAND * 2 + GAP;
        }
        int tableTop = band + GAP;
        int needed = tableTop + MeridianDataHeader.HEIGHT + MIN_TABLE_ROWS * MeridianDataRow.HEIGHT;
        boolean scrolling = height < needed;
        int pageWidth = width;
        int scrollbar = HIDDEN_SCROLLBAR;
        if (scrolling) {
            pageWidth = width - SCROLLBAR;
            scrollbar = frameScrollbar;
        }
        int pageHeight = Math.max(height, needed);
        frame.scrollbarThiccness(scrollbar);
        content.sizing(Sizing.fixed(Math.max(1, pageWidth)), Sizing.fixed(pageHeight));
        cards(pageWidth, stacked);
        MeridianPanel.place(content.childById(UIComponent.class, "data-header"), 0, tableTop,
                pageWidth - SCROLLBAR, MeridianDataHeader.HEIGHT);
        int rowsTop = tableTop + MeridianDataHeader.HEIGHT;
        MeridianPanel.place(content.childById(UIComponent.class, "data-rows-scroll"), 0, rowsTop,
                pageWidth, pageHeight - rowsTop);
    }

    private void cards(int width, boolean stacked) {
        // 并排时左右平分，叠放时各占整宽、元素卡在下。
        UIComponent summaryCard = content.childById(UIComponent.class, "data-summary");
        UIComponent elementCard = content.childById(UIComponent.class, "data-elements");
        if (stacked) {
            MeridianPanel.place(summaryCard, 0, 0, width, TOP_BAND);
            MeridianPanel.place(elementCard, 0, TOP_BAND + GAP, width, TOP_BAND);
            return;
        }
        int half = (width - GAP) / 2;
        MeridianPanel.place(summaryCard, 0, 0, half, TOP_BAND);
        MeridianPanel.place(elementCard, half + GAP, 0, width - half - GAP, TOP_BAND);
    }

    /** 明细表顶上的一段淡色提示，最多折两行。 */
    private static final class Notice extends BaseUIComponent {
        private static final int LINES = 2;
        private final Supplier<String> text;

        Notice(Supplier<String> text) {
            // 宽度随表格，高度固定两行字加上下留白。
            this.text = text;
            id(NOTICE_ID);
            sizing(Sizing.fill(FULL), Sizing.fixed(LINES * DataDrawing.LINE_HEIGHT + DataDrawing.PADDING));
        }

        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 按当前宽度折行，超出两行的部分不画。
            var font = DataDrawing.font();
            int left = x + DataDrawing.PADDING;
            int line = y + DataDrawing.PADDING / 2;
            var lines = font.split(FormattedText.of(text.get()), width - DataDrawing.PADDING * 2);
            for (int index = 0; index < Math.min(LINES, lines.size()); index++) {
                context.text(font, lines.get(index), left, line, MeridianTheme.color(ThemeTokens.MUTED_TEXT),
                        false /* shadow */);
                line += DataDrawing.LINE_HEIGHT;
            }
        }
    }
}
