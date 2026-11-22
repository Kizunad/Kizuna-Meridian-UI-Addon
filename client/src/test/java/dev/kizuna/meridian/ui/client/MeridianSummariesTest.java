package dev.kizuna.meridian.ui.client;

import static org.junit.Assert.*;

import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.ui.client.MeridianData.Account;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 吐纳、经脉、概况三个小窗：标签在左、数值贴右边，文字不出小窗、同一行不相叠。 */
public class MeridianSummariesTest {
    private static final int MERIDIANS = 36;
    private static final int OPENED = 22;
    private static final int LEAKING_INDEX = 20;
    private static final int TICK_PITCH = 3;
    private static final double LARGE = 9_990_000;

    private static void assertRightAligned(SummaryProbe probe, CompactVariant variant, String value) {
        var text = probe.texts().stream().filter(candidate -> candidate.text().equals(value)).findFirst();
        assertTrue(value + " 没画出来", text.isPresent());
        assertEquals(value + " 的右端", variant.width(), text.get().x() + text.get().width());
    }

    @Test
    public void breathPutsLabelsLeftAndReadingsRight() {
        var probe = SummaryProbe.reference();
        MeridianSummaries.breath(probe, MeridianSummaries.BREATH, new BreathSummary(0.94, 1, 0.05));
        assertEquals(List.of("息 余力 | 94%", "气 100% | 纳 0.05"), probe.lines());
        assertRightAligned(probe, MeridianSummaries.BREATH, "94%");
        assertRightAligned(probe, MeridianSummaries.BREATH, "纳 0.05");
        assertEquals(List.of(), probe.problems(MeridianSummaries.BREATH));
        var fill = probe.blocks().getLast();
        assertEquals(probe.color(ThemeTokens.PROGRESS_FILL), fill.color());
        assertEquals(94, fill.right() - fill.left());
        assertEquals(MeridianSummaries.BREATH.width(), probe.blocks().getFirst().right());
    }

    @Test
    public void breathStaysInsideWithTheLongestReadings() {
        for (double stored : List.of(0.0, 9.994, 9.996, 99.96, 9999.4, 9999.6, 123_456.0, LARGE)) {
            var probe = SummaryProbe.reference();
            MeridianSummaries.breath(probe, MeridianSummaries.BREATH, new BreathSummary(1, 1, stored));
            assertEquals("累计 " + stored, List.of(), probe.problems(MeridianSummaries.BREATH));
        }
    }

    private static MeridianData.Row row(int index) {
        var stage = MeridianOpeningStage.UNOPENED;
        if (index < OPENED) {
            stage = MeridianOpeningStage.OPENED;
        }
        var condition = MeridianCondition.INTACT;
        if (index == LEAKING_INDEX) {
            condition = MeridianCondition.LEAKING;
        }
        var state = MeridianState.newBuilder()
                .setId("m" + index)
                .setCondition(condition)
                .setOpening(MeridianOpening.newBuilder().setStage(stage))
                .build();
        var definition = MeridianDefinition.newBuilder().setId("m" + index).build();
        return new MeridianData.Row(definition, state, Account.EMPTY, Account.EMPTY, 0, List.of(), List.of());
    }

    private static MeridianData.Page page(double stored, double capacity) {
        var rows = new ArrayList<MeridianData.Row>();
        for (int index = 0; index < MERIDIANS; index++) {
            rows.add(row(index));
        }
        var lumen = new Account(stored, capacity, 0);
        return new MeridianData.Page(lumen, Account.EMPTY, 0, 0, List.of(), List.of(), rows);
    }

    @Test
    public void channelsShowOneTickPerMeridianAtFullTextSize() {
        var probe = SummaryProbe.reference();
        MeridianSummaries.channels(probe, MeridianSummaries.CHANNELS, page(3.14, 8));
        assertEquals(List.of("脉 开 22/36 | 真元 3.1/8.0"), probe.lines());
        assertRightAligned(probe, MeridianSummaries.CHANNELS, "真元 3.1/8.0");
        assertEquals(List.of(), probe.problems(MeridianSummaries.CHANNELS));
        var backing = probe.blocks().getFirst();
        assertEquals(probe.color(ThemeTokens.COMPACT_TRACK), backing.color());
        assertEquals(MERIDIANS * TICK_PITCH - 1, backing.right());
        var ticks = probe.blocks().subList(1, probe.blocks().size());
        assertEquals(MERIDIANS, ticks.size());
        for (int index = 0; index < MERIDIANS; index++) {
            assertEquals(index * TICK_PITCH, ticks.get(index).left());
        }
        int opened = probe.color(ThemeTokens.PROGRESS_FILL);
        assertEquals(opened, ticks.getFirst().color());
        int damaged = ticks.get(LEAKING_INDEX).color();
        int closed = ticks.getLast().color();
        assertNotEquals("受损的格子和打通的不同色", opened, damaged);
        assertNotEquals("没开的格子和打通的不同色", opened, closed);
        assertNotEquals("受损的格子和没开的不同色", closed, damaged);
        long damagedTicks = ticks.stream().filter(tick -> tick.color() == damaged).count();
        assertEquals(1, damagedTicks);
    }

    @Test
    public void channelsStayInsideWithLargeReserves() {
        for (double capacity : List.of(99.94, 99.96, 9999.4, 9999.6, 99_949.0, LARGE)) {
            var probe = SummaryProbe.reference();
            MeridianSummaries.channels(probe, MeridianSummaries.CHANNELS, page(capacity, capacity));
            assertEquals("容量 " + capacity, List.of(), probe.problems(MeridianSummaries.CHANNELS));
        }
    }

    @Test
    public void compactNumbersShortenAsTheyGrow() {
        assertEquals("0.42", CompactNumbers.fine(0.42));
        assertEquals("9.99", CompactNumbers.fine(9.994));
        assertEquals("10.0", CompactNumbers.fine(9.996));
        assertEquals("3.1", CompactNumbers.coarse(3.14));
        assertEquals("99.9", CompactNumbers.coarse(99.94));
        assertEquals("100", CompactNumbers.coarse(99.96));
        assertEquals("9999", CompactNumbers.whole(9999.4));
        assertEquals("1.0万", CompactNumbers.whole(9999.6));
        assertEquals("9.9万", CompactNumbers.whole(99_400));
        assertEquals("12万", CompactNumbers.whole(123_456));
        assertEquals("999万", CompactNumbers.whole(LARGE));
        assertEquals("—", CompactNumbers.coarse(-1));
        assertEquals("11", CompactNumbers.floored(11.99));
        assertEquals("100%", CompactNumbers.percent(1.2));
        assertEquals("零", CompactNumbers.chinese(0));
        assertEquals("十", CompactNumbers.chinese(10));
        assertEquals("十四", CompactNumbers.chinese(14));
        assertEquals("二十", CompactNumbers.chinese(20));
        assertEquals("九十九", CompactNumbers.chinese(99));
        assertEquals("100", CompactNumbers.chinese(100));
    }
}
