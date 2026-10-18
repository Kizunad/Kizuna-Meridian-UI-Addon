package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;

/** 权威开脉任务的只读身份与生命周期判断。 */
public final class OpeningInformation {
    private OpeningInformation() {
        // 只读取服务端状态，UI 不自行判定完成。
    }

    public static boolean active(BodySnapshot body) {
        // 完成、暂停与耗尽都不再显示为正在调元。
        return body.hasOpeningTask() && (body.getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_PREPARING
                || body.getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_HOLDING);
    }

    static boolean targets(BodySnapshot body, String meridianId) {
        // 最近任务只属于自己的目标，切换选中经脉不挪用上一条的进度。
        return body.hasOpeningTask() && body.getOpeningTask().getRule().getTargetMeridianId().equals(meridianId);
    }
}
