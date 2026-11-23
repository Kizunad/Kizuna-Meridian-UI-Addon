package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.ui.api.SurfaceReadout;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 新版体表窗口的正文。平常是平面人形和受伤部位逐行，人形下方接脚注，最底下一行留给悬停时才显示的操作提示；
 * 显示了体表以外的图层时换成可以转动的立体模型，回到只剩体表一层再换回来。
 */
final class ModernSurfacePanel {
    /** 向窗口要地方：窗口只会变大，不会被缩小。 */
    interface Room {
        /** 窗口外框至少这么大。 */
        void atLeast(int width, int height);

        /** 窗口再高这么多像素。 */
        void taller(int pixels);
    }

    static final String HINT_KEY = "meridian_ui.surface.menu.hint";
    private static final int FULL = 100;
    private static final int PADDING = 3;
    private static final int COLUMN_GAP = 6;
    private static final int SCROLLBAR_GAP = 1;
    private static final int FOOT_GAP = 2;
    private static final int LINE = SurfaceSheet.LINE;
    private final ModernBodyPanel model;
    private final Supplier<SurfaceReadout> readout;
    private final Room room;
    private final FlowLayout root = UIContainers.verticalFlow(Sizing.fill(FULL), Sizing.fill(FULL));
    private final FlowLayout content = UIContainers.verticalFlow(Sizing.fill(FULL), Sizing.fill(FULL));
    private final FlowLayout sheet;
    private final SurfaceSheet.Figure figure;
    private final SurfaceSheet.Wounds wounds;
    private final ScrollContainer<SurfaceSheet.Wounds> scroll;
    private final LabelComponent footnotes = UIComponents.label(Component.empty());
    private final Hint hint = new Hint();
    private final int scrollbar;
    private final int figureWidth;
    private final int figureHeight;
    private List<Component> shownFootnotes = List.of();
    private int listHeight;
    private int viewHeight;
    private int width;
    private int height;
    private boolean modelShown;
    private boolean footnotesMoved;

    ModernSurfacePanel(ModernBodyPanel model, Supplier<SurfaceReadout> readout, Room room) {
        this.model = model;
        this.readout = readout;
        this.room = room;
        figure = new SurfaceSheet.Figure(readout);
        figureWidth = SurfaceSheet.figureWidth(readout.get().figure());
        figureHeight = SurfaceSheet.figureHeight(readout.get().figure());
        wounds = new SurfaceSheet.Wounds(readout);
        listHeight = wounds.fit();
        scroll = UIContainers.verticalScroll(Sizing.fill(FULL), Sizing.fill(FULL), wounds);
        scrollbar = scroll.scrollbarThiccness();
        footnotes.id("surface-footnotes");
        content.child(figure);
        content.child(scroll);
        content.child(footnotes);
        content.child(hint);
        sheet = new ResponsiveLayout(content, this::arrange);
        root.child(sheet);
        showFootnotes(readout.get().footnotes());
        footnotesMoved = !shownFootnotes.isEmpty();
        model.onLayersChanged(this::showMatchingView);
    }

    /** 悬停在体表窗口里才显示的一行提示：图层和概况在右键菜单里。 */
    private final class Hint extends BaseUIComponent {
        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            if (root.isInBoundingBox(mouseX, mouseY)) {
                graphics.text(Minecraft.getInstance().font, Component.translatable(HINT_KEY).getString(), x, y,
                        MeridianTheme.color(ThemeTokens.MUTED_TEXT), false /* shadow */);
            }
        }
    }

    private int footnoteLines(int innerWidth) {
        // 太长的脚注会折行，折成几行就占几行。
        var font = Minecraft.getInstance().font;
        return shownFootnotes.stream().mapToInt(note -> font.split(note, innerWidth).size()).sum();
    }

    private void arrange(int nextWidth, int nextHeight) {
        // 自下而上留位：最底下一行提示，上面是脚注；人形贴左上角，受伤部位占右边剩下的整块。
        width = nextWidth;
        height = nextHeight;
        MeridianTheme.apply(content);
        int innerWidth = Math.max(1, width - PADDING * 2);
        int hintTop = Math.max(PADDING, height - PADDING - LINE);
        MeridianPanel.place(hint, PADDING, hintTop, innerWidth, LINE);
        int footHeight = footnoteLines(innerWidth) * LINE;
        int blockBottom = hintTop - FOOT_GAP;
        if (footHeight > 0) {
            blockBottom -= footHeight + FOOT_GAP;
        }
        MeridianPanel.place(footnotes, PADDING, blockBottom + FOOT_GAP, innerWidth, Math.max(1, footHeight));
        MeridianPanel.place(figure, PADDING, PADDING, figureWidth, figureHeight);
        int listLeft = PADDING + figureWidth + COLUMN_GAP;
        viewHeight = Math.max(1, blockBottom - PADDING);
        MeridianPanel.place(scroll, listLeft, PADDING, Math.max(1, width - listLeft - PADDING), viewHeight);
        reserveScrollbar();
    }

    private void reserveScrollbar() {
        // 内容比可见的这块高时才出滚动条，读数往左让开它。
        int inset = 0;
        if (listHeight > viewHeight) {
            inset = scrollbar + SCROLLBAR_GAP;
        }
        wounds.rightInset(inset);
    }

    private void showFootnotes(List<Component> notes) {
        shownFootnotes = notes;
        var text = Component.empty();
        for (int index = 0; index < notes.size(); index++) {
            if (index > 0) {
                text.append("\n");
            }
            text.append(notes.get(index));
        }
        footnotes.text(text);
    }

    private void showMatchingView() {
        boolean layered = model.layered();
        if (layered == modelShown) {
            return;
        }
        modelShown = layered;
        root.clearChildren();
        if (layered) {
            // 立体模型在平面人形那么小的窗口里看不清。
            room.atLeast(BodyFeature.MODEL_WIDTH, BodyFeature.MODEL_HEIGHT);
            root.child(model.root());
        } else {
            root.child(sheet);
        }
    }

    FlowLayout root() {
        return root;
    }

    /** 现在正文是不是立体模型。 */
    boolean modelShown() {
        return modelShown;
    }

    /** 提示那一行的组件；验收用它的位置。 */
    BaseUIComponent hint() {
        return hint;
    }

    /** 受伤部位那一栏实际画出来的宽度和可见的高度。 */
    int listWidth() {
        return wounds.drawnWidth();
    }

    int listViewHeight() {
        return viewHeight;
    }

    /** 现在显示着的脚注。 */
    List<Component> footnotes() {
        return shownFootnotes;
    }

    /** 身体快照换了：受伤部位的行数可能变，重定列表高度。 */
    void update() {
        listHeight = wounds.fit();
        reserveScrollbar();
    }

    /** 脚注里有不跟身体快照走的内容（状态效果），每个客户端 tick 看一次，变了才重排。 */
    void tick() {
        var notes = readout.get().footnotes();
        if (!notes.equals(shownFootnotes)) {
            showFootnotes(notes);
            footnotesMoved = true;
        }
        if (!footnotesMoved || width <= 0) {
            return;
        }
        footnotesMoved = false;
        arrange(width, height);
        // 脚注把人形挤出可见范围时，窗口长高到刚好放下。
        int cramped = figureHeight - viewHeight;
        if (cramped > 0 && !shownFootnotes.isEmpty()) {
            room.taller(cramped);
        }
    }
}
