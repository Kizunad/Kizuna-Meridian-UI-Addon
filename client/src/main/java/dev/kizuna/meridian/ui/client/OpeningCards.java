package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import static dev.kizuna.meridian.protocol.pb.OperationUiMessages.OperationMeterState.*;

/** 经脉的通用只读汇总；具体操作条件由内容包提供。 */
final class OpeningCards {
    private OpeningCards() {
        // 快照投影没有可写账本。
    }

    static MeridianOperationMeter meter(String id, String label, double fraction,
                                       OperationMeterState state, String hint) {
        // 服务端与客户端构成的卡片都使用归一化条件值。
        return MeridianOperationMeter.newBuilder()
                .setId(id)
                .setLabel(label)
                .setFraction(Math.max(0, Math.min(1, fraction)))
                .setState(state)
                .setHint(hint)
                .build();
    }

    static MeridianOperationView summary(BodySnapshot body, String target) {
        // 实际储量与在途预约分别绘制，都按液态参照体积计算。
        var definition = body.getStructure().getMeridiansList().stream()
                .filter(item -> item.getId().equals(target)).findFirst().orElseThrow();
        var state = body.getMeridianStatesList().stream()
                .filter(item -> item.getId().equals(target)).findFirst().orElseThrow();
        var opening = state.getOpening();
        String status = WindowText.get("action.unopened", "尚未拓宽");
        if (opening.getStage() == MeridianOpeningStage.OPENED) {
            status = WindowText.get("action.opened", "已拓宽");
        }
        return MeridianOperationView.newBuilder()
                .setTitle(definition.getDisplayName())
                .setStatus(status)
                .addMeters(storage(body, definition, state))
                .addMeters(condition(state))
                .addMeters(openingMeter(opening))
                .build();
    }

    private static MeridianOperationMeter storage(BodySnapshot body, MeridianDefinition definition,
                                                 MeridianState state) {
        // 在途量只占预约空间，与实际到账的储量分开显示。
        double volume = definition.getLumenVolume();
        double fullness = QiDisplay.ratio(QiDisplay.volume(state.getLumenQi()), volume);
        var status = fullness >= 1 ? METER_READY : METER_WAITING;
        String label = WindowText.get("action.storage", "内腔储量");
        String hint = WindowText.get("action.storage_hint", "实线为已到账，淡色段为正在送来的真元");
        return meter("storage", label, fullness, status, hint).toBuilder()
                .setIncomingFraction(QiDisplay.ratio(QiDisplay.incoming(body, definition.getId()), volume))
                .build();
    }

    private static MeridianOperationMeter condition(MeridianState state) {
        // 经脉是否完好决定通路能否使用。
        boolean intact = state.getCondition() == MeridianCondition.INTACT;
        String hint = WindowText.get("action.damaged_hint", "先恢复受损经脉");
        if (intact) {
            hint = WindowText.get("action.intact_hint", "通路可用");
        }
        String label = WindowText.get("action.condition", "通路完好");
        return meter("condition", label, intact ? 1 : 0, intact ? METER_READY : METER_BLOCKED, hint);
    }

    private static MeridianOperationMeter openingMeter(MeridianOpening opening) {
        // 开拓完成后显示满值，进行中的数值以快照为准。
        boolean opened = opening.getStage() == MeridianOpeningStage.OPENED;
        double progress = opening.getProgress();
        if (opened) {
            progress = 1;
        }
        String label = WindowText.get("action.progress", "拓宽进度");
        String hint = WindowText.get("action.progress_hint", "随服务端确认的适应进度逐步亮起");
        return meter("opening", label, progress, opened ? METER_READY : METER_WAITING, hint);
    }

}
