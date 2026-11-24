package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.AuraWindowProbe;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.core.UIComponent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static com.google.common.base.Preconditions.checkState;

/**
 * 真客户端里看灵气徽记：正式的吐纳窗口，身体和吐纳视图来自整合服；
 * 当地环境换成六种属性、两行接近、稀薄和充盈各一份喂给同一个面板，逐个截图；再点徽记开小窗、把窗口缩窄各拍一张。
 */
public final class AuraEmblemClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../aura-emblem-results.txt");
    private static final int TIMEOUT_TICKS = 1_600;
    private static final int READY_TICKS = 60;
    private static final int FRAME_TICKS = 12;
    private static final PermissionSet OP_PERMISSIONS = LevelBasedPermissionSet.OWNER;
    private static final int GUI_SCALE = 2;
    private static final double FREE = 0.01;
    /** 各场景：截图名、浓度（元/格）、金木水火土无属性的份数、悬浮该写的那句。 */
    private static final List<Scene> SCENES = List.of(
            new Scene("wood", 0.8, new double[] {10, 45, 15, 10, 20, 0}, "寻常·偏木，约 0.80 元/格"),
            new Scene("fire", 0.8, new double[] {10, 5, 5, 60, 20, 0}, "寻常·偏火，约 0.80 元/格"),
            new Scene("water", 0.8, new double[] {10, 15, 55, 5, 15, 0}, "寻常·偏水，约 0.80 元/格"),
            new Scene("metal", 0.8, new double[] {60, 5, 10, 5, 20, 0}, "寻常·偏金，约 0.80 元/格"),
            new Scene("earth", 0.8, new double[] {10, 3, 5, 22, 60, 0}, "寻常·偏土，约 0.80 元/格"),
            new Scene("none", 0.8, new double[] {0, 0, 0, 0, 0, 100}, "寻常·偏无属性，约 0.80 元/格"),
            new Scene("split-metal-earth", 0.8, new double[] {45, 5, 10, 5, 36, 0}, "寻常·偏金土，约 0.80 元/格"),
            new Scene("thin-earth", 0.2, new double[] {10, 3, 5, 22, 60, 0}, "稀薄·偏土，约 0.20 元/格"),
            new Scene("rich-wood", 1.9, new double[] {5, 65, 20, 5, 5, 0}, "充盈·偏木，约 1.90 元/格"));
    private static final List<String> ATTRIBUTES = List.of("meridian:metal", "meridian:wood", "meridian:water",
            "meridian:fire", "meridian:earth", "meridian:none");
    private static final String VERSION_PROPERTY = "meridian.uiVersion";
    private static final String COMPACT_WIDTH = "380";
    private static final String COMPACT_HEIGHT = "400";

    private record Scene(String name, double concentration, double[] parts, String brief) { }

    private enum Stage { PREPARE, OPEN, SCENES, DETAIL, DETAIL_SHOT, COMPACT, COMPACT_SHOT, DONE }

    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private int sceneIndex;
    private boolean sceneShown;
    private CompletableFuture<Void> command;
    private BodyUiModule module;
    private WindowState window;
    private GatheringView real;
    private int screenshots;

    @Override
    public void onInitializeClient() {
        // 测试入口只在专用运行任务中加载，失败也写报告并退出。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT_TICKS, "灵气徽记客户端验收超时：" + stage);
            if (ticks < READY_TICKS || client.player == null || client.getSingleplayerServer() == null
                    || client.gui.overlay() != null || !ClientProgression.synchronizedWithServer()) {
                return;
            }
            switch (stage) {
                case PREPARE -> prepare(client);
                case OPEN -> open(client);
                case SCENES -> scenes(client);
                case DETAIL -> detail();
                case DETAIL_SHOT -> detailShot(client);
                case COMPACT -> compact();
                case COMPACT_SHOT -> compactShot(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            finish(client, failure.toString());
        }
    }

    private void prepare(Minecraft client) {
        // 只操作从 GameTest 复制的世界，用正式命令把四卷都解锁，吐纳窗口才能打开真实场景。
        module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        command = CompletableFuture.runAsync(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(OP_PERMISSIONS), "meridian progression unlock");
        }, server);
        next(Stage.OPEN);
    }

    private void open(Minecraft client) {
        // 等解锁同步过来再开窗；窗口、面板都走正式工厂。
        if (!command.isDone() || !ClientProgression.canGather()) {
            return;
        }
        command.join();
        if (window == null) {
            var runtime = InventoryUiClient.runtime();
            runtime.motion(false /* enabled */);
            // 跑哪一版界面由运行任务点名；没点就是缺省的那一版。
            runtime.uiVersion(UiVersion.of(System.getProperty(VERSION_PROPERTY)));
            runtime.openWorkspace();
            window = runtime.open("meridian_ui:cultivation", null);
        }
        // 等整合服发来第一份带环境的吐纳视图，后面只换其中的环境一项。
        if (module.cultivationPanel() == null || emblemTooltip().isEmpty()) {
            return;
        }
        real = module.gatheringView();
        checkState(real != null && real.hasEnvironment() && real.getEnvironment().getAvailable(), "整合服没有发来当地环境");
        // 整合服会不停推送身体和吐纳视图，把喂进去的场景盖掉；拿到这一份以后摘掉两个接收器，面板只显示喂的内容。
        ClientPlayNetworking.unregisterGlobalReceiver(MeridianChannels.GATHERING_RESPONSE.type().id());
        ClientPlayNetworking.unregisterGlobalReceiver(MeridianChannels.BODY_VIEW.type().id());
        next(Stage.SCENES);
    }

    private void scenes(Minecraft client) {
        // 每个场景先喂给面板，等几帧画出来，核对悬浮那句，再截图。
        Scene scene = SCENES.get(sceneIndex);
        if (!sceneShown) {
            module.cultivationPanel().update(real.toBuilder().setEnvironment(environment(scene)).build(), false, "");
            sceneShown = true;
            stageTick = ticks;
            return;
        }
        if (ticks - stageTick < FRAME_TICKS) {
            return;
        }
        checkState(emblemTooltip().equals(scene.brief()), scene.name() + " 的悬浮不对：" + emblemTooltip());
        capture(client, "aura-" + scene.name() + ".png");
        sceneShown = false;
        if (++sceneIndex == SCENES.size()) {
            next(Stage.DETAIL);
        }
    }

    private void detail() {
        // 用真实的鼠标按下事件点徽记：应当打开「此地灵气」小窗，吐纳窗口里不再展开明细。
        UIComponent emblem = emblem();
        boolean handled = emblem.onMouseDown(new MouseButtonEvent(emblem.x() + 4, emblem.y() + 4,
                new MouseButtonInfo(0, 0)), false);
        checkState(handled, "点徽记没有反应");
        next(Stage.DETAIL_SHOT);
    }

    private void detailShot(Minecraft client) {
        if (ticks - stageTick < FRAME_TICKS) {
            return;
        }
        var opened = InventoryUiClient.runtime().workspace().windows().snapshot().stream()
                .filter(state -> state.definition().windowType().equals("meridian_ui:aura_window") && !state.closed())
                .findFirst().orElseThrow(() -> new IllegalStateException("点徽记没有打开小窗"));
        var lines = ((EnvironmentDetailPanel) AuraWindowProbe.content(opened.key()))
                .childById(LabelComponent.class, "aura-lines");
        String text = lines.text().getString();
        checkState(text.contains("总量") && text.contains("浓度") && text.contains("无属性") && text.contains("分档"),
                "小窗里的明细不全：" + text);
        capture(client, "aura-detail-window.png");
        InventoryUiClient.runtime().workspace().windows().close(opened.key());
        next(Stage.COMPACT);
    }

    private void compact() {
        // 新版窗口没有窄排布：徽记一直在场景左上角。
        if (InventoryUiClient.runtime().uiVersion() == UiVersion.MODERN) {
            next(Stage.COMPACT_SHOT);
            return;
        }
        // 窗口缩到窄排布：徽记挪到余力条下面，不和余力条叠在一起。
        checkState(InventoryUiClient.runtime().workspace().windows().resize(window.key(), COMPACT_WIDTH, COMPACT_HEIGHT),
                "吐纳窗口缩不了");
        next(Stage.COMPACT_SHOT);
    }

    private void compactShot(Minecraft client) {
        if (ticks - stageTick < FRAME_TICKS) {
            return;
        }
        UIComponent emblem = emblem();
        UIComponent root = module.cultivationPanel().root();
        if (InventoryUiClient.runtime().uiVersion() == UiVersion.MODERN) {
            UIComponent scene = module.cultivationPanel().root().childById(UIComponent.class, "breathing-scene");
            checkState(emblem.x() >= scene.x() && emblem.y() >= scene.y()
                    && emblem.x() + emblem.width() <= scene.x() + scene.width() / 2
                    && emblem.y() + emblem.height() <= scene.y() + scene.height(),
                    "新版窗口里徽记不在场景左上角：" + emblem.x() + "," + emblem.y());
            capture(client, "aura-modern.png");
            finish(client, null);
            return;
        }
        checkState(emblem.y() - root.y() >= 30, "窄窗口里徽记没有挪到余力条下面：" + (emblem.y() - root.y()));
        capture(client, "aura-compact.png");
        finish(client, null);
    }

    private UIComponent emblem() {
        // 按公开身份找真实组件。
        return module.cultivationPanel().root().childById(UIComponent.class, "breathing-emblem");
    }

    private String emblemTooltip() {
        // 徽记现在悬浮里写的那句；还没收到环境时是空串。
        return ((EnvironmentEmblem) emblem()).currentBrief();
    }

    private static GatheringEnvironment environment(Scene scene) {
        // 各行一条游离态明细，份数按一百份的总量换成元；吸收上限在数值上等于浓度。
        var qi = QiInventory.newBuilder();
        for (int index = 0; index < ATTRIBUTES.size(); index++) {
            if (scene.parts()[index] > 0) {
                qi.addEntries(Qi.newBuilder().setAttribute(ATTRIBUTES.get(index)).setDensity(FREE)
                        .setAmount(scene.parts()[index] * 1000));
            }
        }
        return GatheringEnvironment.newBuilder().setAvailable(true).setQi(qi)
                .setConcentrationPerBlock(scene.concentration()).setSupplyPerSecond(scene.concentration()).build();
    }

    private void next(Stage next) {
        // 记录阶段起点，为渲染和网络响应留出真实 tick。
        stage = next;
        stageTick = ticks;
    }

    private void capture(Minecraft client, String name) {
        // 保存帧缓冲原图供人工复核。
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> { });
        screenshots++;
    }

    private void finish(Minecraft client, String failure) {
        // 成败都结束隔离客户端，报告由 Gradle 验证。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure
                    + "\nscreenshots=" + screenshots + "\n");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        client.stop();
    }
}
