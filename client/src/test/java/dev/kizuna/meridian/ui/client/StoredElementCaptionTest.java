package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import org.junit.Test;
import static org.junit.Assert.*;

/** 纳入量的五行那一行：纳入里有哪一行的真元就写哪一行，按金木水火土的顺序；全是无属性时不写。 */
public class StoredElementCaptionTest {
    private static final double FREE = 0.01;
    private static final double GAS = 0.1;
    /** 按字数量宽度时，正好排得下两样的宽度。 */
    private static final int TWO_WIDE = 22;

    @Test
    public void namesOnlyTheElementsThatArrivedInFiveElementOrder() {
        // 只有火行：写一样。游离、气态两种相态的火合在一起算。
        BreathingView fire = view(qi("meridian:none", 0.30, FREE), qi("meridian:fire", 0.04, FREE),
                qi("meridian:fire", 0.06, GAS));
        assertEquals("其中火行 0.10", StoredElementCaption.of(fire).orElseThrow());

        // 火、金都有：按金木水火土的顺序，不按到达先后。
        BreathingView mixed = view(qi("meridian:fire", 0.05, FREE), qi("meridian:metal", 0.02, FREE));
        assertEquals("其中金行 0.02 · 火行 0.05", StoredElementCaption.of(mixed).orElseThrow());
    }

    @Test
    public void staysSilentWithoutElementQiOrWhenItRoundsToNothing() {
        // 全是无属性、还没有入脉明细、或者本属性少到两位小数下是 0.00，都不写这一行。
        assertTrue(StoredElementCaption.of(view(qi("meridian:none", 0.3, FREE))).isEmpty());
        assertTrue(StoredElementCaption.of(BreathingView.getDefaultInstance()).isEmpty());
        assertTrue(StoredElementCaption.of(view(qi("meridian:fire", 0.004, FREE))).isEmpty());
        // 一样够写、另一样不够：只写够的那一样。
        BreathingView one = view(qi("meridian:fire", 0.004, FREE), qi("meridian:earth", 0.03, FREE));
        assertEquals("其中土行 0.03", StoredElementCaption.of(one).orElseThrow());
    }

    @Test
    public void wrapsInsteadOfCuttingWhenAllFiveElementsArrive() {
        // 五行都有、一行只排得下两样：折成三行，五样一样不少（原来整行截断，火行被截掉看不见）。
        BreathingView all = view(qi("meridian:metal", 2.92, GAS), qi("meridian:wood", 0.56, GAS),
                qi("meridian:water", 0.38, GAS), qi("meridian:fire", 18.57, GAS), qi("meridian:earth", 7.39, GAS));
        assertEquals(java.util.List.of("其中金行 2.92 · 木行 0.56", "水行 0.38 · 火行 18.57", "土行 7.39"),
                StoredElementCaption.lines(all, String::length, TWO_WIDE));
        // 够宽就还是一行；一样也没有就一行都不写。
        assertEquals(java.util.List.of(StoredElementCaption.of(all).orElseThrow()),
                StoredElementCaption.lines(all, String::length, 200));
        assertTrue(StoredElementCaption.lines(view(qi("meridian:none", 0.3, FREE)), String::length, TWO_WIDE)
                .isEmpty());
        // 窄到一样都嫌宽：每样自己占一行，不丢。
        assertEquals(5, StoredElementCaption.lines(all, String::length, 1).size());
    }

    private static BreathingView view(Qi... entries) {
        // 只填入脉明细。
        QiInventory stored = QiInventory.newBuilder().addAllEntries(java.util.List.of(entries)).build();
        return BreathingView.newBuilder().setStoredByKind(stored).build();
    }

    private static Qi qi(String attribute, double amount, double density) {
        // 一行真元。
        return Qi.newBuilder()
                .setAttribute(attribute)
                .setDensity(density)
                .setAmount(amount)
                .build();
    }
}
