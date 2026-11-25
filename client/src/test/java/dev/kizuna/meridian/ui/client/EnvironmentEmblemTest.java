package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 灵气徽记：画哪一行、两行接近时各半、浓淡怎么换算、悬浮和明细的文字。 */
public class EnvironmentEmblemTest {
    private static final double FREE = 0.01;
    private static final double TOLERANCE = 1e-9;

    @Test
    public void picksTheLargestElementAndSplitsWhenTheSecondIsClose() {
        // 平原偏木，只画木；岩地金 45、土 35 不到八成，只画金；金 45、土 36 正好八成，左金右土。
        assertEquals(List.of("meridian:wood"), EnvironmentEmblem.leading(environment(0.8, 10, 45, 15, 10, 20, 0)));
        assertEquals(List.of("meridian:metal"), EnvironmentEmblem.leading(environment(0.4, 45, 5, 10, 5, 35, 0)));
        assertEquals(List.of("meridian:metal", "meridian:earth"),
                EnvironmentEmblem.leading(environment(0.4, 45, 5, 10, 5, 36, 0)));
        // 只有无属性时画淡雾；什么都没有时不指定，由画的时候退到淡雾。
        assertEquals(List.of("meridian:none"), EnvironmentEmblem.leading(environment(0.3, 0, 0, 0, 0, 0, 5)));
        assertTrue(EnvironmentEmblem.leading(environment(0.3, 0, 0, 0, 0, 0, 0)).isEmpty());
    }

    @Test
    public void glowFollowsConcentrationContinuouslyUpToTheCap() {
        // 光晕、明暗、光点个数都用这个 0 到 1 的数：每格 2 元封顶，中间按比例。
        assertEquals(0, EnvironmentEmblem.glow(environment(0, 1, 1, 1, 1, 1, 0)), TOLERANCE);
        assertEquals(0.4, EnvironmentEmblem.glow(environment(0.8, 1, 1, 1, 1, 1, 0)), TOLERANCE);
        assertEquals(1, EnvironmentEmblem.glow(environment(EnvironmentEmblem.FULL_GLOW, 1, 1, 1, 1, 1, 0)), TOLERANCE);
        assertEquals(1, EnvironmentEmblem.glow(environment(5, 1, 1, 1, 1, 1, 0)), TOLERANCE);
    }

    @Test
    public void hoverNamesTheLevelBiasAndConcentration() {
        // 档位写在悬浮里：不到 0.5 稀薄，1.2 起充盈，分界值归上面那档。
        assertEquals("稀薄·偏土，约 0.20 元/格",
                EnvironmentEmblem.brief(environment(0.2, 10, 3, 5, 22, 60, 0), WindowText.CLASSIC));
        assertEquals("寻常·偏木，约 0.80 元/格",
                EnvironmentEmblem.brief(environment(0.8, 10, 45, 15, 10, 20, 0), WindowText.CLASSIC));
        assertEquals("充盈·偏木，约 1.90 元/格",
                EnvironmentEmblem.brief(environment(1.9, 5, 65, 20, 5, 5, 0), WindowText.CLASSIC));
        assertEquals("寻常·偏水，约 0.50 元/格",
                EnvironmentEmblem.brief(environment(EnvironmentEmblem.THIN_BELOW, 10, 15, 55, 5, 15, 0),
                        WindowText.CLASSIC));
        assertEquals("充盈·偏水，约 1.20 元/格",
                EnvironmentEmblem.brief(environment(EnvironmentEmblem.RICH_FROM, 10, 15, 55, 5, 15, 0),
                        WindowText.CLASSIC));
        assertEquals("寻常·偏金土，约 0.60 元/格",
                EnvironmentEmblem.brief(environment(0.6, 45, 5, 10, 5, 36, 0), WindowText.CLASSIC));
        assertEquals("此地没有灵气可纳",
                EnvironmentEmblem.brief(GatheringEnvironment.getDefaultInstance(), WindowText.CLASSIC));
    }

    @Test
    public void detailsListEveryKindTotalConcentrationSupplyAndTheLevelBounds() {
        var details = EnvironmentEmblem.details(environment(0.8, 10, 45, 15, 10, 20, 0), WindowText.CLASSIC);
        assertEquals(List.of("此地灵气：寻常", "金 10 元（10%）", "木 45 元（45%）", "水 15 元（15%）", "火 10 元（10%）",
                "土 20 元（20%）", "无属性 0 元（0%）", "总量 100 元", "浓度 0.80 元/格",
                "每秒最多纳 0.80 元，在场的人共用", "分档：不到 0.5 稀薄，1.2 以上充盈"), details);
        assertEquals(1, EnvironmentEmblem.details(GatheringEnvironment.getDefaultInstance(),
                        WindowText.CLASSIC).size());
    }

    static GatheringEnvironment environment(double concentration, double metal, double wood, double water,
                                            double fire, double earth, double none) {
        // 各行一条游离态明细，数量就是权重，零的不写；吸收上限在数值上等于浓度。
        var qi = QiInventory.newBuilder();
        add(qi, "meridian:metal", metal);
        add(qi, "meridian:wood", wood);
        add(qi, "meridian:water", water);
        add(qi, "meridian:fire", fire);
        add(qi, "meridian:earth", earth);
        add(qi, "meridian:none", none);
        return GatheringEnvironment.newBuilder().setAvailable(true).setQi(qi)
                .setConcentrationPerBlock(concentration).setSupplyPerSecond(concentration).build();
    }

    private static void add(QiInventory.Builder qi, String attribute, double amount) {
        if (amount > 0) {
            qi.addEntries(Qi.newBuilder().setAttribute(attribute).setDensity(FREE).setAmount(amount));
        }
    }
}
