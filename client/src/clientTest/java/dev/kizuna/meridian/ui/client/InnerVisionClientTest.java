package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InspectionWindowProbe;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.progression.client.ScrollScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import static com.google.common.base.Preconditions.checkState;

/** 真客户端验证四卷阅读、锁定窗口、即时撤权和完整人体渲染接线。 */
public final class InnerVisionClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../inner-vision-results.txt");
    private static final int TIMEOUT_TICKS = 1_600;
    private static final int READY_TICKS = 60;
    private static final int FRAME_SETTLE_TICKS = 30;
    private static final int READING_TICKS = 280;
    private static final int OP_LEVEL = 4;
    private static final int GUI_SCALE = 2;
    private static final Identifier SCROLL = new Identifier("meridian_progression", "inner_vision_scroll");
    private enum Stage { PREPARE, LOCKED, READ, USE_SCROLL, SCROLL, OPEN, RESET, REOPEN, DONE }
    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private CompletableFuture<Void> command;
    private BodyUiModule module;
    private WindowState window;

    @Override
    public void onInitializeClient() {
        // 测试入口只在专用运行任务中加载，失败也写报告并退出。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        // 按客户端收到的真实进度推进，不在客户端直接更改投影。
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT_TICKS, "内观客户端验收超时：" + stage);
            if (ticks < READY_TICKS || client.player == null || client.getServer() == null
                    || client.getOverlay() != null || !ClientProgression.synchronizedWithServer()) {
                return;
            }
            switch (stage) {
                case PREPARE -> prepare(client);
                case LOCKED -> openLocked(client);
                case READ -> read(client);
                case USE_SCROLL -> useScroll(client);
                case SCROLL -> inspectScroll(client);
                case OPEN -> openInspection(client);
                case RESET -> verifyReset(client);
                case REOPEN -> verifyReopen(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            finish(client, failure.toString());
        }
    }

    private void prepare(MinecraftClient client) {
        // 只操作从 GameTest 复制的世界，以正式命令设定札记已读、内观未读。
        module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance)
                .map(BodyUiModule.class::cast)
                .findFirst()
                .orElseThrow();
        client.options.getGuiScale().setValue(GUI_SCALE);
        client.onResolutionChanged();
        command = serverCommands(client, "meridian progression unlock", "meridian progression reset inner_vision");
        next(Stage.LOCKED);
    }

    private void openLocked(MinecraftClient client) {
        // 未解锁入口仍在菜单，实际工厂返回 LockedNotice，不能生成体表面板。
        if (!command.isDone() || !ClientProgression.canCultivate() || ClientProgression.canInspect()) {
            return;
        }
        command.join();
        var runtime = InventoryUiClient.runtime();
        checkState(runtime.workspace().features("").stream()
                        .anyMatch(feature -> feature.id().equals("meridian_ui:body")),
                "未解锁检视入口从菜单消失");
        runtime.openWorkspace();
        window = runtime.open("meridian_ui:body", null);
        checkState(InspectionWindowProbe.content(window.key()) instanceof LockedNotice, "未解锁没有显示锁定卡");
        checkState(module.panel() == null, "未解锁仍创建体表检视");
        next(Stage.READ);
    }

    private void read(MinecraftClient client) {
        // 先保存锁定画面，再由服务端给物品；下一阶段从客户端实际右键发送使用包。
        if (ticks - stageTick < FRAME_SETTLE_TICKS) {
            return;
        }
        capture(client, "inner-vision-locked.png");
        client.setScreen(null);
        var server = client.getServer();
        var playerId = client.player.getUuid();
        command = CompletableFuture.runAsync(() -> {
            var player = server.getPlayerManager().getPlayer(playerId);
            player.getInventory().selectedSlot = 0;
            player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Registries.ITEM.get(SCROLL)));
            player.currentScreenHandler.sendContentUpdates();
        }, server);
        client.player.getInventory().selectedSlot = 0;
        next(Stage.USE_SCROLL);
    }

    private void useScroll(MinecraftClient client) {
        // 等物品同步完成后只发送一次真实右键，不用重复输入促成成功。
        if (!command.isDone() || !client.player.getMainHandStack().isOf(Registries.ITEM.get(SCROLL))) {
            return;
        }
        command.join();
        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        next(Stage.SCROLL);
    }

    private void inspectScroll(MinecraftClient client) {
        // 等正文真实滚入纸面再截图，提前合卷不能撤销服务端已授予的权限。
        if (!ClientProgression.canInspect() || ticks - stageTick < READING_TICKS) {
            return;
        }
        checkState(client.currentScreen instanceof ScrollScreen, "真实右键没有打开阅读画面");
        checkState(window.closed(), "读卷后旧锁定窗口未关闭");
        capture(client, "inner-vision-scroll.png");
        client.currentScreen.close();
        next(Stage.OPEN);
    }

    private void openInspection(MinecraftClient client) {
        // 授权后实际挂载人体面板，并验证内部骨架层可选。
        if (ticks - stageTick < FRAME_SETTLE_TICKS) {
            return;
        }
        var runtime = InventoryUiClient.runtime();
        runtime.openWorkspace();
        window = runtime.open("meridian_ui:body", null);
        checkState(!(InspectionWindowProbe.content(window.key()) instanceof LockedNotice), "解锁后仍显示锁");
        checkState(module.panel() != null, "解锁后未创建真实人体窗口");
        module.panel().layer("skeleton");
        next(Stage.RESET);
    }

    private void verifyReset(MinecraftClient client) {
        // 保留真实内部层截图，再通过正式单卷命令撤权。
        if (ticks - stageTick < FRAME_SETTLE_TICKS) {
            return;
        }
        capture(client, "inner-vision-unlocked.png");
        command = serverCommands(client, "meridian progression reset inner_vision");
        next(Stage.REOPEN);
    }

    private void verifyReopen(MinecraftClient client) {
        // 收到重置即关闭实际窗口并释放面板，重新打开只能看到锁。
        if (!command.isDone() || ClientProgression.canInspect()) {
            return;
        }
        command.join();
        checkState(window.closed() && module.panel() == null, "重置后内观窗口或渲染资源仍在");
        checkState(ClientProgression.canGather() && ClientProgression.canUseMiddleJiao(), "重置内观误撤其他卷");
        var reopened = InventoryUiClient.runtime().open("meridian_ui:body", null);
        checkState(InspectionWindowProbe.content(reopened.key()) instanceof LockedNotice, "重置后仍可打开体表");
        finish(client, null);
    }

    private static CompletableFuture<Void> serverCommands(MinecraftClient client, String... commands) {
        // 所有权威修改都提交到整合服务器主线程，不复用客户端实体。
        var server = client.getServer();
        var playerId = client.player.getUuid();
        return CompletableFuture.runAsync(() -> {
            var player = server.getPlayerManager().getPlayer(playerId);
            for (String command : commands) {
                server.getCommandManager().executeWithPrefix(player.getCommandSource().withLevel(OP_LEVEL), command);
            }
        }, server);
    }

    private void next(Stage next) {
        // 记录阶段起点，为渲染和网络响应留出真实 tick。
        stage = next;
        stageTick = ticks;
    }

    private static void capture(MinecraftClient client, String name) {
        // 保存帧缓冲原图供人工复核，不用 OCR 推断授权状态。
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), message -> { });
    }

    private void finish(MinecraftClient client, String failure) {
        // 成败都结束隔离客户端，报告由 Gradle 验证。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure + "\n");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        client.scheduleStop();
    }
}
