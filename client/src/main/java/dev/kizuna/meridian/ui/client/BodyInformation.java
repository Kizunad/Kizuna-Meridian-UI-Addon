package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按对象身份投影已有快照字段，不在 UI 重算权威能力。 */
public final class BodyInformation {
    private BodyInformation() {
        // 信息投影是纯读取，不缓存另一份可变身体状态。
    }

    public static List<String> lines(BodySnapshot body, BodyVisual node) {
        // 先查现存定义，再读取对应状态；缺失状态不能假装为满血或空缓存。
        var lines = new ArrayList<String>();
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
                    lines.add("HP：" + number(state.getHp()) + " / " + number(definition.getMaxHp()));
                }
            }
            case TISSUE -> {
                TissueDefinition definition = body.getStructure().getTissuesList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
                lines.add("组织：" + definition.getDisplayName());
                lines.add("基础力量：" + number(definition.getCapacity().getStrengthNewtons()) + " N");
                lines.add("基础爆发：" + number(definition.getCapacity().getBurstWatts()) + " W");
                lines.add("基础耐力：" + number(definition.getCapacity().getEnduranceJoules()) + " J");
                // 动作定义随权威实例保存，客户端直接展示占用，不猜测行走或吐纳状态。
                List<String> occupyingActions = body.getActionsList().stream()
                        .filter(action -> action.getDefinition().getTissueIdsList().contains(id))
                        .map(action -> action.getDefinition().getDisplayName()).toList();
                lines.add("占用：" + (occupyingActions.isEmpty() ? "空闲" : String.join("、", occupyingActions)));
                lines.add("空闲恢复半衰期：" + number(definition.getRecoveryHalfLifeSeconds()) + " 秒");
                TissueState state = body.getTissueStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("组织状态尚未同步");
                } else {
                    lines.add("HP：" + number(state.getHp()) + " / " + number(definition.getMaxHp()));
                    lines.add("疲劳：" + number(state.getFatigue()) + " / " + number(definition.getFatigueCapacity()));
                }
            }
            case ORGAN -> {
                OrganDefinition definition = body.getStructure().getOrgansList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
                lines.add("器官：" + definition.getDisplayName());
                lines.add("吞吐：" + number(definition.getThroughputPerSecond()) + " 元/秒");
                OrganState state = body.getOrganStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("器官状态尚未同步");
                } else {
                    lines.add("HP：" + number(state.getHp()) + " / " + number(definition.getMaxHp()));
                    lines.add("缓存：" + number(amount(state.getBuffer())) + " / "
                            + number(QiDensity.displayedCapacity(definition.getVolumeCapacity(), state.getBuffer())) + " 元");
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
                lines.add("吞吐：" + number(definition.getThroughputPerSecond()) + " 元/秒");
                lines.add("承受度：" + number(definition.getResilience()));
                MeridianState state = body.getMeridianStatesList().stream()
                        .filter(value -> value.getId().equals(id)).findFirst().orElse(null);
                if (state == null) {
                    lines.add("经脉状态尚未同步");
                } else {
                    lines.add("内腔：" + number(amount(state.getLumenQi())) + " / "
                            + number(QiDensity.displayedCapacity(definition.getLumenVolume(), state.getLumenQi())) + " 元");
                    lines.add("外壁：" + number(amount(state.getWallQi())) + " / "
                            + number(QiDensity.displayedCapacity(definition.getWallVolume(), state.getWallQi())) + " 元");
                    lines.add("疲劳：" + number(state.getFatigue()));
                    lines.add("状态：" + switch (state.getCondition()) {
                        case INTACT -> "完好";
                        case LEAKING -> "破漏";
                        case SEVERED -> "断裂";
                        default -> "未知";
                    });
                    lines.add("开拓：" + switch (state.getOpening().getStage()) {
                        case UNOPENED -> "未开拓";
                        case IN_PROGRESS -> "进行中";
                        case OPENED -> "已开拓";
                        default -> "未知";
                    });
                    lines.add("进度：" + number(state.getOpening().getProgress() * 100) + "%");
                }
            }
            default -> lines.add(UiErrors.MISSING_TARGET);
        }
        return List.copyOf(lines);
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
