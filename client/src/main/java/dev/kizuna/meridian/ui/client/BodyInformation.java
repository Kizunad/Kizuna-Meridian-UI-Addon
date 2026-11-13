package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** 按对象身份投影已有快照字段，不在 UI 重算权威能力。 */
public final class BodyInformation {
    static final String PART_DISABLED = "已失能：这里的筋肉出不了力，器官收发不了真元，用到它们的功法放不出。";
    static final String PART_DISABLED_WAY_OUT = "出路：回血和药水都治不了归零的部位。濒死时手里有不死图腾，各处抬回两成半；"
            + "死后重生，各处回到一成。";

    private BodyInformation() {
        // 信息投影是纯读取，不缓存另一份可变身体状态。
    }

    public static List<String> lines(BodySnapshot body, BodyVisual node) {
        // 纯文字版本，行的内容和顺序与带悬浮说明的版本相同。
        return describe(body, node).stream().map(Component::getString).toList();
    }

    /** 带悬浮说明的信息行：鼠标停在一行上，显示这一项管什么。 */
    public static List<Component> describe(BodySnapshot body, BodyVisual node) {
        return rows(body, node).stream().map(Row::component).toList();
    }

    /** 一行文字和它的悬浮说明；没有说明的行悬浮为空。 */
    private static final class Rows extends ArrayList<Row> {
        void add(String text) {
            add(new Row(text, ""));
        }

        void add(String text, String hover) {
            add(new Row(text, hover));
        }
    }

    private record Row(String text, String hover) {
        Component component() {
            // 悬浮说明挂在文字样式上，标签组件自己负责显示。
            var line = Component.literal(text);
            if (hover.isEmpty()) {
                return line;
            }
            return line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
        }
    }

    private static List<Row> rows(BodySnapshot body, BodyVisual node) {
        // 先查现存定义，再读取对应状态；缺失状态不能假装为满血或空缓存。
        var lines = new Rows();
        lines.add(node.getDisplayName());
        if (!node.getDescription().isBlank()) {
            lines.add(node.getDescription());
        }
        lines.add("");
        if (!BodyScene.exists(body, node)) {
            lines.add(UiErrors.MISSING_TARGET);
            return List.copyOf(lines);
        }
        String id = node.getTargetId();
        switch (node.getTargetKind()) {
            case PART -> {
                PartDefinition definition = body.getStructure().getPartsList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
                lines.add("部位：" + definition.getDisplayName());
                PartState state = body.getPartStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("部位状态尚未同步");
                } else {
                    lines.add("HP：" + number(state.getHp()) + " / " + number(definition.getMaxHp()),
                            "这一处的血量。红心是全身各处血量合起来的比例。\n吃饱后的自然回血、治疗和再生药水，只补到还没归零的部位上。");
                    if (state.getHp() == 0) {
                        // 归零的部位原版回血治不了，玩家只看到红心回满而手脚不听使唤，这里把状态和出路写明。
                        lines.add(PART_DISABLED);
                        lines.add(PART_DISABLED_WAY_OUT);
                    }
                }
            }
            case TISSUE -> {
                TissueDefinition definition = body.getStructure().getTissuesList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
                lines.add("组织：" + definition.getDisplayName());
                lines.add("基础力量：" + number(definition.getCapacity().getStrengthNewtons()) + " N",
                        "满状态下能出的力。伤了、累了都按比例打折。\n手臂肌肉眼下的力量决定近战和拳掌打得多重，腿上的决定腿法。");
                lines.add("基础爆发：" + number(definition.getCapacity().getBurstWatts()) + " W",
                        "一次出手能用的最大功率，同样随伤和疲劳打折。\n出手太猛、超过它，这一下就使不上劲。");
                lines.add("基础耐力：" + number(definition.getCapacity().getEnduranceJoules()) + " J",
                        "一口气能做的功。用掉几成，疲劳就涨几成；走、跑也从这里出。");
                // 动作定义随权威实例保存，客户端直接展示占用，不猜测行走或吐纳状态。
                List<String> occupyingActions = body.getActionsList().stream()
                        .filter(action -> action.getDefinition().getTissueIdsList().contains(id))
                        .map(action -> action.getDefinition().getDisplayName()).toList();
                lines.add("占用：" + (occupyingActions.isEmpty() ? "空闲" : String.join("、", occupyingActions)),
                        "正在用这块组织的动作。被占用时疲劳不恢复。");
                lines.add("空闲恢复半衰期：" + number(definition.getRecoveryHalfLifeSeconds()) + " 秒",
                        "空闲时，疲劳每过这么久减半。0 表示不会自己恢复。");
                TissueState state = body.getTissueStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("组织状态尚未同步");
                } else {
                    lines.add("HP：" + number(state.getHp()) + " / " + number(definition.getMaxHp()),
                            "这块组织自己的伤。它不会自己长好，原版回血也治不到；\n四肢的骨、肉、皮可以敷苦黄膏。归零就出不了力。");
                    lines.add("疲劳：" + number(state.getFatigue()) + " / " + number(definition.getFatigueCapacity()),
                            "越累出力越小，满了就使不上劲；空闲时自己恢复。");
                }
            }
            case ORGAN -> {
                OrganDefinition definition = body.getStructure().getOrgansList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
                lines.add("器官：" + definition.getDisplayName());
                lines.add("吞吐：" + number(definition.getThroughputPerSecond()) + " 元/秒",
                        "这个器官每秒最多收发这么多真元。");
                OrganState state = body.getOrganStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("器官状态尚未同步");
                } else {
                    lines.add("HP：" + number(state.getHp()) + " / " + number(definition.getMaxHp()),
                            "器官自己的伤。它不会自己长好，原版回血也治不到。\n归零就不能收发真元、不能炼化。");
                    lines.add("缓存：" + number(amount(state.getBuffer())) + " / "
                            + number(QiDensity.displayedCapacity(definition.getVolumeCapacity(), state.getBuffer())) + " 元",
                            "器官里暂存的真元 / 装得下的量。死时清空。");
                    if (state.hasProcess()) {
                        lines.add("加工：" + state.getProcess().getRecipe().getId());
                    lines.add("加工中原料：" + number(amount(state.getProcess().getInput())) + " 元");
                    } else {
                        lines.add("加工：空闲");
                    }
                }
            }
            case MERIDIAN -> {
                MeridianDefinition definition = body.getStructure().getMeridiansList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
                lines.add("经脉：" + definition.getDisplayName());
                lines.add("吞吐：" + number(definition.getThroughputPerSecond()) + " 元/秒",
                        "这条经脉每秒最多流过这么多真元。");
                lines.add("承受度：" + number(definition.getResilience()), "开脉时比对的经脉强度。");
                MeridianState state = body.getMeridianStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("经脉状态尚未同步");
                } else {
                    lines.add("内腔：" + number(amount(state.getLumenQi())) + " / "
                            + number(QiDensity.displayedCapacity(definition.getLumenVolume(), state.getLumenQi())) + " 元",
                            "能流动的真元 / 装得下的量。放功法、开脉用的都是内腔里的。\n开拓以后内腔变宽很多。");
                    lines.add("外壁：" + number(amount(state.getWallQi())) + " / "
                            + number(QiDensity.displayedCapacity(definition.getWallVolume(), state.getWallQi())) + " 元",
                            "沉在脉壁里的储备，不参与流动；突破境界时当燃料。死时清空。");
                    lines.add("疲劳：" + number(state.getFatigue()), "眼下没有规则用到这一项。");
                    lines.add("状态：" + conditionName(state.getCondition()),
                            "完好才能运转。破漏、断裂的经脉不能收发真元，眼下没有办法修。\n周天推得太猛会破漏。");
                    lines.add("开拓：" + stageName(state.getOpening().getStage()),
                            "开拓过的经脉才能走周天、给功法付真元。");
                    lines.add("进度：" + number(state.getOpening().getProgress() * 100) + "%", "这条经脉开拓到几成。");
                }
            }
            default -> lines.add(UiErrors.MISSING_TARGET);
        }
        return List.copyOf(lines);
    }

    static String conditionName(MeridianCondition condition) {
        // 检视和数据页共用的经脉状态叫法。
        return switch (condition) {
            case INTACT -> "完好";
            case LEAKING -> "破漏";
            case SEVERED -> "断裂";
            default -> "未知";
        };
    }

    static String stageName(MeridianOpeningStage stage) {
        // 检视和数据页共用的开拓阶段叫法。
        return switch (stage) {
            case UNOPENED -> "未开拓";
            case IN_PROGRESS -> "进行中";
            case OPENED -> "已开拓";
            default -> "未知";
        };
    }

    private static double amount(QiInventory inventory) {
        // 只为当前信息行汇总明细，不把派生总数保存为可写账户。
        return inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
    }

    private static String number(double value) {
        // 小量真元保留两位，避免 0.01 元被显示成空库存。
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
