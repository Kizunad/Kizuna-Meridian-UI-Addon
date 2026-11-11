package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import org.junit.Test;
import static org.junit.Assert.*;

/** 纳入量的相态那一行：真炁吐纳有气态时分开写游离与气态，全是游离态时不写。 */
public class StoredPhaseCaptionTest {
    private static final double FREE = 0.01;
    private static final double GAS = 0.1;

    @Test
    public void splitsFreeAndGasOnlyWhenGasArrived() {
        BreathingView mixed = view(qi(0.125, FREE), qi(0.25, GAS));
        assertEquals("游离态 0.13 · 气态 0.25", StoredPhaseCaption.of(mixed).orElseThrow());

        // 份额为 1 时只有气态，也要写出游离为零。
        assertEquals("游离态 0.00 · 气态 0.40", StoredPhaseCaption.of(view(qi(0.4, GAS))).orElseThrow());

        assertTrue(StoredPhaseCaption.of(view(qi(0.3, FREE))).isEmpty());
        // 还没有入脉明细时也不写这一行。
        assertTrue(StoredPhaseCaption.of(BreathingView.getDefaultInstance()).isEmpty());
    }

    private static BreathingView view(Qi... entries) {
        // 只填入脉明细。
        QiInventory stored = QiInventory.newBuilder().addAllEntries(java.util.List.of(entries)).build();
        return BreathingView.newBuilder().setStoredByKind(stored).build();
    }

    private static Qi qi(double amount, double density) {
        // 无属性真元一行。
        return Qi.newBuilder()
                .setAttribute("meridian:none")
                .setDensity(density)
                .setAmount(amount)
                .build();
    }
}
