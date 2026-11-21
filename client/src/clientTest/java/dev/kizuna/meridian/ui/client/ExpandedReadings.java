package dev.kizuna.meridian.ui.client;

import java.util.Base64;
import java.util.List;

/** 记录经脉展开数据页、吐纳与中焦的同帧输入。 */
public final class ExpandedReadings {
    private ExpandedReadings() { }

    public static void log(BodyUiModule module) {
        // 附属进度保持原有协议编码，验收工具按人体周天协议解读。
        var body = module.snapshot();
        var page = MeridianData.of(body);
        var breath = module.gatheringView().getBreathing();
        var state = body.getAddonProgressOrThrow("human:cultivation").getValue();
        System.out.println("S5_DATA 展开\t吐纳\t" + List.of(breath.getStaminaRatio(), breath.getAttentionRatio(),
                breath.getAutomatic(), breath.getAirLiters(), breath.getCapacityLiters(), breath.getStoredQi()));
        System.out.println("S5_DATA 展开\t中焦\t" + Base64.getEncoder().encodeToString(state.toByteArray())
                + "|" + body.getTransitsList().stream().filter(t -> t.getId().equals("human-middle-jiao"))
                .mapToDouble(t -> QiDisplay.amount(t.getCargo())).sum());
        System.out.println("S5_DATA 展开\t经脉\t" + List.of(page.opened(), page.rows().size(), page.meridianTotal(),
                page.lumen().capacity() + page.wall().capacity()));
        for (var row : page.rows()) {
            var meridian = row.state();
            System.out.println("S5_DATA 经脉\t" + row.definition().getDisplayName() + "\t"
                    + meridian.getCondition() + "|" + meridian.getOpening().getStage());
        }
    }
}
