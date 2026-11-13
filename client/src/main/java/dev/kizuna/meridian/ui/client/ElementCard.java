package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.ArrayList;
import java.util.List;

/** 「元素构成」卡：五行与无属性的占比圆环，右侧逐项写元数和百分比。 */
final class ElementCard extends BaseUIComponent {
    private static final int FULL = 100;
    private static final int MAX_DIAMETER = 96;
    private static final double RING_WIDTH = 0.36;
    private static final double TURN = Math.PI * 2;
    private static final double PIXEL_CENTER = 0.5;
    private static final int SWATCH = 7;
    private static final int LEGEND_TEXT = 11;
    private MeridianData.Page page;
    private List<Run> runs = List.of();
    private int cachedDiameter = -1;

    /** 圆环一行里同色的一段像素，相对圆环左上角。 */
    private record Run(int left, int right, int row, int color) { }

    /** 圆环的外径、内径（像素）和要分配的元数总量。 */
    private record Ring(double outer, double inner, double total) { }

    ElementCard(MeridianData.Page page) {
        // 尺寸由数据页排布，圆环像素在尺寸或构成变化时重算。
        this.page = page;
        sizing(Sizing.fill(FULL), Sizing.fill(FULL));
    }

    void update(MeridianData.Page next) {
        // 构成变了才作废圆环缓存，元数小幅波动不必每帧重算。
        if (!next.elements().equals(page.elements())) {
            cachedDiameter = -1;
        }
        page = next;
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 左边圆环、中心写主属性，右边图例。
        MeridianTheme.panel(context, new MeridianTheme.Bounds(x, y, width, height));
        int left = x + DataDrawing.PADDING;
        int top = y + DataDrawing.PADDING;
        DataDrawing.heading(context, "元素构成", left, top, width - DataDrawing.PADDING * 2);
        top += DataDrawing.HEADING_HEIGHT;
        int available = Math.min(height - DataDrawing.HEADING_HEIGHT, width / 2) - DataDrawing.PADDING * 2;
        int diameter = Math.max(0, Math.min(MAX_DIAMETER, available));
        ring(context, left, top, diameter);
        legend(context, left + diameter + DataDrawing.PADDING * 2, top);
    }

    private void ring(OwoUIGraphics context, int left, int top, int diameter) {
        // 圆环按缓存的像素段画；中心写元数最多的属性和它的占比。
        if (diameter != cachedDiameter) {
            runs = runs(diameter);
            cachedDiameter = diameter;
        }
        for (Run run : runs) {
            context.fill(left + run.left(), top + run.row(), left + run.right(), top + run.row() + 1, run.color());
        }
        int centerX = left + diameter / 2;
        int centerY = top + diameter / 2;
        var font = DataDrawing.font();
        if (page.elements().isEmpty()) {
            String empty = "无真元";
            DataDrawing.text(context, empty, centerX - font.width(empty) / 2, centerY - font.lineHeight / 2,
                    ThemeTokens.MUTED_TEXT);
            return;
        }
        MeridianData.Share main = page.elements().getFirst();
        String share = DataDrawing.share(main.amount(), total());
        DataDrawing.text(context, main.name(), centerX - font.width(main.name()) / 2, centerY - font.lineHeight,
                ThemeTokens.TEXT);
        DataDrawing.text(context, share, centerX - font.width(share) / 2, centerY + 1, ThemeTokens.MUTED_TEXT);
    }

    private List<Run> runs(int diameter) {
        // 逐行扫描圆环：从正上方顺时针，按累计占比决定每个像素属于哪个属性，同色相邻像素并成一段。
        var result = new ArrayList<Run>();
        double outer = diameter / 2.0;
        var ring = new Ring(outer, outer * (1 - RING_WIDTH), total());
        for (int row = 0; row < diameter; row++) {
            int start = -1;
            int color = 0;
            for (int column = 0; column <= diameter; column++) {
                int pixel = pixelColor(column, row, ring);
                if (pixel != color && start >= 0 && color != 0) {
                    result.add(new Run(start, column, row, color));
                }
                if (pixel != color) {
                    start = column;
                    color = pixel;
                }
            }
        }
        return List.copyOf(result);
    }

    private int pixelColor(int column, int row, Ring ring) {
        // 圆环外的像素记 0；没有真元时整圈画成底槽色。
        double dx = column + PIXEL_CENTER - ring.outer();
        double dy = row + PIXEL_CENTER - ring.outer();
        double radius = Math.hypot(dx, dy);
        if (radius > ring.outer() || radius < ring.inner()) {
            return 0;
        }
        if (ring.total() <= 0) {
            return MeridianTheme.color(ThemeTokens.PROGRESS_TRACK);
        }
        double angle = Math.atan2(dx, -dy);
        if (angle < 0) {
            angle += TURN;
        }
        double target = angle / TURN * ring.total();
        double reached = 0;
        for (MeridianData.Share share : page.elements()) {
            reached += share.amount();
            if (target < reached) {
                return MeridianData.elementColor(share.key());
            }
        }
        return MeridianData.elementColor(page.elements().getLast().key());
    }

    private void legend(OwoUIGraphics context, int left, int top) {
        // 每个属性一行：色块、名称、元数和占比，元数多的在上。
        int right = x + width - DataDrawing.PADDING;
        int row = top;
        double total = total();
        for (MeridianData.Share share : page.elements()) {
            context.fill(left, row + 1, left + SWATCH, row + 1 + SWATCH, MeridianData.elementColor(share.key()));
            DataDrawing.text(context, share.name(), left + LEGEND_TEXT, row, ThemeTokens.TEXT);
            String amount = DataDrawing.number(share.amount()) + DataDrawing.UNIT + "  "
                    + DataDrawing.share(share.amount(), total);
            DataDrawing.textRight(context, amount, right, row, ThemeTokens.MUTED_TEXT);
            row += DataDrawing.LINE_HEIGHT;
        }
    }

    private double total() {
        // 各属性合计，即经脉真元总数。
        return page.elements().stream().mapToDouble(MeridianData.Share::amount).sum();
    }
}
