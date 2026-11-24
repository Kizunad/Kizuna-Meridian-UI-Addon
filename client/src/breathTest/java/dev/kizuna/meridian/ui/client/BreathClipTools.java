package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.workspace.UiVersion;
import io.wispforest.owo.ui.core.UIComponent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static com.google.common.base.Preconditions.checkState;

/** 吐纳录像场景共用的手脚：选场景、找界面模块、以玩家身份跑命令、点正式组件、拍帧缓冲。 */
final class BreathClipTools {
    /** 运行任务用这个系统属性点名要拍哪个场景；没写就是攒五行那个。 */
    private static final String SCENE_PROPERTY = "meridian.breathScene";
    private static final String DEFAULT_SCENE = "element";
    /** 运行任务用这个系统属性点名跑哪一版界面；没写就是缺省的那一版。 */
    private static final String VERSION_PROPERTY = "meridian.uiVersion";

    private BreathClipTools() {
        // 只有静态方法。
    }

    static boolean selected(String scene) {
        // 两个场景的入口都会被加载，各自只在点到自己时动手。
        return scene.equals(System.getProperty(SCENE_PROPERTY, DEFAULT_SCENE));
    }

    static UiVersion version() {
        return UiVersion.of(System.getProperty(VERSION_PROPERTY));
    }

    static BodyUiModule module() {
        // 正式装配里登记的那一个界面模块。
        return FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
    }

    static CompletableFuture<Void> run(Minecraft client, List<String> commands) {
        // 在整合服的线程上以这名玩家、管理员权限执行。
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        return CompletableFuture.runAsync(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER)
                    .withSuppressedOutput();
            commands.forEach(line -> server.getCommands().performPrefixedCommand(source, line));
        }, server);
    }

    static void click(BodyUiModule module, String id) {
        // 用真实的鼠标按下事件点正式组件。
        UIComponent target = module.cultivationPanel().root().childById(UIComponent.class, id);
        boolean handled = target.onMouseDown(new MouseButtonEvent(target.x() + 4, target.y() + 4,
                new MouseButtonInfo(0, 0)), false);
        checkState(handled, "点 " + id + " 没有反应");
    }

    static void capture(Minecraft client, String name) {
        // 保存帧缓冲原图。
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> { });
    }
}
