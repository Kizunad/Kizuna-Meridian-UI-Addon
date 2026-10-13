package dev.kizuna.meridian.ui.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/** 单一客户端偏好；教学已读不会影响服务端资源或操作许可。 */
final class BreathingTutorial {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("meridian-breathing-tutorial.txt");
    private static boolean acknowledged = !Boolean.getBoolean("meridian.ui.gatheringSmoke") && Files.isRegularFile(PATH);

    private BreathingTutorial() {
        // 仅保存一个显示偏好，没有玩家数据或第二份身体存档。
    }

    static boolean seen() {
        // 已读偏好只影响悬浮提示是否出现。
        return acknowledged;
    }

    static void remember() {
        // 写盘失败保留本次进程的已读状态，并记录可定位的问题。
        acknowledged = true;
        if (Boolean.getBoolean("meridian.ui.gatheringSmoke")) {
            return;
        }
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, "手动吐纳操作指引已读\n");
        } catch (IOException failure) {
            LoggerFactory.getLogger("meridian_ui").warn(UiErrors.TUTORIAL_SAVE, failure);
        }
    }
}
