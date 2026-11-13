package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;

/** 明细表的表头；列宽按表宽比例分，行与表头共用同一组列边界。 */
final class MeridianDataHeader extends BaseUIComponent {
    static final int HEIGHT = 14;
    static final int CELL_GAP = 4;
    static final int NAME = 0;
    static final int OPENING = 1;
    static final int LUMEN = 2;
    static final int WALL = 3;
    static final int DENSITY = 4;
    static final int MAIN = 5;
    private static final int FULL = 100;
    private static final int TEXT_TOP = 3;
    private static final List<String> TITLES = List.of("经脉", "开拓", "内腔", "外壁", "平均密度", "主属性");
    private static final double[] STARTS = {0, 0.19, 0.31, 0.53, 0.75, 0.88, 1};

    MeridianDataHeader() {
        // 宽度随数据页，高度固定一行字。
        sizing(Sizing.fill(FULL), Sizing.fixed(HEIGHT));
    }

    static int left(int tableX, int tableWidth, int column) {
        // 第 column 列的左边界，含左侧留白。
        return tableX + DataDrawing.PADDING + (int) Math.round((tableWidth - DataDrawing.PADDING * 2) * STARTS[column]);
    }

    static int width(int tableWidth, int column) {
        // 第 column 列可写字的宽度，右侧留出列间距。
        int inner = tableWidth - DataDrawing.PADDING * 2;
        double share = STARTS[column + 1] - STARTS[column];
        return (int) Math.round(inner * share) - CELL_GAP;
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 表头用标题栏底色，列名淡色。
        context.fill(x, y, x + width, y + height, MeridianTheme.color(ThemeTokens.HEADER));
        context.fill(x, y + height - 1, x + width, y + height, MeridianTheme.color(ThemeTokens.BORDER));
        for (int column = 0; column < TITLES.size(); column++) {
            DataDrawing.text(context, TITLES.get(column), left(x, width, column), y + TEXT_TOP,
                    ThemeTokens.MUTED_TEXT);
        }
    }
}
