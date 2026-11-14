package dev.kizuna.meridian.ui.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 功法谱正文折行：中文按字折、守避头尾（句读和闭括号不落行首，开括号不留行尾），英文按词折。
 *
 * <p>折好的每一行都不超过给定宽度，交给标签时原样显示，不会被标签再折一次。</p>
 */
final class CodexLines {
    private static final String NO_LINE_START = "，。、；：？！）」』》〉】〕”’…·—～,.;:?!)]}%";
    private static final String NO_LINE_END = "（「『《〈【〔“‘([{";

    private CodexLines() {
        // 只有静态折行方法。
    }

    static String wrap(String text, int width, ToIntFunction<String> measure) {
        // 原文里的换行照留，每段各自折。
        var lines = new ArrayList<String>();
        for (String paragraph : text.split("\n", -1)) {
            lines.addAll(paragraph(paragraph, width, measure));
        }
        return String.join("\n", lines);
    }

    static List<String> paragraph(String text, int width, ToIntFunction<String> measure) {
        // 一个字一个字往行里放，放不下就换行；换行处按避头尾挪字。
        var lines = new ArrayList<String>();
        var line = new ArrayList<String>();
        for (String token : tokens(text)) {
            if (line.isEmpty() || measure.applyAsInt(String.join("", line) + token) <= width) {
                line.add(token);
                continue;
            }
            var next = new ArrayList<String>();
            if (!token.isBlank()) {
                next.add(token);
            }
            carry(line, next);
            lines.add(String.join("", line).stripTrailing());
            line = next;
        }
        lines.add(String.join("", line).stripTrailing());
        return lines;
    }

    private static void carry(List<String> line, List<String> next) {
        // 下一行开头是句读、或这一行结尾是开括号时，把这一行最后一个字挪下去，直到合规或这一行只剩一个字。
        while (line.size() > 1 && (startsBadly(next) || endsBadly(line))) {
            next.addFirst(line.removeLast());
        }
    }

    private static boolean startsBadly(List<String> next) {
        // 下一行第一个字是不许出现在行首的标点。
        return !next.isEmpty() && NO_LINE_START.indexOf(next.getFirst().charAt(0)) >= 0;
    }

    private static boolean endsBadly(List<String> line) {
        // 这一行最后一个字是不许留在行尾的开括号或开引号。
        String last = line.getLast();
        return NO_LINE_END.indexOf(last.charAt(last.length() - 1)) >= 0;
    }

    static List<String> tokens(String text) {
        // 拉丁字母和数字连成一个词不拆；空格单独一个，换行时丢掉；其余每个字自成一个。
        var tokens = new ArrayList<String>();
        var word = new StringBuilder();
        text.codePoints().forEach(point -> {
            if (wordPart(point)) {
                word.appendCodePoint(point);
                return;
            }
            if (!word.isEmpty()) {
                tokens.add(word.toString());
                word.setLength(0);
            }
            tokens.add(Character.toString(point));
        });
        if (!word.isEmpty()) {
            tokens.add(word.toString());
        }
        return tokens;
    }

    private static boolean wordPart(int point) {
        // 数字和拉丁字母算词的一部分，撇号连字符也不拆开（don't、two-handed）。
        if (Character.isDigit(point) || point == '\'' || point == '-') {
            return true;
        }
        return Character.isLetter(point) && Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN;
    }
}
