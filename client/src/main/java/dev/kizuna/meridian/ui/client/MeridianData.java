package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据页的只读投影：全身经脉总量、相态和属性构成、每条经脉一行，全部从同一份身体快照派生。 */
final class MeridianData {
    static final String NONE = "meridian:none";
    private static final int OPAQUE = 0xFF000000;

    /** 一个账户（内腔或外壁）的元数、按实际密度换算的可装元数和体积占用比例。 */
    record Account(double amount, double capacity, double fill) {
        static final Account EMPTY = new Account(0, 0, 0);

        Account plus(Account other) {
            // 合计时元数和容量直接相加，占用比例由调用方按总体积另算。
            return new Account(amount + other.amount, capacity + other.capacity, 0);
        }
    }

    /** 构成项是相态还是属性；两者的键和叫法各走一套。 */
    enum Kind { PHASE, ELEMENT }

    /** 某一相态（键为相态名）或属性（键为属性 ID）在经脉里合计的元数。 */
    record Share(Kind kind, String key, double amount) {
        String name() {
            // 界面叫法随当前语言。
            return DataText.share(this);
        }
    }

    /** 明细表的一行；状态缺失的经脉不进表，交给检视窗口报缺失。 */
    record Row(MeridianDefinition definition, MeridianState state, Account lumen, Account wall,
               double density, List<Share> elements, List<Share> phases) {
        String mainElement() {
            // 元数最多的属性；空经脉没有主属性。
            if (elements.isEmpty()) {
                return "—";
            }
            return elements.getFirst().name();
        }
    }

    /** 整页数据；行顺序沿用构型里的经脉顺序。 */
    record Page(Account lumen, Account wall, double organs, double transits,
                List<Share> phases, List<Share> elements, List<Row> rows) {
        double meridianTotal() {
            // 经脉真元 = 全身内腔 + 外壁，不含器官缓存和在途。
            return lumen.amount() + wall.amount();
        }

        long opened() {
            // 已开拓的经脉条数；为 0 时数据页给出开拓提示。
            return rows.stream()
                    .filter(row -> row.state().getOpening().getStage() == MeridianOpeningStage.OPENED)
                    .count();
        }
    }

    private MeridianData() {
        // 纯函数集合，不缓存身体。
    }

    static Page of(BodySnapshot body) {
        // 逐条经脉投影后再汇总；容量按每条经脉自己的平均密度换算，占用比例按体积算。
        var rows = new ArrayList<Row>();
        double lumenVolume = 0;
        double lumenOccupied = 0;
        double wallVolume = 0;
        double wallOccupied = 0;
        var lumen = Account.EMPTY;
        var wall = Account.EMPTY;
        var all = new ArrayList<Qi>();
        for (MeridianDefinition definition : body.getStructure().getMeridiansList()) {
            MeridianState state = state(body, definition.getId());
            if (state == null) {
                continue;
            }
            Row row = row(definition, state);
            rows.add(row);
            lumen = lumen.plus(row.lumen());
            wall = wall.plus(row.wall());
            lumenVolume += definition.getLumenVolume();
            lumenOccupied += QiDisplay.volume(state.getLumenQi());
            wallVolume += definition.getWallVolume();
            wallOccupied += QiDisplay.volume(state.getWallQi());
            all.addAll(state.getLumenQi().getEntriesList());
            all.addAll(state.getWallQi().getEntriesList());
        }
        var lumenTotal = new Account(lumen.amount(), lumen.capacity(), QiDisplay.ratio(lumenOccupied, lumenVolume));
        var wallTotal = new Account(wall.amount(), wall.capacity(), QiDisplay.ratio(wallOccupied, wallVolume));
        return new Page(lumenTotal, wallTotal, organs(body), transits(body), phases(all), elements(all),
                List.copyOf(rows));
    }

    private static Row row(MeridianDefinition definition, MeridianState state) {
        // 一条经脉的内腔、外壁两个账户，以及两者合起来的相态、属性构成。
        Account lumen = account(state.getLumenQi(), definition.getLumenVolume());
        Account wall = account(state.getWallQi(), definition.getWallVolume());
        var entries = new ArrayList<Qi>(state.getLumenQi().getEntriesList());
        entries.addAll(state.getWallQi().getEntriesList());
        double volume = QiDisplay.volume(state.getLumenQi());
        double density = 0;
        if (volume > 0) {
            density = lumen.amount() / volume;
        }
        return new Row(definition, state, lumen, wall, density, elements(entries), phases(entries));
    }

    private static Account account(QiInventory inventory, double volume) {
        // 和检视窗口同一套换算：元数求和，容量按当前平均密度，占用比例按体积。
        return new Account(QiDisplay.amount(inventory), QiDisplay.displayedCapacity(volume, inventory),
                QiDisplay.storageFraction(inventory, volume));
    }

    private static MeridianState state(BodySnapshot body, String id) {
        // 构型里有、状态还没同步的经脉返回 null。
        return body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static double organs(BodySnapshot body) {
        // 器官缓存连同加工中的原料，与 QiDisplay.total 的口径一致。
        double total = 0;
        for (OrganState organ : body.getOrganStatesList()) {
            total += QiDisplay.amount(organ.getBuffer());
            if (organ.hasProcess()) {
                total += QiDisplay.amount(organ.getProcess().getInput());
            }
        }
        return total;
    }

    private static double transits(BodySnapshot body) {
        // 已离开发出端、还没到目的经脉的真元。
        return body.getTransitsList().stream()
                .mapToDouble(transit -> QiDisplay.amount(transit.getCargo()))
                .sum();
    }

    static List<Share> phases(List<Qi> entries) {
        // 按密度区间归到相态，从稀到密排列。
        var totals = new EnumMap<QiPhase, Double>(QiPhase.class);
        for (Qi entry : entries) {
            totals.merge(QiDensity.phase(entry.getDensity()), entry.getAmount(), Double::sum);
        }
        var shares = new ArrayList<Share>();
        totals.forEach((phase, amount) -> shares.add(new Share(Kind.PHASE, phase.name(), amount)));
        return List.copyOf(shares);
    }

    static List<Share> elements(List<Qi> entries) {
        // 按属性合计，元数多的在前；同量时按属性 ID 排，顺序稳定。
        var totals = new LinkedHashMap<String, Double>();
        for (Qi entry : entries) {
            totals.merge(entry.getAttribute(), entry.getAmount(), Double::sum);
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new Share(Kind.ELEMENT, entry.getKey(), entry.getValue()))
                .toList();
    }

    static int elementColor(String attribute) {
        // 与吐纳窗口粒子同一套五行色，补上不透明度。
        return OPAQUE | BreathingCanvas.elementColor(attribute);
    }
}
