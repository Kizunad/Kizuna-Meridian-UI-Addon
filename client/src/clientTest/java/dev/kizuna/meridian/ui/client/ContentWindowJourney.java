package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.CompactView;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;

import static com.google.common.base.Preconditions.checkState;

/** 在隔离客户端中对照实际业务窗口、旧配置和两个连接上的 HUD。 */
public final class ContentWindowJourney {
    private static final String PREFIX = "meridian_ui:";
    private static final String SURFACE = PREFIX + "body";
    private static final String OVERVIEW = PREFIX + "overview";
    private static final String BREATH = PREFIX + "cultivation";
    private static final String PRACTICE = PREFIX + "practice";
    private static final String FILL_QI = "meridian qi fill";
    private static final Set<String> LEGACY = Set.of(SURFACE, BREATH, PRACTICE);
    private static final List<String> WINDOWS = List.of(SURFACE, PREFIX + "muscles", PREFIX + "organs",
            PREFIX + "meridians", BREATH, PRACTICE, OVERVIEW, PREFIX + "aura", PREFIX + "combat",
            PREFIX + "settings", "meridian_human_ui:reserves", PREFIX + "meridian_operation");
    private static final int COMPACT_COUNT = 7;
    private static final int SETTLE = 20;
    private static final int TIMEOUT = 4000;
    private static final int GUI_SCALE = 2;
    private static final int SMALL_WIDTH = 854;
    private static final int LARGE_HEIGHT = 720;
    private static final int SMALL_HEIGHT = 480;
    private static final int HOTBAR = 24;
    private static final int TOOLBAR = 28;
    private static final int SPAWN_SETTLE = 140;
    private static final int HALF_CROSSHAIR = 8;
    private enum Stage { JOIN, MIGRATE, LAYOUT_OPEN, LAYOUT_SHOT, OPEN, EXPANDED, SMALL, TABLE, PAIR,
        HUD, HIDDEN, OFFLINE, REJOIN, DONE }
    private final Integer layoutWidth = Integer.getInteger("layout.width");
    private final List<String> layoutIssues = new ArrayList<>();
    private final boolean fixedReadings = Boolean.getBoolean("layout.fixedReadings");
    private final String bodyRecording = System.getProperty("layout.bodyRecording");
    private BodySnapshot recordedBody;
    private boolean restoreMotion;
    private List<String> features;
    private WindowState operationParent;
    private Stage stage = Stage.JOIN;
    private int ticks;
    private int since;
    private int index;
    private int width;
    private BodyUiModule module;
    private ClientRuntime runtime;
    private WindowState window;
    private WindowState surface;
    private long previous;
    private String previousSession;
    private CompletableFuture<Void> prepared;
    private String pendingCapture;
    private String completedCapture;
    private int captureWait;

    public void register() {
        // 使用同一个 Fabric tick 线程读取游戏和窗口状态。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft c) {
        // 客户端 tick 与延迟截帧使用同一阶段，避免读取尚未绘制的界面。
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT, "验收超时 %s %s", stage, index);
            if (fixedReadings && stage == Stage.LAYOUT_SHOT) {
                stabilizeFrame(c, features.get(index));
            }
            if (pendingCapture != null) {
                flushCapture(c);
                return;
            }
            if (ticks - since < SETTLE || c.gui.overlay() != null) {
                return;
            }
            switch (stage) {
                case JOIN -> join(c);
                case MIGRATE -> migrate(c);
                case LAYOUT_OPEN -> openLayout(c);
                case LAYOUT_SHOT -> captureLayout(c);
                case OPEN -> open(c);
                case EXPANDED -> expanded(c);
                case SMALL -> small(c);
                case TABLE -> table(c);
                case PAIR -> pair(c);
                case HUD -> hud(c);
                case HIDDEN -> hidden(c);
                case OFFLINE -> offline(c);
                case REJOIN -> rejoin(c);
                default -> { }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(c, failure.toString());
        }
    }

    private void join(Minecraft c) {
        // 尺寸只改隔离客户端的 GLFW 窗口；解锁及状态效果走实际服务器命令。
        if (c.player == null || c.player.tickCount < SPAWN_SETTLE || !ClientProgression.synchronizedWithServer()) {
            return;
        }
        module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        runtime = InventoryUiClient.runtime();
        if (layoutWidth == null) {
            runtime.uiVersion(UiVersion.MODERN);
        } else if ("classic".equals(System.getProperty("meridian.uiVersion"))) {
            runtime.uiVersion(UiVersion.CLASSIC);
            runtime.background("kiui:night_meridian");
            if (fixedReadings && !EffectSettings.reducedMotion()) {
                EffectSettings.toggleMotion();
                restoreMotion = true;
            }
        } else {
            checkState(runtime.uiVersion() == UiVersion.MODERN, "新配置没有使用新版");
        }
        c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        width = Integer.getInteger("content.width", SMALL_WIDTH);
        if (layoutWidth != null) {
            width = layoutWidth;
        }
        int height = width == SMALL_WIDTH ? SMALL_HEIGHT : LARGE_HEIGHT;
        GLFW.glfwSetWindowSize(c.getWindow().handle(), width, height);
        c.options.guiScale().set(GUI_SCALE);
        c.resizeGui();
        prepared = prepareBody(c);
        next(Stage.MIGRATE);
    }

    private CompletableFuture<Void> prepareBody(Minecraft client) {
        // 用正式服务器命令设置每种窗口都能读取的同一具身体。
        var commands = new ArrayList<>(List.of("meridian progression unlock",
                "meridian opening complete @s left_lung_meridian"));
        if (fixedReadings) {
            // 两次截帧在同一位置读环境，空库存没有随时间结算的在途量。
            commands.add("tp @s -6454316.5 -60 2821320.5");
        } else {
            commands.add(FILL_QI);
        }
        commands.addAll(List.of("gamemode survival @s", "damage @s 3 minecraft:fall",
                "meridian debug fatigue set @s left_biceps_brachii 0.75",
                "effect give @s minecraft:speed infinite"));
        return runCommands(client, commands);
    }

    private CompletableFuture<Void> runCommands(Minecraft client, List<String> commands) {
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        return CompletableFuture.runAsync(() -> {
            var source = server.getPlayerList().getPlayer(id).createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.OWNER);
            for (String command : commands) {
                server.getCommands().performPrefixedCommand(source, command);
            }
        }, server);
    }

    private Set<String> hudFeatures(Minecraft c) {
        // 只取实际可画的新连接投影，等待与拒绝的 scope 不在其中。
        return hudCompacts(c).stream().map(CompactView::feature).collect(Collectors.toSet());
    }

    private List<CompactView> hudCompacts(Minecraft c) {
        var viewport = c.getWindow();
        return runtime.hudCompacts(viewport.getGuiScaledWidth(), viewport.getGuiScaledHeight());
    }

    private String type(String id) {
        return runtime.catalog().features().get(id).windowType();
    }

    private void migrate(Minecraft c) {
        if (!prepared.isDone() || !ClientProgression.canUseMiddleJiao() || module.snapshot() == null) {
            return;
        }
        prepared.join();
        if (layoutWidth != null) {
            // 从实际装配目录逐项开窗，每项单独截取默认位置。
            features = new ArrayList<>(runtime.catalog().features().keySet().stream().sorted().toList());
            if (fixedReadings && features.remove(BodyFeature.OPERATION.id)) {
                // 操作窗需要真元，其余窗口先用同一份空库存拍完。
                features.add(BodyFeature.OPERATION.id);
            }
            runtime.motion(false /* enabled */);
            runtime.openWorkspace();
            next(Stage.LAYOUT_OPEN);
            return;
        }
        var migrated = runtime.pinnedCompacts();
        checkState(migrated.equals(LEGACY), "旧锁映射错误 %s", migrated);
        if (hudFeatures(c).size() != LEGACY.size()) {
            return;
        }
        if (!capture(c, "legacy")) {
            return;
        }
        System.out.println("S5_MIGRATION " + migrated);
        List.copyOf(runtime.workspace().windowBar()).forEach(w -> runtime.workspace().windows().close(w.key()));
        runtime.openWorkspace();
        next(Stage.OPEN);
    }

    private void open(Minecraft c) {
        // 概况先在体表勾选，其他窗口均由正式功能入口创建。
        if (index == WINDOWS.size()) {
            positionPair(c);
            return;
        }
        String id = WINDOWS.get(index);
        if (!runtime.workspace().canOpen(id)) {
            checkState(id.equals(PREFIX + "meridian_operation"), "入口意外不可用 %s", id);
            System.out.println("S5_NEEDS_TARGET " + id);
            index++;
            return;
        }
        if (id.equals(OVERVIEW)) {
            surface = runtime.open(SURFACE, null);
            clickToggle(c);
            window = runtime.workspace().windowBar().stream().filter(w -> w.key().windowType().equals(type(id)))
                    .findFirst().orElseThrow();
        } else {
            window = runtime.open(id, null);
        }
        next(Stage.EXPANDED);
    }

    private void openLayout(Minecraft c) {
        // 每次都从空工作台开始，测到的是该入口在当前分辨率下的缺省矩形。
        if (index == features.size()) {
            checkState(layoutIssues.isEmpty(), "窗口布局异常 %s", layoutIssues);
            finish(c, null);
            return;
        }
        String id = features.get(index);
        if (id.equals(BodyFeature.OPERATION.id)) {
            // 先从经脉窗订阅可开拓目标，权威目录到达后操作入口才可用。
            if (operationParent == null) {
                operationParent = runtime.open(BodyFeature.MERIDIAN.id, null);
                if (fixedReadings) {
                    prepared = runCommands(c, List.of(FILL_QI));
                }
            }
            if (!prepared.isDone() || !runtime.workspace().canOpen(id)) {
                return;
            }
            prepared.join();
        }
        window = runtime.open(id, null);
        next(Stage.LAYOUT_SHOT);
    }

    private void stabilizeFrame(Minecraft client, String feature) throws java.io.IOException {
        client.gui.toastManager().clear();
        if (bodyRecording != null && (feature.equals("meridian_human_ui:reserves")
                || feature.equals(BodyFeature.MERIDIAN.id) || feature.equals(PRACTICE))) {
            replayBody();
        }
        if (feature.equals("bs3d:open")) {
            // 背包人台从本地玩家的 tickCount 取待机摆臂相位。
            client.player.tickCount = SPAWN_SETTLE;
        } else if (feature.equals(BREATH)) {
            showFixedBreath();
        }
    }

    private void replayBody() throws java.io.IOException {
        // 重放同一帧的实际库存，让储备读数和随库存变化的经脉亮度使用相同输入。
        var live = module.snapshot();
        if (recordedBody == null) {
            Path file = Path.of(bodyRecording);
            if (Files.exists(file)) {
                recordedBody = BodySnapshot.parseFrom(Files.readAllBytes(file));
            } else {
                Files.write(file, live.toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW);
                recordedBody = live;
            }
        }
        var frame = recordedBody.toBuilder()
                .setId(live.getId())
                .setRevision(live.getRevision())
                .build();
        module.update(frame);
    }

    private void showFixedBreath() {
        // 固定空地的读数，不让服务端随机粒子与徽记轨道改变两次截帧的差值。
        var actual = module.gatheringView();
        if (actual != null && actual.hasBreathing()) {
            var breath = actual.getBreathing().toBuilder()
                    .clearParticles()
                    .setAirLiters(0)
                    .setCapacityLiters(0)
                    .setStaminaRatio(1)
                    .setAttentionRatio(1)
                    .build();
            var frame = actual.toBuilder()
                    .setBreathing(breath)
                    .setEnvironment(GatheringEnvironment.getDefaultInstance())
                    .build();
            module.cultivationPanel().update(frame, false /* pending */, "");
        }
    }

    private void captureLayout(Minecraft c) {
        String id = features.get(index);
        if (!capture(c, id.replace(':', '-') + "-expanded")) {
            return;
        }
        Rect bounds = window.bounds();
        System.out.println("WINDOW_LAYOUT " + width + " " + id + " " + bounds);
        if (runtime.uiVersion() == UiVersion.MODERN
                && (bounds.y() < TOOLBAR || bounds.y() + bounds.height() > c.gui.screen().height)) {
            layoutIssues.add(id + " " + bounds);
        }
        if (id.equals(BodyFeature.MERIDIAN.id)) {
            // 两个真实页签用绘制时的当前语言取词。
            var panel = module.meridianPanel().root();
            checkState(panel.childById(MeridianTab.class, "meridian-tab-scene") != null
                    && panel.childById(MeridianTab.class, "meridian-tab-data") != null, "经脉窗缺页签");
            String scene = DataText.get("tab.scene");
            String data = DataText.get("tab.data");
            String language = System.getProperty("layout.language", "zh_cn");
            if (language.equals("en_us")) {
                checkState(scene.equals("Meridians") && data.equals("Data"), "英文经脉页签错误 %s / %s", scene, data);
            } else {
                checkState(scene.equals("经脉") && data.equals("数据"), "中文经脉页签错误 %s / %s", scene, data);
            }
            System.out.println("MERIDIAN_TABS " + language + " " + scene + " / " + data);
        }
        runtime.workspace().windows().close(window.key());
        if (operationParent != null) {
            runtime.workspace().windows().close(operationParent.key());
            operationParent = null;
        }
        index++;
        next(Stage.LAYOUT_OPEN);
    }

    private void clickToggle(Minecraft c) {
        // 在体表窗口里按右键，再点菜单里的「概况小窗」；两下都从屏幕的鼠标入口进去。
        var root = module.surfaceView().orElseThrow().root();
        var open = new MouseButtonEvent(root.x() + root.width() / 2.0, root.y() + root.height() / 2.0,
                new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0));
        c.gui.screen().mouseClicked(open, false /* doubleClick */);
        c.gui.screen().mouseReleased(open);
        var row = runtime.contextMenu().row("overview", c.gui.screen().width, c.gui.screen().height).orElseThrow();
        var pick = new MouseButtonEvent(row.left() + row.width() / 2.0, row.top() + row.height() / 2.0,
                new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        c.gui.screen().mouseClicked(pick, false /* doubleClick */);
        c.gui.screen().mouseReleased(pick);
    }

    private void expanded(Minecraft c) {
        String id = WINDOWS.get(index);
        if (id.equals(SURFACE)) {
            var notes = MeridianSceneExtension.required().surfaceReadout(module.snapshot()).notes();
            // 只写整年、零头舍去：十八岁，寿元还剩六十一年多。
            checkState(notes.getLast().endsWith("18/61 年"), "体表窗的年龄或寿元不对 %s", notes);
        }
        if (!capture(c, shortId() + "-expanded")) {
            return;
        }
        if (index >= COMPACT_COUNT) {
            checkState(
                    !runtime.workspace().windows().minimize(window.key()),
                    "仅展开窗口出现收小能力 %s",
                    id);
            runtime.workspace().windows().close(window.key());
            index++;
            next(Stage.OPEN);
            return;
        }
        if (!id.equals(OVERVIEW)) {
            checkState(runtime.workspace().windows().minimize(window.key()), "未登记摘要 %s", id);
        }
        runtime.pinWindow(window.key(), true /* pinned */);
        if (id.equals(OVERVIEW)) {
            runtime.workspace().windows().close(surface.key());
            checkState(!window.closed() && window.pinned(), "关闭体表影响概况锁");
        }
        next(Stage.SMALL);
    }

    private void small(Minecraft c) {
        // 宿主开着时从观察状态验证 LIVE；随后关闭宿主单独拍 HUD。
        if (!window.minimized() || !window.pinned()) {
            throw new IllegalStateException("摘要未锁定 " + shortId());
        }
        if (!capture(c, shortId() + "-compact")) {
            return;
        }
        if (index == 0) {
            runtime.compactVariant(SURFACE, "body_table");
            next(Stage.TABLE);
            return;
        }
        index++;
        next(Stage.OPEN);
    }

    private void table(Minecraft c) {
        if (!capture(c, "body-table")) {
            return;
        }
        runtime.compactVariant(SURFACE, "body_doll");
        index++;
        next(Stage.OPEN);
    }

    private void positionPair(Minecraft c) {
        // 两个展开窗分置于准星两侧，固定意图跨形态保留。
        var first = runtime.open(SURFACE, null);
        runtime.pinWindow(first.key(), true /* pinned */);
        var second = runtime.open(BREATH, null);
        int w = c.getWindow().getGuiScaledWidth();
        move(first, 1);
        move(second, w - second.bounds().width() - 1);
        for (var state : List.of(first, second)) {
            var r = state.bounds();
            int cy = c.getWindow().getGuiScaledHeight() / 2;
            checkState(r.x() + r.width() <= w / 2 - HALF_CROSSHAIR || r.x() >= w / 2 + HALF_CROSSHAIR
                    || r.y() + r.height() < cy, "双窗遮住准星 %s", r);
            checkState(
                    r.y() + r.height() <= c.getWindow().getGuiScaledHeight() - HOTBAR,
                    "双窗遮住快捷栏 %s",
                    r);
        }
        next(Stage.PAIR);
    }

    private void move(WindowState state, int targetX) {
        var manager = runtime.workspace().windows();
        var r = state.bounds();
        manager.beginDrag(state.key(), r.x(), r.y());
        manager.dragTo(targetX, TOOLBAR + 1);
        manager.endDrag();
    }

    private void pair(Minecraft c) {
        if (!capture(c, "two-expanded")) {
            return;
        }
        c.gui.setScreen(null);
        next(Stage.HUD);
    }

    private void hud(Minecraft c) {
        if (hudFeatures(c).size() != COMPACT_COUNT) {
            return;
        }
        int guiWidth = c.getWindow().getGuiScaledWidth();
        int guiHeight = c.getWindow().getGuiScaledHeight();
        var projections = runtime.hudCompacts(guiWidth, guiHeight);
        checkState(projections.size() == COMPACT_COUNT, "HUD 小窗矩形不足 %s", projections.size());
        var statusArea = StatusLayout.vanillaStatusBarArea(guiWidth, guiHeight);
        Rect statusBar = new Rect(statusArea.left(), statusArea.top(), statusArea.width(), statusArea.height());
        checkState(projections.stream().map(p -> p.bounds()).noneMatch(bounds -> intersects(bounds, statusBar)),
                "小窗覆盖原版状态栏 %s", statusBar);
        for (int first = 0; first < projections.size(); first++) {
            Rect a = projections.get(first).bounds();
            checkState(a.x() >= 0 && a.y() >= 0
                    && a.x() + a.width() <= guiWidth
                    && a.y() + a.height() <= guiHeight - HOTBAR,
                    "小窗越过 HUD 安全区 %s", a);
            for (int second = first + 1; second < projections.size(); second++) {
                Rect b = projections.get(second).bounds();
                checkState(!intersects(a, b), "小窗矩形重叠 %s %s", a, b);
            }
        }
        Rect crosshair = new Rect(c.getWindow().getGuiScaledWidth() / 2 - HALF_CROSSHAIR,
                c.getWindow().getGuiScaledHeight() / 2 - HALF_CROSSHAIR,
                HALF_CROSSHAIR * 2, HALF_CROSSHAIR * 2);
        checkState(projections.stream().map(p -> p.bounds()).noneMatch(bounds -> intersects(bounds, crosshair)),
                "小窗压住准星 %s", crosshair);
        var frame = StatusIcons.lastLayout();
        checkState(!frame.cells().isEmpty(), "缺少状态图标");
        checkState(frame.cells().stream().noneMatch(cell -> frame.blocked().stream().anyMatch(b ->
                cell.left() < b.right() && b.left() < cell.right() && cell.top() < b.bottom()
                        && b.top() < cell.bottom())), "状态图标遮住小窗");
        previous = module.snapshot().getRevision();
        previousSession = module.gatheringView().getBreathing().getSessionId();
        CompactReadings.record(c, module, runtime);
        System.out.println("S5_HUD " + hudFeatures(c) + " revision=" + previous);
        if (!capture(c, "hud")) {
            return;
        }
        c.gui.hud.toggle();
        next(Stage.HIDDEN);
    }

    private void hidden(Minecraft c) {
        checkState(c.gui.hud.isHidden() && hudFeatures(c).isEmpty(), "F1 仍有小窗投影");
        if (!capture(c, "f1")) {
            return;
        }
        c.gui.hud.toggle();
        c.disconnectWithSavingScreen();
        next(Stage.OFFLINE);
    }

    private void offline(Minecraft c) {
        if (c.getSingleplayerServer() == null) {
            c.createWorldOpenFlows().openWorld("inner-vision-test", () -> {});
            next(Stage.REJOIN);
        }
    }

    private void rejoin(Minecraft c) {
        // 不打开 I，等待每个业务单例由新观察恢复；吐纳会话号必须已更换。
        if (c.player == null || c.gui.screen() != null) {
            return;
        }
        var expected = Set.copyOf(WINDOWS.subList(0, COMPACT_COUNT));
        var restored = hudFeatures(c);
        if (!restored.equals(expected)) {
            checkState(ticks - since < TIMEOUT / 4, "重进恢复缺窗，期待 %s，实际 %s", expected, restored);
            return;
        }
        checkState(module.surfacePanel() == null && module.cultivationPanel() == null && module.practicePanel() == null,
                "恢复创建了输入组件");
        var breath = module.gatheringView().getBreathing();
        checkState(
                !breath.getActive() && !breath.getSessionId().equals(previousSession),
                "恢复沿用了旧吐纳会话");
        checkState(module.snapshot().getRevision() >= previous, "身体版本倒退");
        System.out.println("S5_REJOIN " + hudFeatures(c) + " revision=" + module.snapshot().getRevision()
                + " previousSession=" + previousSession + " newSession=" + breath.getSessionId());
        if (!capture(c, "rejoin")) {
            return;
        }
        finish(c, null);
    }

    private String shortId() {
        return WINDOWS.get(index).split(":")[1];
    }

    private void next(Stage next) {
        stage = next;
        since = ticks;
    }

    private boolean capture(Minecraft c, String name) {
        if (name.equals(completedCapture)) {
            completedCapture = null;
            return true;
        }
        if (pendingCapture == null) {
            moveCursorAway(c);
            pendingCapture = name;
            captureWait = 0;
        }
        return false;
    }

    private void flushCapture(Minecraft c) {
        if (captureWait++ < SETTLE) {
            return;
        }
        String name = pendingCapture;
        Screenshot.grab(c.gameDirectory, "s5-" + width + "-" + name + ".png",
                c.gameRenderer.mainRenderTarget(), 1, message -> {});
        pendingCapture = null;
        completedCapture = name;
    }

    private static void moveCursorAway(Minecraft c) {
        long handle = c.getWindow().handle();
        double scale = c.getWindow().getGuiScale();
        GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(handle, null);
        GLFW.glfwSetCursorPosCallback(handle, callback);
        if (callback != null) {
            callback.invoke(handle, scale, scale);
        } else {
            GLFW.glfwSetCursorPos(handle, scale, scale);
        }
    }

    private static boolean intersects(Rect a, Rect b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }

    private void finish(Minecraft c, String failure) {
        stage = Stage.DONE;
        if (restoreMotion) {
            EffectSettings.toggleMotion();
        }
        try {
            Files.writeString(Path.of("../inner-vision-results.txt"),
                    "success=" + (failure == null) + "\nfailure=" + failure + "\n");
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        c.stop();
    }
}
