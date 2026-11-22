package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.CompactView;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.workspace.IconStyle;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.progression.client.ClientProgression;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;

import static com.google.common.base.Preconditions.checkState;

/**
 * 新版界面七个小窗全部锁到游戏画面后的验收：小窗互不相交，不压准星、快捷栏和原版状态栏，文字原字号且不出小窗；
 * 白天、夜晚、雪地各截一张，换一组样式再截一张；窄屏那一轮接着把三种图标风格的标题栏各截一张，并查一张不存在的图标。
 */
public final class HudColumnJourney {
    private static final String PREFIX = "meridian_ui:";
    private static final String SURFACE = PREFIX + "body";
    private static final String ORGANS = PREFIX + "organs";
    private static final List<String> COMPACTS = List.of(PREFIX + "cultivation", PREFIX + "practice", SURFACE,
            PREFIX + "meridians", PREFIX + "overview", PREFIX + "muscles", ORGANS);
    private static final List<String> PREPARE = List.of("meridian progression unlock",
            "meridian opening complete @s left_lung_meridian", "meridian qi fill", "gamemode survival @s",
            "meridian debug fatigue set @s left_biceps_brachii 0.75",
            "effect give @s minecraft:speed infinite", "weather clear", "time set noon");
    private static final List<String> INJURE = List.of("damage @s 6 minecraft:fall", "damage @s 4 minecraft:generic");
    private static final List<String> NIGHT = List.of("time set midnight");
    private static final List<String> SNOW = List.of("time set noon",
            "fill ~-12 ~-1 ~-12 ~12 ~-1 ~12 minecraft:snow_block", "tp @s ~ ~ ~ 0 50");
    private static final List<String> LEVEL = List.of("tp @s ~ ~ ~ 0 0");
    private static final Identifier ABSENT_ICON = Identifier.fromNamespaceAndPath("meridian_ui", "absent");
    private static final List<String> TITLE_BAR_ICONS = List.of("collapse", "expand", "lock", "unlock", "close");
    private static final int SETTLE = 20;
    private static final int SCENE_SETTLE = 80;
    private static final int INJURY_SETTLE = 300;
    private static final int TIMEOUT = 4000;
    private static final int GUI_SCALE = 2;
    private static final int NARROW_WIDTH = 854;
    private static final int NARROW_HEIGHT = 480;
    private static final int WIDE_HEIGHT = 720;
    private static final int HOTBAR = 24;
    private static final int SPAWN_SETTLE = 140;
    private static final int HALF_CROSSHAIR = 8;
    private enum Stage { JOIN, READY, LOCK, DAY, NIGHT, SNOW, VARIANTS, EXPAND, ICONS, MISSING, DONE }
    private Stage stage = Stage.JOIN;
    private int ticks;
    private int since;
    private int index;
    private int width;
    private BodyUiModule module;
    private ClientRuntime runtime;
    private CompletableFuture<Void> commands;
    private String pendingCapture;
    private String completedCapture;
    private int captureWait;

    public void register() {
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
            boolean waiting = ticks - since < SETTLE || c.gui.overlay() != null;
            if (waiting || (commands != null && !commands.isDone())) {
                return;
            }
            switch (stage) {
                case JOIN -> join(c);
                case READY -> ready(c);
                case LOCK -> lock(c);
                case DAY -> day(c);
                case NIGHT -> scene(c, "hud-night", Stage.SNOW, SNOW);
                case SNOW -> scene(c, "hud-snow", Stage.VARIANTS, LEVEL);
                case VARIANTS -> variants(c);
                case EXPAND -> expand(c);
                case ICONS -> icons(c);
                case MISSING -> missing(c);
                default -> { }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(c, failure.toString());
        }
    }

    private void run(Minecraft c, List<String> lines) {
        // 命令在服务端线程执行，不往聊天栏回显，截图里没有命令反馈。
        var server = c.getSingleplayerServer();
        var id = c.player.getUUID();
        commands = CompletableFuture.runAsync(() -> {
            var source = server.getPlayerList().getPlayer(id).createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.OWNER)
                    .withSuppressedOutput();
            for (String command : lines) {
                server.getCommands().performPrefixedCommand(source, command);
            }
        }, server);
    }

    private void join(Minecraft c) {
        if (c.player == null || c.player.tickCount < SPAWN_SETTLE || !ClientProgression.synchronizedWithServer()) {
            return;
        }
        module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        runtime = InventoryUiClient.runtime();
        runtime.uiVersion(UiVersion.MODERN);
        c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        width = Integer.getInteger("hud.width", NARROW_WIDTH);
        int height = WIDE_HEIGHT;
        if (width == NARROW_WIDTH) {
            height = NARROW_HEIGHT;
        }
        GLFW.glfwSetWindowSize(c.getWindow().handle(), width, height);
        c.options.guiScale().set(GUI_SCALE);
        // 状态效果的粒子在第一人称里会贴着镜头糊成大色块，验收截图里关掉；状态图标照常显示。
        c.options.particles().set(ParticleStatus.MINIMAL);
        c.resizeGui();
        run(c, PREPARE);
        next(Stage.READY);
    }

    private void ready(Minecraft c) {
        if (!ClientProgression.canUseMiddleJiao() || module.snapshot() == null) {
            return;
        }
        checkState(runtime.iconStyle() == IconStyle.DEFAULT, "图标风格缺省不是琉璃细线 %s", runtime.iconStyle());
        runtime.openWorkspace();
        next(Stage.LOCK);
    }

    private void lock(Minecraft c) {
        // 每个窗口都从正式的功能入口打开，再收小、锁住；一拍一个，让只读观察各自就绪。
        if (index == COMPACTS.size()) {
            c.gui.setScreen(null);
            run(c, INJURE);
            index = 0;
            next(Stage.DAY);
            return;
        }
        String id = COMPACTS.get(index);
        checkState(runtime.workspace().canOpen(id), "入口不可用 %s", id);
        var window = runtime.open(id, null);
        checkState(runtime.workspace().windows().minimize(window.key()), "未登记摘要 %s", id);
        checkState(runtime.pinWindow(window.key(), true /* pinned */), "锁不上 %s", id);
        index++;
        next(Stage.LOCK);
    }

    private List<CompactView> hudCompacts(Minecraft c) {
        var viewport = c.getWindow();
        return runtime.hudCompacts(viewport.getGuiScaledWidth(), viewport.getGuiScaledHeight());
    }

    private void verify(Minecraft c, String label) {
        int guiWidth = c.getWindow().getGuiScaledWidth();
        int guiHeight = c.getWindow().getGuiScaledHeight();
        var projections = hudCompacts(c);
        checkState(projections.size() == COMPACTS.size(), "游戏画面上的小窗不足 %s", projections.size());
        var statusArea = StatusLayout.vanillaStatusBarArea(guiWidth, guiHeight);
        Rect statusBar = new Rect(statusArea.left(), statusArea.top(), statusArea.width(), statusArea.height());
        Rect crosshair = new Rect(guiWidth / 2 - HALF_CROSSHAIR, guiHeight / 2 - HALF_CROSSHAIR,
                HALF_CROSSHAIR * 2, HALF_CROSSHAIR * 2);
        for (int first = 0; first < projections.size(); first++) {
            Rect a = projections.get(first).bounds();
            boolean inside = a.x() >= 0 && a.y() >= 0 && a.x() + a.width() <= guiWidth
                    && a.y() + a.height() <= guiHeight - HOTBAR;
            checkState(inside, "小窗越过安全区或压住快捷栏 %s", a);
            checkState(!intersects(a, statusBar), "小窗压住原版状态栏 %s", a);
            checkState(!intersects(a, crosshair), "小窗压住准星 %s", a);
            for (int second = first + 1; second < projections.size(); second++) {
                Rect b = projections.get(second).bounds();
                checkState(!intersects(a, b), "小窗矩形重叠 %s %s", a, b);
            }
        }
        var frame = StatusIcons.lastLayout();
        checkState(!frame.cells().isEmpty(), "缺少状态图标");
        checkState(frame.cells().stream().noneMatch(cell -> frame.blocked().stream().anyMatch(b ->
                cell.left() < b.right() && b.left() < cell.right() && cell.top() < b.bottom()
                        && b.top() < cell.bottom())), "状态图标遮住小窗");
        CompactReadings.record(c, module, runtime);
        for (var view : projections) {
            System.out.println("R2_HUD " + width + " " + label + " " + view.feature() + " " + view.variant() + " "
                    + view.bounds());
        }
    }

    private void day(Minecraft c) {
        // 受伤时聊天栏会出一段提示、身上有一阵受伤的效果，等它们散了再拍。
        if (ticks - since < INJURY_SETTLE || hudCompacts(c).size() != COMPACTS.size()) {
            return;
        }
        if (completedCapture == null) {
            verify(c, "day");
        }
        if (!capture(c, "hud-day")) {
            return;
        }
        if (width != NARROW_WIDTH) {
            next(Stage.VARIANTS);
            return;
        }
        run(c, NIGHT);
        next(Stage.NIGHT);
    }

    private void scene(Minecraft c, String name, Stage following, List<String> after) {
        if (ticks - since < SCENE_SETTLE) {
            return;
        }
        if (!capture(c, name)) {
            return;
        }
        run(c, after);
        next(following);
    }

    private void variants(Minecraft c) {
        // 体表换成全表、器官换成全部，同一组断言再过一遍。
        if (ticks - since < SCENE_SETTLE) {
            return;
        }
        if (completedCapture == null) {
            runtime.compactVariant(SURFACE, "body_table");
            runtime.compactVariant(ORGANS, "organs_all");
            verify(c, "variants");
        }
        if (!capture(c, "hud-variants")) {
            return;
        }
        runtime.compactVariant(SURFACE, "body_doll");
        runtime.compactVariant(ORGANS, "organs_active");
        if (width != NARROW_WIDTH) {
            finish(c, null);
            return;
        }
        next(Stage.EXPAND);
    }

    private void expand(Minecraft c) {
        // 体表展开成窗口：标题栏上有收小、锁、关闭三个按钮，其余六个小窗留在整屏界面里。
        runtime.openWorkspace();
        var window = runtime.open(SURFACE, null);
        checkState(!window.minimized(), "体表没有展开");
        index = 0;
        next(Stage.ICONS);
    }

    private void icons(Minecraft c) {
        var styles = IconStyle.values();
        if (index == styles.length) {
            checkState(runtime.missingIcons().isEmpty(), "随包的图标缺图 %s", runtime.missingIcons());
            runtime.iconStyle(IconStyle.DEFAULT);
            next(Stage.MISSING);
            return;
        }
        var style = styles[index];
        if (completedCapture == null) {
            runtime.iconStyle(style);
            for (String name : TITLE_BAR_ICONS) {
                var texture = runtime.icon(Identifier.fromNamespaceAndPath("kizuna_inventory_ui", name));
                checkState(texture.getPath().contains("/" + style.id() + "/"), "按钮没按风格取图 %s", texture);
                System.out.println("R2_ICONS " + style.id() + " " + texture);
            }
        }
        if (!capture(c, "icons-" + style.id())) {
            return;
        }
        index++;
        next(Stage.ICONS);
    }

    private void missing(Minecraft c) {
        // 要一张哪种风格都没有的图标：运行时照样给出当前风格的路径，并把它记进缺图表。
        if (completedCapture == null) {
            var texture = runtime.icon(ABSENT_ICON);
            checkState(runtime.missingIcons().equals(Set.of(texture)), "缺图没有报出 %s", texture);
            System.out.println("R2_ICON_MISSING " + texture);
            c.gui.setScreen(null);
        }
        if (!capture(c, "icons-missing")) {
            return;
        }
        finish(c, null);
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
        Screenshot.grab(c.gameDirectory, "r2-" + width + "-" + name + ".png",
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
