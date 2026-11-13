package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** 经脉窗口的「数据」页：上方整体与元素两张卡，下方逐条经脉的明细表。 */
final class MeridianDataPage {
    private static final int TOP_BAND = 164;
    private static final int GAP = 6;
    private static final int SCROLLBAR = 5;
    private final FlowLayout content;
    private final UIComponent root;
    private final DataSummaryCard summary;
    private final ElementCard elements;
    private final FlowLayout rows;
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, MeridianDataRow> rowViews = new LinkedHashMap<>();

    MeridianDataPage(BodySnapshot body) {
        // 结构来自 XML，卡片和行是自绘组件，挂进对应占位。
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "meridian_data"))
                .expandTemplate(FlowLayout.class, "meridian-data", Map.of());
        MeridianData.Page page = MeridianData.of(body);
        summary = new DataSummaryCard(page);
        elements = new ElementCard(page);
        content.childById(FlowLayout.class, "data-summary").child(summary);
        content.childById(FlowLayout.class, "data-elements").child(elements);
        content.childById(FlowLayout.class, "data-header").child(new MeridianDataHeader());
        rows = content.childById(FlowLayout.class, "data-rows");
        root = new ResponsiveLayout(content, this::arrange);
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
        // 经脉集合没变时只换数值，保留滚动位置和展开状态；有增减时整表重建。
        List<String> ids = page.rows().stream().map(row -> row.definition().getId()).toList();
        if (ids.equals(List.copyOf(rowViews.keySet()))) {
            page.rows().forEach(row -> rowViews.get(row.definition().getId()).update(row));
            return;
        }
        rows.clearChildren();
        rowViews.clear();
        for (MeridianData.Row row : page.rows()) {
            String id = row.definition().getId();
            var view = new MeridianDataRow(row, expanded.contains(id), this::toggled);
            rowViews.put(id, view);
            rows.child(view);
        }
    }

    private void toggled(String id) {
        // 记住哪几行展开着，重建表格后照样展开。
        if (!expanded.remove(id)) {
            expanded.add(id);
        }
    }

    private void arrange(int width, int height) {
        // 两张卡平分上方一条，表头和滚动表占剩下的高度；表头让出滚动条宽度与行对齐。
        int half = (width - GAP) / 2;
        MeridianPanel.place(content.childById(UIComponent.class, "data-summary"), 0, 0, half, TOP_BAND);
        MeridianPanel.place(content.childById(UIComponent.class, "data-elements"), half + GAP, 0,
                width - half - GAP, TOP_BAND);
        int tableTop = TOP_BAND + GAP;
        MeridianPanel.place(content.childById(UIComponent.class, "data-header"), 0, tableTop,
                width - SCROLLBAR, MeridianDataHeader.HEIGHT);
        int rowsTop = tableTop + MeridianDataHeader.HEIGHT;
        MeridianPanel.place(content.childById(UIComponent.class, "data-rows-scroll"), 0, rowsTop,
                width, height - rowsTop);
    }
}
