package dev.kizuna.meridian.ui.client;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/** 数据页的换算：总数、可装、占用、密度、相态与属性构成都从快照派生，和检视窗口同一套函数。 */
public class MeridianDataTest {
    private static final double DELTA = 1e-9;
    private static final double FREE = 0.01;
    private static final double GAS = 0.1;
    private static final String METAL = "meridian:metal";
    private static final String FIRE = "meridian:fire";
    private static final String LANG = "/assets/meridian_ui/lang/";

    @Test
    public void totalsAddBothAccountsAndFillFollowsVolume() {
        // 整体卡：两条经脉的元数、可装、占用和已开拓条数。
        QiInventory lungLumen = inventory(qi(MeridianData.NONE, FREE, 3), qi(METAL, GAS, 2));
        QiInventory lungWall = inventory(qi(MeridianData.NONE, FREE, 1));
        QiInventory stomachLumen = inventory(qi(FIRE, GAS, 4));
        BodySnapshot body = body(List.of(definition("lung", 900, 300), definition("stomach", 600, 200)),
                List.of(state("lung", lungLumen, lungWall, MeridianOpeningStage.OPENED),
                        state("stomach", stomachLumen, QiInventory.getDefaultInstance(),
                                MeridianOpeningStage.IN_PROGRESS)));

        MeridianData.Page page = MeridianData.of(body);

        assertEquals(9, page.lumen().amount(), DELTA);
        assertEquals(1, page.wall().amount(), DELTA);
        assertEquals(10, page.meridianTotal(), DELTA);
        // 可装是每条经脉按自己的平均密度换算后相加，不是全身平均密度乘总体积。
        double capacity = QiDisplay.displayedCapacity(900, lungLumen) + QiDisplay.displayedCapacity(600, stomachLumen);
        assertEquals(capacity, page.lumen().capacity(), DELTA);
        // 整体占用按体积：已占体积之和除以内腔体积之和。
        double occupied = QiDisplay.volume(lungLumen) + QiDisplay.volume(stomachLumen);
        assertEquals(occupied / 1500, page.lumen().fill(), DELTA);
        assertEquals(QiDisplay.volume(lungWall) / 500, page.wall().fill(), DELTA);
        assertEquals(1, page.opened());
    }

    @Test
    public void rowDensityIsLumenAmountOverOccupiedVolume() {
        // 单行：平均密度和内腔占用与检视窗口同口径。
        QiInventory lumen = inventory(qi(MeridianData.NONE, FREE, 3), qi(METAL, GAS, 2));
        BodySnapshot body = body(List.of(definition("lung", 900, 300)),
                List.of(state("lung", lumen, QiInventory.getDefaultInstance(), MeridianOpeningStage.OPENED)));

        MeridianData.Row row = MeridianData.of(body).rows().getFirst();

        assertEquals(5 / QiDisplay.volume(lumen), row.density(), DELTA);
        assertEquals(QiDisplay.storageFraction(lumen, 900), row.lumen().fill(), DELTA);
        // 空外壁：没有元数、没有主属性以外的构成。
        assertEquals(0, row.wall().amount(), DELTA);
    }

    @Test
    public void emptyMeridianHasNoDensityAndNoMainElement() {
        // 空经脉：密度记 0、主属性写破折号、没有构成项。
        BodySnapshot body = body(List.of(definition("lung", 900, 300)), List.of(state("lung",
                QiInventory.getDefaultInstance(), QiInventory.getDefaultInstance(), MeridianOpeningStage.UNOPENED)));

        MeridianData.Page page = MeridianData.of(body);

        assertEquals(0, page.rows().getFirst().density(), DELTA);
        assertEquals("—", page.rows().getFirst().mainElement());
        assertTrue(page.phases().isEmpty());
        assertTrue(page.elements().isEmpty());
        assertEquals(0, page.opened());
    }

    @Test
    public void rowsFollowStructureOrderAndSkipMissingStates() {
        // 构型里有三条，状态只同步了两条，而且顺序和构型相反。
        BodySnapshot body = body(List.of(definition("a", 100, 10), definition("b", 100, 10), definition("c", 100, 10)),
                List.of(state("c", QiInventory.getDefaultInstance(), QiInventory.getDefaultInstance(),
                                MeridianOpeningStage.OPENED),
                        state("a", QiInventory.getDefaultInstance(), QiInventory.getDefaultInstance(),
                                MeridianOpeningStage.OPENED)));

        List<String> ids = MeridianData.of(body).rows().stream().map(row -> row.definition().getId()).toList();

        assertEquals(List.of("a", "c"), ids);
    }

    @Test
    public void phasesRunSparseToDenseAndElementsLargestFirst() {
        // 内腔、外壁的真元合在一起分；同量的两种属性按 ID 排，顺序稳定。
        QiInventory lumen = inventory(qi(METAL, GAS, 2), qi(MeridianData.NONE, FREE, 5));
        QiInventory wall = inventory(qi(FIRE, FREE, 2));
        BodySnapshot body = body(List.of(definition("lung", 900, 300)),
                List.of(state("lung", lumen, wall, MeridianOpeningStage.OPENED)));

        MeridianData.Page page = MeridianData.of(body);

        assertEquals(List.of(QiPhase.FREE.name(), QiPhase.GAS.name()),
                page.phases().stream().map(MeridianData.Share::key).toList());
        assertEquals(7, page.phases().getFirst().amount(), DELTA);
        assertEquals(List.of(MeridianData.NONE, FIRE, METAL),
                page.elements().stream().map(MeridianData.Share::key).toList());
        assertTrue(page.elements().stream().allMatch(share -> share.kind() == MeridianData.Kind.ELEMENT));
        assertEquals(page.elements(), page.rows().getFirst().elements());
    }

    @Test
    public void organsCountBufferAndProcessInputAndTransitsCountCargo() {
        // 器官缓存含加工原料，在途按货物算。
        OrganState organ = OrganState.newBuilder()
                .setId("stomach")
                .setBuffer(inventory(qi(MeridianData.NONE, FREE, 1.5)))
                .setProcess(OrganProcess.newBuilder().setInput(inventory(qi(MeridianData.NONE, FREE, 0.5))))
                .build();
        QiTransit transit = QiTransit.newBuilder()
                .setId("t")
                .setCargo(inventory(qi(MeridianData.NONE, FREE, 0.25)))
                .build();
        BodySnapshot body = body(List.of(), List.of()).toBuilder()
                .addOrganStates(organ)
                .addTransits(transit)
                .build();

        MeridianData.Page page = MeridianData.of(body);

        assertEquals(2, page.organs(), DELTA);
        assertEquals(0.25, page.transits(), DELTA);
        // 器官和在途不算进经脉真元。
        assertEquals(0, page.meridianTotal(), DELTA);
    }

    @Test
    public void languagesShareKeysAndChineseNamesMatchOtherWindows() {
        // 两份语言表键一致，中文叫法与其他窗口一致。
        JsonObject chinese = language("zh_cn");
        JsonObject english = language("en_us");
        assertEquals(chinese.keySet(), english.keySet());
        // 状态、开拓、相态的中文叫法与检视、吐纳窗口一致。
        for (MeridianCondition condition : List.of(MeridianCondition.INTACT, MeridianCondition.LEAKING,
                MeridianCondition.SEVERED)) {
            assertEquals(BodyInformation.conditionName(condition), name(chinese, "condition.", condition.name()));
        }
        for (MeridianOpeningStage stage : List.of(MeridianOpeningStage.UNOPENED, MeridianOpeningStage.IN_PROGRESS,
                MeridianOpeningStage.OPENED)) {
            assertEquals(BodyInformation.stageName(stage), name(chinese, "stage.", stage.name()));
        }
        for (QiPhase phase : List.of(QiPhase.FREE, QiPhase.GAS, QiPhase.LIQUID, QiPhase.SOLID, QiPhase.ORDERED,
                QiPhase.COHERENT)) {
            assertEquals(StoredPhaseCaption.name(phase), name(chinese, "phase.", phase.name()));
        }
        assertEquals(BodyInformation.stageName(MeridianOpeningStage.UNRECOGNIZED),
                chinese.get(DataText.PREFIX + "unknown").getAsString());
    }

    private static String name(JsonObject language, String group, String value) {
        // 枚举名小写作键尾，和 DataText 的取法一致。
        return language.get(DataText.PREFIX + group + value.toLowerCase(Locale.ROOT)).getAsString();
    }

    private static JsonObject language(String code) {
        // 从测试类路径读打进 JAR 的语言表。
        var stream = MeridianDataTest.class.getResourceAsStream(LANG + code + ".json");
        assertNotNull(code, stream);
        return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static BodySnapshot body(List<MeridianDefinition> definitions, List<MeridianState> states) {
        // 只填数据页读到的构型和经脉状态。
        return BodySnapshot.newBuilder()
                .setStructure(BodyStructure.newBuilder().addAllMeridians(definitions))
                .addAllMeridianStates(states)
                .build();
    }

    private static MeridianDefinition definition(String id, double lumenVolume, double wallVolume) {
        // 一条经脉的两个体积。
        return MeridianDefinition.newBuilder()
                .setId(id)
                .setDisplayName(id)
                .setLumenVolume(lumenVolume)
                .setWallVolume(wallVolume)
                .build();
    }

    private static MeridianState state(String id, QiInventory lumen, QiInventory wall, MeridianOpeningStage stage) {
        // 一条经脉的两个账户和开拓阶段。
        return MeridianState.newBuilder()
                .setId(id)
                .setLumenQi(lumen)
                .setWallQi(wall)
                .setOpening(MeridianOpening.newBuilder().setStage(stage))
                .build();
    }

    private static QiInventory inventory(Qi... entries) {
        // 几笔真元组成一个账户。
        return QiInventory.newBuilder().addAllEntries(List.of(entries)).build();
    }

    private static Qi qi(String attribute, double density, double amount) {
        // 一笔真元。
        return Qi.newBuilder()
                .setAttribute(attribute)
                .setDensity(density)
                .setAmount(amount)
                .build();
    }
}
