package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.combat.fabric.client.CombatSlotAccess;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Size;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.Map;
import static dev.kizuna.meridian.ui.client.CombatBookStyle.*;

/** 功法谱两页书：左页全谱目录与完成度，右页一本书的每一卷按三种状态铺开。 */
final class CodexPanel extends FlowLayout implements AutoCloseable {
    private static final int SCROLLBAR_SPACE = 6;
    private static final int NAME_WIDTH = 64;
    private static final int COUNT_WIDTH = 26;
    private static final int BAR_HEIGHT = 3;
    private static final int ROW_PADDING = 2;
    private final FlowLayout content;
    private final FlowLayout index;
    private final FlowLayout page;
    private final AutoCloseable subscription;
    private final CodexCatalog catalog = CodexMoments.catalog();
    private CodexProgress progress;
    private final java.util.Set<String> expanded = new java.util.HashSet<>();
    private String selectedBook;
    private int arrangedWidth = -1;
    private int arrangedHeight = -1;
    private int pageWidth = TILE_WIDTH;
    private boolean closed;

    CodexPanel() {
        // 内容来自 XML；已学列表一变就重画两页，窗口关闭时解除订阅。
        super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "codex"))
                .expandTemplate(FlowLayout.class, "codex-book", Map.of());
        index = content.childById(FlowLayout.class, "index");
        page = content.childById(FlowLayout.class, "page");
        index.gap(GAP);
        page.gap(GAP * 2);
        child(content);
        progress = CodexProgress.current();
        selectedBook = CodexMoments.latestFreshBook().orElseGet(this::firstBook);
        subscription = CombatSlotAccess.subscribe(this::refresh);
    }

    private String firstBook() {
        // 没有新卷时翻到第一本有进度的书，一卷没学时翻到第一本。
        return catalog.books().stream().filter(book -> progress.learnedCount(book) > 0).findFirst()
                .or(() -> catalog.books().stream().findFirst()).map(CodexCatalog.Book::id).orElse("");
    }

    private void refresh() {
        // 冷却推送也会通知，只有已学列表或境界真的变了才重建。
        var next = CodexProgress.current();
        if (next.known().keySet().equals(progress.known().keySet()) && next.realm() == progress.realm()) {
            return;
        }
        progress = next;
        rebuild();
    }

    @Override
    public void layout(Size space) {
        // 窗口尺寸变化时重新定位两页并按新宽度折行。
        if (width != arrangedWidth || height != arrangedHeight) {
            arrangedWidth = width;
            arrangedHeight = height;
            runAndDeferEvents(this::arrange);
        }
        super.layout(space);
    }

    private void arrange() {
        // 左右两页各占一半，标题一行，下面是各自的滚动区。
        pageWidth = Math.max(1, width / 2 - MARGIN * 2);
        int pageHeight = Math.max(1, height - TITLE_HEIGHT - MARGIN * 2);
        int rightX = width / 2 + MARGIN;
        place("index-title", MARGIN, MARGIN, pageWidth, LINE_HEIGHT);
        place("index-count", MARGIN, MARGIN, pageWidth, LINE_HEIGHT);
        place("page-title", rightX, MARGIN, pageWidth, LINE_HEIGHT);
        place("index-scroll", MARGIN, TITLE_HEIGHT + MARGIN, pageWidth, pageHeight);
        place("page-scroll", rightX, TITLE_HEIGHT + MARGIN, pageWidth, pageHeight);
        rebuild();
    }

    private void place(String id, int left, int top, int width, int height) {
        // 标题与滚动区共用同一定位方法。
        var component = content.childById(UIComponent.class, id);
        MeridianPanel.place(component, left, top, width, height);
        if (component instanceof LabelComponent label) {
            label.color(Color.ofArgb(paper(ThemeTokens.TEXT)));
        }
    }

    private void rebuild() {
        // 两页一起重建，完成度与右页状态始终来自同一份快照。
        int learned = progress.learnedCount(catalog);
        var count = content.childById(LabelComponent.class, "index-count");
        String text = "已得 " + learned + " / " + catalog.volumeCount() + " 卷";
        count.text(Component.literal(text));
        count.color(Color.ofArgb(paper(ThemeTokens.MUTED_TEXT)));
        int textWidth = Minecraft.getInstance().font.width(text);
        count.positioning(io.wispforest.owo.ui.core.Positioning.absolute(MARGIN + pageWidth - textWidth, MARGIN));
        rebuildIndex();
        rebuildPage();
    }

    private void rebuildIndex() {
        // 每本书一行：书名、本书进度、各卷一格；空格子就是还差的那几卷。
        index.clearChildren();
        for (var book : catalog.books()) {
            index.child(bookRow(book));
        }
    }

    private FlowLayout bookRow(CodexCatalog.Book book) {
        // 整行可点，翻到这本书；当前这本的书名用强调色。
        var row = UIContainers.horizontalFlow(Sizing.fill(FULL), Sizing.content()).gap(GAP);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.padding(Insets.vertical(ROW_PADDING));
        int learned = progress.learnedCount(book);
        boolean complete = learned == book.volumes().size();
        int color = book.id().equals(selectedBook) ? paper(ThemeTokens.ACCENT) : paper(ThemeTokens.TEXT);
        row.child(fixedLabel(book.name(), color, NAME_WIDTH));
        String countText = complete ? "齐" : learned + "/" + book.volumes().size();
        int countColor = complete ? paper(ThemeTokens.ACCENT) : paper(ThemeTokens.MUTED_TEXT);
        row.child(fixedLabel(countText, countColor, COUNT_WIDTH));
        for (var volume : book.volumes()) {
            row.child(new CodexCell(volume, progress.state(volume), CodexPage.tooltip(volume, progress),
                    () -> select(book.id())));
        }
        row.cursorStyle(CursorStyle.HAND);
        row.mouseDown().subscribe((event, doubleClick) -> {
            // 点在书名或进度上同样翻页。
            select(book.id());
            return true;
        });
        return row;
    }

    private void rebuildPage() {
        // 右页标题写本书进度，正文交给书页构造器。
        var book = catalog.books().stream().filter(candidate -> candidate.id().equals(selectedBook)).findFirst();
        var title = content.childById(LabelComponent.class, "page-title");
        page.clearChildren();
        if (book.isEmpty()) {
            title.text(Component.literal(""));
            return;
        }
        int learned = progress.learnedCount(book.get());
        title.text(Component.literal("《" + book.get().name() + "》　" + learned + "/" + book.get().volumes().size()));
        title.color(Color.ofArgb(paper(ThemeTokens.ACCENT)));
        new CodexPage(page, progress, catalog, Math.max(1, pageWidth - SCROLLBAR_SPACE), expanded, this::toggle)
                .fill(book.get());
    }

    private void toggle(String volumeId) {
        // 展开状态只在本窗口里记，关窗即忘。
        if (!expanded.remove(volumeId)) {
            expanded.add(volumeId);
        }
        rebuildPage();
    }

    void select(String bookId) {
        // 翻页前把上一本书已播完的新卷记成看过。
        if (bookId.equals(selectedBook)) {
            return;
        }
        CodexMoments.viewed(selectedBook);
        selectedBook = bookId;
        rebuildIndex();
        rebuildPage();
    }

    private LabelComponent fixedLabel(String text, int color, int width) {
        // 目录列宽固定，各行的格子对齐成一列。
        var label = UIComponents.label(Component.literal(text));
        label.color(Color.ofArgb(color));
        label.shadow(false /* shadow */);
        label.maxWidth(width);
        label.horizontalSizing(Sizing.fixed(width));
        return label;
    }

    String selectedBook() {
        // 客户端验收读取当前翻到的书。
        return selectedBook;
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 书页背景与功法配置同一张纸；左页标题下画一条全谱进度。
        book(context, new Bounds(x, y, width, height));
        int barY = y + MARGIN + LINE_HEIGHT;
        int barX = x + MARGIN;
        context.fill(barX, barY, barX + pageWidth, barY + BAR_HEIGHT, paper(ThemeTokens.PROGRESS_TRACK));
        int total = Math.max(1, catalog.volumeCount());
        int filled = pageWidth * progress.learnedCount(catalog) / total;
        context.fill(barX, barY, barX + filled, barY + BAR_HEIGHT, paper(ThemeTokens.PROGRESS_FILL));
        super.draw(context, mouseX, mouseY, partialTicks, delta);
    }

    @Override
    public void close() {
        // 关窗时把当前这本书播完的新卷记成看过，订阅只释放一次。
        if (closed) {
            return;
        }
        closed = true;
        CodexMoments.viewed(selectedBook);
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("无法释放功法谱订阅", failure);
        }
    }
}
