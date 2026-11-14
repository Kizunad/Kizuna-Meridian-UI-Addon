package dev.kizuna.meridian.ui.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class CodexLinesTest {
    private static final int THREE = 3;
    private static final int FOUR = 4;
    private static final int FIVE = 5;

    @Test
    public void punctuationNeverStartsALine() {
        // 一行四个字时「。」本该落在第二行行首，改为把前一个字带下去。
        assertEquals(List.of("天下拳", "脚。好"), CodexLines.paragraph("天下拳脚。好", FOUR, String::length));
    }

    @Test
    public void closingMarksAfterPunctuationMoveTogether() {
        // 「。」」两个都不许打头，连同前一个字一起挪下去。
        assertEquals(List.of("说道", "好。」走"), CodexLines.paragraph("说道好。」走", FOUR, String::length));
    }

    @Test
    public void openingQuoteNeverEndsALine() {
        // 开引号落在行尾时跟后面的字一起换行。
        assertEquals(List.of("他说", "「好", "啊」"), CodexLines.paragraph("他说「好啊」", THREE, String::length));
    }

    @Test
    public void latinWordsStayWholeAndSpacesAreDroppedAtBreaks() {
        // 英文按词折，换行处的空格丢掉，逗号跟着前一个词走。
        assertEquals(List.of("bow", "and,", "arrow"), CodexLines.paragraph("bow and, arrow", FIVE, String::length));
    }

    @Test
    public void wrapKeepsExistingNewlinesAndEveryLineFits() {
        // 原有换行保留，每行都不超宽。
        String wrapped = CodexLines.wrap("一二三四五六\n七八", FOUR, String::length);
        assertEquals("一二三四\n五六\n七八", wrapped);
    }
}
