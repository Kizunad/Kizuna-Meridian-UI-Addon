package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import org.junit.Test;
import static org.junit.Assert.*;

/** 吐纳窗口的灵气读数：浓淡分三档，偏向取最多的一行，不在主世界时说没有。 */
public class EnvironmentCaptionTest {
    private static final double FREE = 0.01;

    @Test
    public void namesLevelAndDominantElementFromTheServerView() {
        // 沙漠偏土而稀薄，平原偏木而寻常，丛林偏木而充盈；边界值归到上面那档。
        assertEquals("此地灵气：稀薄，偏土", EnvironmentCaption.of(environment(0.2, 10, 3, 5, 22, 60)));
        assertEquals("此地灵气：寻常，偏木", EnvironmentCaption.of(environment(0.8, 10, 45, 15, 10, 20)));
        assertEquals("此地灵气：充盈，偏木", EnvironmentCaption.of(environment(1.9, 5, 65, 20, 5, 5)));
        assertEquals("此地灵气：寻常，偏水", EnvironmentCaption.of(environment(EnvironmentCaption.THIN_BELOW, 10, 15, 55, 5, 15)));
        assertEquals("此地灵气：充盈，偏水", EnvironmentCaption.of(environment(EnvironmentCaption.RICH_FROM, 10, 15, 55, 5, 15)));
    }

    @Test
    public void detailsGiveTheNumbersAndUnavailablePlacesSaySo() {
        var details = EnvironmentCaption.details(environment(0.8, 10, 45, 15, 10, 20));
        assertEquals("浓度 0.80 元/格", details.get(0));
        assertEquals("木 45% · 土 20% · 水 15% · 金 10% · 火 10%", details.get(1));
        assertTrue(details.get(2).startsWith("这一带每秒最多纳 0.80 元"));

        var nowhere = GatheringEnvironment.getDefaultInstance();
        assertEquals("此地没有灵气可纳", EnvironmentCaption.of(nowhere));
        assertEquals(1, EnvironmentCaption.details(nowhere).size());
    }

    private static GatheringEnvironment environment(double concentration, double metal, double wood, double water,
                                                    double fire, double earth) {
        // 五行各一条游离态明细，数量就是权重；吸收上限在数值上等于浓度。
        var qi = QiInventory.newBuilder()
                .addEntries(qi("meridian:metal", metal)).addEntries(qi("meridian:wood", wood))
                .addEntries(qi("meridian:water", water)).addEntries(qi("meridian:fire", fire))
                .addEntries(qi("meridian:earth", earth));
        return GatheringEnvironment.newBuilder().setAvailable(true).setQi(qi)
                .setConcentrationPerBlock(concentration).setSupplyPerSecond(concentration).build();
    }

    private static Qi qi(String attribute, double amount) {
        return Qi.newBuilder().setAttribute(attribute).setDensity(FREE).setAmount(amount).build();
    }
}
