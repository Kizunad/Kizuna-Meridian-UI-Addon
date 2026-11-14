package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.technique.fabric.TechniqueCodex;
import net.fabricmc.api.ClientModInitializer;

/** 功法谱的客户端入口：右键功法谱翻开书页，读卷学会时弹小卡。 */
public final class CodexClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 功法库的物品只发翻开事件，书页和小卡都在这里登记。
        CodexMoments.initialize();
        TechniqueCodex.OPEN.register(CodexScreen::open);
    }
}
