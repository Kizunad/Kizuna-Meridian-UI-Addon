package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.status.StatusNotes;
import dev.kizuna.meridian.technique.fabric.TechniqueCodex;
import dev.kizuna.meridian.technique.fabric.client.TechniqueEffectTooltips;
import net.fabricmc.api.ClientModInitializer;

/** 功法谱的客户端入口：右键功法谱翻开书页，读卷学会时弹小卡。 */
public final class CodexClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 功法库的物品只发翻开事件，书页和小卡都在这里登记。
        CodexMoments.initialize();
        TechniqueCodex.OPEN.register(CodexScreen::open);
        // 功法库只生成说明行，由界面框架把它们接到状态图标的名字和时间下面。
        StatusNotes.register(TechniqueEffectTooltips::lines);
    }
}
