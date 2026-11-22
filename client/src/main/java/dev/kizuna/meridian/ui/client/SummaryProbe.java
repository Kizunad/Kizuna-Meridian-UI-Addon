package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.CompactVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** 不出图的画布：记下摘要画了哪些文字和色块，用来核对有没有画出小窗、同一行的文字有没有叠在一起。 */
public final class SummaryProbe implements SummaryCanvas {
    /** 一段文字和它占的横向范围。 */
    public record Text(int x, int y, String text, int width, int color) {
        int right() {
            return x + width;
        }
    }

    /** 一个色块。 */
    public record Block(int left, int top, int right, int bottom, int color) { }

    private static final int FULL_WIDTH_START = 0x2E80;
    private static final int FULL_WIDTH = 9;
    private static final int LATIN_WIDTH = 6;
    private static final int SPACE_WIDTH = 4;
    private static final int DOT_WIDTH = 2;
    private static final String GAP = " | ";
    private final ToIntFunction<String> font;
    private final List<Text> texts = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();

    public SummaryProbe(ToIntFunction<String> font) {
        this.font = font;
    }

    /** 按游戏默认字体的字宽表量宽的画布，不需要启动客户端。 */
    public static SummaryProbe reference() {
        return new SummaryProbe(SummaryProbe::referenceWidth);
    }

    /** 游戏默认字体里一段文字的宽度：汉字和破折号 9，数字和拉丁字母 6，空格 4，小数点、冒号和间隔号 2。 */
    public static int referenceWidth(String text) {
        return text.codePoints().map(SummaryProbe::referenceAdvance).sum();
    }

    private static int referenceAdvance(int codePoint) {
        if (codePoint >= FULL_WIDTH_START || codePoint == '—') {
            return FULL_WIDTH;
        }
        if (codePoint == ' ') {
            return SPACE_WIDTH;
        }
        if (codePoint == '.' || codePoint == ':' || codePoint == '·') {
            return DOT_WIDTH;
        }
        return LATIN_WIDTH;
    }

    @Override
    public int width(String text) {
        return font.applyAsInt(text);
    }

    @Override
    public int color(String token) {
        // 不同令牌得到不同的颜色，不依赖主题。
        return token.hashCode();
    }

    @Override
    public void text(int x, int y, String text, int color) {
        texts.add(new Text(x, y, text, width(text), color));
    }

    @Override
    public void fill(int left, int top, int right, int bottom, int color) {
        blocks.add(new Block(left, top, right, bottom, color));
    }

    public List<Text> texts() {
        return List.copyOf(texts);
    }

    public List<Block> blocks() {
        return List.copyOf(blocks);
    }

    /** 每行从左到右的文字，行按从上到下排；相隔不到一个空格的几段连写，隔得更开的用「 | 」分开。 */
    public List<String> lines() {
        var rows = texts.stream().map(Text::y).distinct().sorted().toList();
        var lines = new ArrayList<String>();
        for (int row : rows) {
            var ordered = texts.stream().filter(text -> text.y() == row)
                    .sorted((a, b) -> Integer.compare(a.x(), b.x()))
                    .toList();
            var line = new StringBuilder();
            int end = ordered.getFirst().x();
            for (Text text : ordered) {
                if (text.x() - end >= SPACE_WIDTH) {
                    line.append(GAP);
                }
                line.append(text.text());
                end = text.right();
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** 画到小窗外面的文字和色块、以及同一行里互相压住的文字；全都合规时为空。 */
    public List<String> problems(CompactVariant variant) {
        var problems = new ArrayList<String>();
        for (Text text : texts) {
            boolean inside = text.x() >= 0 && text.right() <= variant.width()
                    && text.y() >= 0 && text.y() + CompactSummary.LINE <= variant.height();
            if (!inside) {
                problems.add("文字出界：" + text);
            }
            for (Text other : texts) {
                boolean sameLine = other != text && other.y() == text.y();
                if (sameLine && other.x() >= text.x() && other.x() < text.right()) {
                    problems.add("文字相叠：" + text + " 和 " + other);
                }
            }
        }
        for (Block block : blocks) {
            boolean inside = block.left() >= 0 && block.right() <= variant.width()
                    && block.top() >= 0 && block.bottom() <= variant.height();
            if (!inside) {
                problems.add("色块出界：" + block);
            }
        }
        return problems;
    }
}
