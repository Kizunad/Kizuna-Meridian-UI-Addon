package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import dev.kizuna.meridian.ui.api.SurfaceReadout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;

import static com.google.common.base.Preconditions.checkState;

/**
 * 新版体表窗口的验收：受伤几处就列几行、每行有「当前/上限」和 HP 条；鼠标移进窗口出现右键提示；
 * 用真实的右键和左键从菜单打开、关掉概况小窗；带一个伤势效果看脚注；再开、关一个图层看正文换成立体模型又换回来。
 */
public final class SurfaceWindowJourney {
    private static final String PREFIX = "meridian_ui:";
    private static final String SURFACE = PREFIX + "body";
    private static final String OVERVIEW_TYPE = PREFIX + "overview_window";
    private static final String OVERVIEW_ACTION = "overview";
    private static final String SKELETON = "skeleton";
    private static final String SKELETON_ACTION = "layer:" + SKELETON;
    private static final String INJURY_NAMESPACE = "meridian_vanilla_healing";
    private static final String READING = "\\d+/\\d+";
    private static final List<String> PREPARE = List.of("meridian progression unlock", "meridian qi fill",
            "gamemode survival @s", "weather clear", "time set noon");
    private static final List<String> INJURE = List.of("damage @s 5 minecraft:fall", "damage @s 4 minecraft:generic",
            "damage @s 3 minecraft:cactus", "damage @s 3 minecraft:fall");
    private static final String LANGUAGE = "zh_cn";
    private static final List<String> CURE = List.of("effect clear @s");
    private static final List<String> AFFLICT = List.of("effect give @s meridian_vanilla_healing:leg_injury 600 1");
    private static final int SETTLE = 20;
    private static final int INJURY_SETTLE = 60;
    private static final int TIMEOUT = 4000;
    private static final int NARROW_WIDTH = 854;
    private static final int NARROW_HEIGHT = 480;
    private static final int WIDE_HEIGHT = 720;
    private static final int SPAWN_SETTLE = 140;
    private static final int SHEET_WIDTH = 150;
    private static final int SHEET_HEIGHT = 104;
    private static final int MODEL_WIDTH = 194;
    private static final int MODEL_HEIGHT = 174;
    private static final int TEXTS_PER_ROW = 2;
    private static final int BLOCKS_PER_ROW = 2;
    private enum Stage {
        JOIN, READY, INJURE, OPEN, SHEET, HOVER, MENU, OVERVIEW_ON, OVERVIEW_SHOWN, OVERVIEW_OFF, AFFLICT, FOOTNOTE,
        LAYER_MENU, LAYER_ON, MODEL, LAYER_OFF, DONE
    }
    private Stage stage = Stage.JOIN;
    private int ticks;
    private int since;
    private int width;
    private int injuries;
    private BodyUiModule module;
    private ClientRuntime runtime;
    private WindowState window;
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
            checkState(++ticks < TIMEOUT, "验收超时 %s", stage);
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
                case INJURE -> injure(c);
                case OPEN -> open(c);
                case SHEET -> sheet(c);
                case HOVER -> hover(c);
                case MENU -> menu(c);
                case OVERVIEW_ON -> overviewOn(c);
                case OVERVIEW_SHOWN -> overviewShown(c);
                case OVERVIEW_OFF -> overviewOff(c);
                case LAYER_MENU -> layerMenu(c);
                case LAYER_ON -> layerOn(c);
                case MODEL -> model(c);
                case LAYER_OFF -> layerOff(c);
                case AFFLICT -> afflict(c);
                case FOOTNOTE -> footnote(c);
                default -> { }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(c, failure.toString());
        }
    }

    private void run(Minecraft c, List<String> lines) {
        // 命令在服务端线程执行，不往聊天栏回显。
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
        width = Integer.getInteger("surface.width", NARROW_WIDTH);
        int height = WIDE_HEIGHT;
        if (width == NARROW_WIDTH) {
            height = NARROW_HEIGHT;
        }
        GLFW.glfwSetWindowSize(c.getWindow().handle(), width, height);
        c.options.guiScale().set(Integer.getInteger("surface.scale", 0));
        c.options.particles().set(ParticleStatus.MINIMAL);
        c.resizeGui();
        // 菜单和提示走翻译键，隔离客户端缺省是英文；换成中文后等资源重载完（重载时有遮罩，tick 里会等）。
        c.options.languageCode = LANGUAGE;
        c.getLanguageManager().setSelected(LANGUAGE);
        c.reloadResourcePacks();
        run(c, PREPARE);
        next(Stage.READY);
    }

    private void ready(Minecraft c) {
        if (!ClientProgression.canInspect() || module.snapshot() == null) {
            return;
        }
        next(Stage.INJURE);
    }

    private void injure(Minecraft c) {
        // 一拍只打一下：刚受过伤的那半秒里再来的伤害不算数。
        if (injuries == Integer.getInteger("surface.injuries", INJURE.size())) {
            // 摔伤可能自己带出伤势效果；先清掉，伤势那一行后面单独验。
            run(c, CURE);
            next(Stage.OPEN);
            return;
        }
        run(c, List.of(INJURE.get(injuries)));
        injuries++;
        next(Stage.INJURE);
    }

    private long woundedParts() {
        // 直接数身体快照里 HP 不满的部位，不经过窗口用的那份投影。
        var body = module.snapshot();
        return body.getStructure().getPartsList().stream().filter(part -> body.getPartStatesList().stream()
                .filter(state -> state.getId().equals(part.getId()))
                .mapToDouble(PartState::getHp).anyMatch(hp -> hp < part.getMaxHp())).count();
    }

    private void open(Minecraft c) {
        // 受伤那一下镜头会晃，等它过去再开窗；部位的 HP 会慢慢回，不能等太久。
        if (ticks - since < INJURY_SETTLE || woundedParts() == 0) {
            return;
        }
        runtime.openWorkspace();
        window = runtime.open(SURFACE, null);
        checkState(!window.minimized(), "体表没有展开");
        next(Stage.SHEET);
    }

    private ModernSurfacePanel view() {
        return module.surfaceView().orElseThrow();
    }

    private SurfaceReadout readout() {
        return MeridianSceneExtension.required().surfaceReadout(module.snapshot());
    }

    private void verifySheet(Minecraft c) {
        // 用游戏里的真字体把正文重放一遍：一处受伤部位一行，名字靠左、读数右端贴边、下面一条槽和一段填充。
        var bounds = window.bounds();
        checkState(bounds.width() == SHEET_WIDTH && bounds.height() == SHEET_HEIGHT, "体表窗尺寸不对 %s", bounds);
        checkState(!view().modelShown(), "体表窗一开就是立体模型");
        checkState(view().footnotes().isEmpty(), "没有伤势效果却有脚注 %s", view().footnotes());
        var readout = readout();
        long wounded = woundedParts();
        checkState(readout.wounds().size() == wounded, "受伤 %s 处，列了 %s 行", wounded, readout.wounds().size());
        int listWidth = view().listWidth();
        var probe = new SummaryProbe(c.font::width);
        SurfaceSheet.list(probe, listWidth, readout);
        checkState(probe.blocks().size() == wounded * BLOCKS_PER_ROW, "HP 条数目不对 %s", probe.blocks().size());
        for (int index = 0; index < readout.wounds().size(); index++) {
            var row = readout.wounds().get(index);
            var name = probe.texts().get(index * TEXTS_PER_ROW);
            var value = probe.texts().get(index * TEXTS_PER_ROW + 1);
            var track = probe.blocks().get(index * BLOCKS_PER_ROW);
            var fill = probe.blocks().get(index * BLOCKS_PER_ROW + 1);
            checkState(row.value().matches(READING), "读数不是「当前/上限」 %s %s", row.name(), row.value());
            checkState(value.x() + value.width() == listWidth, "读数没贴右边 %s", row.name());
            checkState(name.x() + name.width() < value.x(), "名字和读数相叠 %s", row.name());
            checkState(track.right() - track.left() == listWidth, "HP 条的槽不满宽 %s", row.name());
            checkState(fill.right() <= track.right() && fill.top() == track.top(), "HP 条出槽 %s", row.name());
            System.out.println("R3_SURFACE " + width + " " + row.name() + " " + row.value() + " "
                    + (fill.right() - fill.left()) + "/" + listWidth);
        }
        checkState(probe.texts().stream().allMatch(text -> text.x() + text.width() <= listWidth),
                "有文字画出了受伤部位那一栏");
        System.out.println("R3_SURFACE " + width + " notes=" + readout.notes() + " list="
                + SurfaceSheet.listHeight(readout) + "/" + view().listViewHeight() + " window=" + bounds);
    }

    private void sheet(Minecraft c) {
        if (completedCapture == null) {
            verifySheet(c);
        }
        if (capture(c, "sheet", Cursor.AWAY)) {
            next(Stage.HOVER);
        }
    }

    private void hover(Minecraft c) {
        // 鼠标停在窗口里：最底下一行出现右键提示，正文其余不动。
        if (capture(c, "hover", Cursor.INSIDE)) {
            next(Stage.MENU);
        }
    }

    private MouseButtonEvent press(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private void click(Minecraft c, MouseButtonEvent event) {
        // 和玩家一样从当前屏幕的鼠标入口进去，经过窗口层的命中和菜单。
        c.gui.screen().mouseClicked(event, false /* doubleClick */);
        c.gui.screen().mouseReleased(event);
    }

    private void rightClickWindow(Minecraft c) {
        var root = view().root();
        click(c, press(root.x() + root.width() / 2.0, root.y() + root.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        checkState(runtime.contextMenu().isOpen(), "右键没有弹出菜单");
    }

    private void choose(Minecraft c, String action) {
        var screen = c.gui.screen();
        var row = runtime.contextMenu().row(action, screen.width, screen.height)
                .orElseThrow(() -> new IllegalStateException("菜单里没有 " + action));
        click(c, press(row.left() + row.width() / 2.0, row.top() + row.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        checkState(!runtime.contextMenu().isOpen(), "点了菜单项菜单还开着 %s", action);
    }

    private Optional<WindowState> overview() {
        return runtime.workspace().windowBar().stream()
                .filter(state -> state.key().windowType().equals(OVERVIEW_TYPE)).findFirst();
    }

    private void menu(Minecraft c) {
        if (completedCapture == null && !runtime.contextMenu().isOpen()) {
            checkState(overview().isEmpty(), "概况小窗一开始就在");
            rightClickWindow(c);
            var screen = c.gui.screen();
            for (var layer : module.surfacePanel().layers()) {
                checkState(runtime.contextMenu().row("layer:" + layer.id(), screen.width, screen.height).isPresent(),
                        "菜单里缺图层 %s", layer.id());
            }
        }
        if (capture(c, "menu", Cursor.KEEP)) {
            next(Stage.OVERVIEW_ON);
        }
    }

    private void overviewOn(Minecraft c) {
        choose(c, OVERVIEW_ACTION);
        checkState(overview().isPresent() && overview().get().minimized(), "点了概况小窗没有打开");
        next(Stage.OVERVIEW_SHOWN);
    }

    private void overviewShown(Minecraft c) {
        if (capture(c, "overview", Cursor.AWAY)) {
            next(Stage.OVERVIEW_OFF);
        }
    }

    private void overviewOff(Minecraft c) {
        rightClickWindow(c);
        choose(c, OVERVIEW_ACTION);
        checkState(overview().isEmpty(), "再点一次概况小窗没有关掉");
        run(c, AFFLICT);
        next(Stage.AFFLICT);
    }

    private void layerMenu(Minecraft c) {
        rightClickWindow(c);
        next(Stage.LAYER_ON);
    }

    private void layerOn(Minecraft c) {
        choose(c, SKELETON_ACTION);
        checkState(module.surfacePanel().visibleLayers().contains(SKELETON), "点了图层没有显示骨架");
        checkState(view().modelShown(), "显示骨架后正文没有换成立体模型");
        var bounds = window.bounds();
        checkState(bounds.width() >= MODEL_WIDTH && bounds.height() >= MODEL_HEIGHT, "窗口没有放大 %s", bounds);
        System.out.println("R3_SURFACE " + width + " model window=" + bounds);
        next(Stage.MODEL);
    }

    private void model(Minecraft c) {
        if (capture(c, "model", Cursor.AWAY)) {
            next(Stage.LAYER_OFF);
        }
    }

    private void layerOff(Minecraft c) {
        // 立体模型那一面同样从右键菜单回来。
        rightClickWindow(c);
        choose(c, SKELETON_ACTION);
        checkState(!module.surfacePanel().visibleLayers().contains(SKELETON), "再点一次没有收起骨架");
        checkState(!view().modelShown(), "只剩体表一层后正文没有换回平面人形");
        finish(c, null);
    }

    private void afflict(Minecraft c) {
        boolean injured = c.player.getActiveEffects().stream().anyMatch(effect -> effect.getEffect().unwrapKey()
                .filter(key -> key.identifier().getNamespace().equals(INJURY_NAMESPACE)).isPresent());
        checkState(injured, "伤势效果没有挂上");
        if (view().footnotes().isEmpty()) {
            return;
        }
        next(Stage.FOOTNOTE);
    }

    private void footnote(Minecraft c) {
        if (completedCapture == null) {
            var notes = view().footnotes();
            checkState(notes.size() == 1, "脚注不是一行伤势 %s", notes);
            checkState(window.bounds().height() > SHEET_HEIGHT, "有了脚注窗口没有长高 %s", window.bounds());
            System.out.println("R3_SURFACE " + width + " footnote=" + notes.getFirst().getString() + " window="
                    + window.bounds());
        }
        if (capture(c, "injury", Cursor.AWAY)) {
            next(Stage.LAYER_MENU);
        }
    }

    private void next(Stage next) {
        stage = next;
        since = ticks;
    }

    /** 截图前把鼠标放哪：挪到角上、停在体表窗里、或者留在原处。 */
    private enum Cursor { AWAY, INSIDE, KEEP }

    private boolean capture(Minecraft c, String name, Cursor cursor) {
        if (name.equals(completedCapture)) {
            completedCapture = null;
            return true;
        }
        if (pendingCapture == null) {
            if (cursor == Cursor.AWAY) {
                moveCursor(c, 1, 1);
            } else if (cursor == Cursor.INSIDE) {
                var root = view().root();
                moveCursor(c, root.x() + root.width() / 2.0, root.y() + root.height() / 2.0);
            }
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
        Screenshot.grab(c.gameDirectory, "r3-surface-" + width + "-" + name + ".png",
                c.gameRenderer.mainRenderTarget(), 1, message -> {});
        pendingCapture = null;
        completedCapture = name;
    }

    private static void moveCursor(Minecraft c, double guiX, double guiY) {
        long handle = c.getWindow().handle();
        double scale = c.getWindow().getGuiScale();
        GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(handle, null);
        GLFW.glfwSetCursorPosCallback(handle, callback);
        if (callback != null) {
            callback.invoke(handle, guiX * scale, guiY * scale);
        } else {
            GLFW.glfwSetCursorPos(handle, guiX * scale, guiY * scale);
        }
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
