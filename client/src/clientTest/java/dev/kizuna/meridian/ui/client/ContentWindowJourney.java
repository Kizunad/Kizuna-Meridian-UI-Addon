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
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private enum Stage { JOIN, MIGRATE, OPEN, EXPANDED, SMALL, TABLE, PAIR, HUD, HIDDEN, OFFLINE, REJOIN, DONE }
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
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT, "验收超时 %s %s", stage, index);
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
        runtime.uiVersion(UiVersion.MODERN);
        c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        width = Integer.getInteger("content.width", SMALL_WIDTH);
        int height = width == SMALL_WIDTH ? SMALL_HEIGHT : LARGE_HEIGHT;
        GLFW.glfwSetWindowSize(c.getWindow().handle(), width, height);
        c.options.guiScale().set(GUI_SCALE);
        c.resizeGui();
        var server = c.getSingleplayerServer();
        var id = c.player.getUUID();
        prepared = CompletableFuture.runAsync(() -> {
            var source = server.getPlayerList().getPlayer(id).createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.OWNER);
            for (String command : List.of("meridian progression unlock",
                    "meridian opening complete @s left_lung_meridian", "meridian qi fill", "gamemode survival @s",
                    "damage @s 3 minecraft:fall", "meridian debug fatigue set @s left_biceps_brachii 0.75",
                    "effect give @s minecraft:speed infinite")) {
                server.getCommands().performPrefixedCommand(source, command);
            }
        }, server);
        next(Stage.MIGRATE);
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
        try {
            Files.writeString(Path.of("../inner-vision-results.txt"),
                    "success=" + (failure == null) + "\nfailure=" + failure + "\n");
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        c.stop();
    }
}
