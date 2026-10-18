package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import static dev.kizuna.meridian.protocol.pb.OperationUiMessages.OperationMeterState.*;

/** 经脉的通用只读汇总；具体操作条件由内容包提供。 */
final class OpeningCards {
    private OpeningCards() {
        // 快照投影没有可写账本。
    }

    static MeridianOperationMeter meter(String id, String label, double fraction, OperationMeterState state, String hint) {
        // 条形只接受归一化数据，具体数值不堆进操作窗口。
        return MeridianOperationMeter.newBuilder().setId(id).setLabel(label)
                .setFraction(Math.max(0, Math.min(1, fraction))).setState(state).setHint(hint).build();
    }

    static MeridianOperationView summary(BodySnapshot body, String target) {
        // 真实储量与在途预约分别绘制，稳定容量和开脉增压目标分开表达。
        MeridianDefinition definition = body.getStructure().getMeridiansList().stream()
                .filter(item -> item.getId().equals(target)).findFirst().orElseThrow();
        MeridianState state = body.getMeridianStatesList().stream().filter(item -> item.getId().equals(target)).findFirst().orElseThrow();
        boolean opened = state.getOpening().getStage() == MeridianOpeningStage.OPENED;
        boolean intact = state.getCondition() == MeridianCondition.INTACT;
        double fullness = QiDisplay.ratio(QiDisplay.amount(state.getLumenQi()), definition.getLumenCapacity());
        return MeridianOperationView.newBuilder().setTitle(definition.getDisplayName())
                .setStatus(opened ? "已拓宽" : "尚未拓宽")
                .addMeters(meter("storage", "内腔储量", fullness, fullness >= 1 ? METER_READY : METER_WAITING,
                        "实线为已到账，淡色段为正在送来的真元").toBuilder()
                        .setIncomingFraction(QiDisplay.ratio(QiDisplay.incoming(body, target), definition.getLumenCapacity())))
                .addMeters(meter("condition", "通路完好", intact ? 1 : 0,
                        intact ? METER_READY : METER_BLOCKED, intact ? "通路可用" : "先恢复受损经脉"))
                .addMeters(meter("opening", "拓宽进度", opened ? 1 : state.getOpening().getProgress(),
                        opened ? METER_READY : METER_WAITING, "随服务端确认的适应进度逐步亮起")).build();
    }

}
