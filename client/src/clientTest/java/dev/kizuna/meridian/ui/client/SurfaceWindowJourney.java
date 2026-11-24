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
 * 新版体表窗口的验收：受伤几处就列几行、每行有「当前/上限」和 HP 条，窗口跟着长高到放得下，最多露六处，
 * 再多的用滚轮一处一处翻，任何时候都不露半行；鼠标移进窗口出现右键提示，不压正文；
 * 用真实的右键和左键从菜单打开、关掉概况小窗；带一个伤势效果看脚注；再开、关一个图层看正文换成立体模型又换回来；
 * 最后把窗口拉矮，看窗口不再跟内容变、列表仍只露整行。
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
    private static final String SPREAD_HIT = "damage @s 2 minecraft:generic";
    private static final int MAX_HITS = 40;
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
    private static final int ROWS_CAP = 6;
    private static final int FIGURE_BLOCK = 66;
    private static final int LINE = 9;
    private static final int FOOT_GAP = 2;
    /** 正文四周和提示行占的高度：上边距 3、脚注上的空 2、提示行 9、下边距 3。 */
    private static final int SHEET_MARGINS = 17;
    /** 标题栏 14 加下边框 2。 */
    private static final int CHROME_HEIGHT = 16;
    private static final int WHEEL_TO_END = 20;
    private static final String CRAMPED_HEIGHT = "90";
    private enum Stage {
        JOIN, READY, INJURE, OPEN, SHEET, SCROLL, HOVER, MENU, OVERVIEW_ON, OVERVIEW_SHOWN, OVERVIEW_OFF, AFFLICT,
        FOOTNOTE, LAYER_MENU, LAYER_ON, MODEL, LAYER_OFF, CRAMP, CRAMPED, DONE
    }
    private Stage stage = Stage.JOIN;
    private int ticks;
    private int since;
    private int width;
    private int injuries;
    private int targetWounds;
    private boolean scrolled;
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
                case SCROLL -> scroll(c);
                case HOVER -> hover(c);
                case MENU -> menu(c);
                case OVERVIEW_ON -> overviewOn(c);
                case OVERVIEW_SHOWN -> overviewShown(c);
                case OVERVIEW_OFF -> overviewOff(c);
                case LAYER_MENU -> layerMenu(c);
                case LAYER_ON -> layerOn(c);
                case MODEL -> model(c);
                case LAYER_OFF -> layerOff(c);
                case CRAMP -> cramp(c);
                case CRAMPED -> cramped(c);
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
        // 这个运行目录没有偏好文件：不用切，缺省就该是新版。
        checkState(runtime.uiVersion() == UiVersion.MODERN, "没有偏好文件时缺省不是新版 %s", runtime.uiVersion());
        c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        width = Integer.getInteger("surface.width", NARROW_WIDTH);
        targetWounds = Integer.getInteger("surface.wounds", 0);
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

    private boolean injuredEnough() {
        // 指定了受伤处数就打到够数为止（没有来源的伤害在全身各部位里随机落点），否则照固定的几下打完。
        if (targetWounds == 0) {
            return injuries == Integer.getInteger("surface.injuries", INJURE.size());
        }
        return woundedParts() >= targetWounds;
    }

    private String nextHit() {
        if (targetWounds == 0) {
            return INJURE.get(injuries);
        }
        return SPREAD_HIT;
    }

    private void injure(Minecraft c) {
        // 一拍只打一下：刚受过伤的那半秒里再来的伤害不算数。
        checkState(c.player.isAlive(), "打了 %s 下人死了", injuries);
        if (injuredEnough()) {
            // 摔伤可能自己带出伤势效果；先清掉，伤势那一行后面单独验。
            run(c, CURE);
            next(Stage.OPEN);
            return;
        }
        checkState(injuries < MAX_HITS, "打了 %s 下只伤到 %s 处", injuries, woundedParts());
        run(c, List.of(nextHit()));
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

    private int sheetHeight(SurfaceReadout readout) {
        // 窗口该有多高：受伤部位最多算六处，和人形比谁高；有脚注再加脚注；不低于缺省的 104。
        int notes = readout.notes().size();
        int list = Math.min(readout.wounds().size(), ROWS_CAP) * SurfaceSheet.ROW + notes * LINE
                + Math.max(0, notes - 1) * SurfaceSheet.NOTE_GAP;
        int foot = view().footnotes().size() * (LINE + FOOT_GAP);
        int content = Math.max(FIGURE_BLOCK, list) + foot + SHEET_MARGINS;
        return Math.max(SHEET_HEIGHT, content + CHROME_HEIGHT);
    }

    private void verifyWholeRows(SurfaceReadout readout) {
        // 露出来的都是整行：这一段的高度不超过给它的地方，下面的脚注和提示行都在它之外，提示行在窗口里面。
        var span = view().listSpan();
        var list = view().list();
        var hint = view().hint();
        int shownBottom = list.y() + span.height();
        checkState(span.height() <= view().listViewHeight(), "列表露了半行 %s 可见 %s", span, view().listViewHeight());
        checkState(shownBottom + view().footnotes().size() * LINE <= hint.y(), "提示行压着正文 %s", span);
        checkState(hint.y() + LINE <= window.bounds().y() + window.bounds().height(), "提示行出了窗口");
        checkState(span.first() + span.rows() <= readout.wounds().size(), "列表翻过了头 %s", span);
    }

    private void verifySheet(Minecraft c) {
        // 用游戏里的真字体把正文重放一遍：一处受伤部位一行，名字靠左、读数右端贴边、下面一条槽和一段填充。
        var bounds = window.bounds();
        var readout = readout();
        checkState(bounds.width() == SHEET_WIDTH && bounds.height() == sheetHeight(readout),
                "体表窗尺寸不对 %s，该高 %s", bounds, sheetHeight(readout));
        checkState(!view().modelShown(), "体表窗一开就是立体模型");
        checkState(view().footnotes().isEmpty(), "没有伤势效果却有脚注 %s", view().footnotes());
        long wounded = woundedParts();
        checkState(readout.wounds().size() == wounded, "受伤 %s 处，列了 %s 行", wounded, readout.wounds().size());
        var span = view().listSpan();
        checkState(span.rows() == Math.min(wounded, ROWS_CAP) && span.notes() == readout.notes().size(),
                "受伤 %s 处，窗口露出的是 %s", wounded, span);
        verifyWholeRows(readout);
        int listWidth = view().listWidth();
        var probe = new SummaryProbe(c.font::width);
        SurfaceSheet.list(probe, listWidth, readout, span);
        checkState(probe.blocks().size() == span.rows() * BLOCKS_PER_ROW, "HP 条数目不对 %s", probe.blocks().size());
        for (int index = 0; index < span.rows(); index++) {
            var row = readout.wounds().get(span.first() + index);
            var name = probe.texts().get(index * TEXTS_PER_ROW);
            var value = probe.texts().get(index * TEXTS_PER_ROW + 1);
            var track = probe.blocks().get(index * BLOCKS_PER_ROW);
            var fill = probe.blocks().get(index * BLOCKS_PER_ROW + 1);
            checkState(row.value().matches(READING), "读数不是「当前/上限」 %s %s", row.name(), row.value());
            checkState(value.x() + value.width() == listWidth, "读数没贴右边 %s", row.name());
            checkState(name.x() + name.width() < value.x(), "名字和读数相叠 %s", row.name());
            checkState(track.right() - track.left() == listWidth, "HP 条的槽不满宽 %s", row.name());
            checkState(fill.right() <= track.right() && fill.top() == track.top(), "HP 条出槽 %s", row.name());
            System.out.println("R4_SURFACE " + width + " " + row.name() + " " + row.value() + " "
                    + (fill.right() - fill.left()) + "/" + listWidth);
        }
        checkState(probe.texts().stream().allMatch(text -> text.x() + text.width() <= listWidth),
                "有文字画出了受伤部位那一栏");
        System.out.println("R4_SURFACE " + width + " wounds=" + wounded + " span=" + span + " notes="
                + readout.notes() + " list=" + span.height() + "/" + view().listViewHeight() + " window=" + bounds);
    }

    private void sheet(Minecraft c) {
        if (completedCapture == null) {
            verifySheet(c);
        }
        if (capture(c, "sheet", Cursor.AWAY)) {
            next(Stage.SCROLL);
        }
    }

    private void wheel(Minecraft c, int notches) {
        // 和玩家一样从当前屏幕的滚轮入口进去，鼠标在受伤部位那一栏正中。
        var list = view().list();
        double x = list.x() + list.width() / 2.0;
        double y = list.y() + list.height() / 2.0;
        moveCursor(c, x, y);
        for (int index = 0; index < Math.abs(notches); index++) {
            c.gui.screen().mouseScrolled(x, y, 0, Integer.signum(notches));
        }
    }

    private void verifyScroll(Minecraft c) {
        // 往下一格只挪一处伤；一直滚到底停在最后六处，说明行还在下面，没有半行。
        var readout = readout();
        int wounds = readout.wounds().size();
        wheel(c, -1);
        checkState(view().listSpan().first() == 1, "滚一格没有正好挪一处 %s", view().listSpan());
        verifyWholeRows(readout);
        wheel(c, -WHEEL_TO_END);
        var span = view().listSpan();
        checkState(span.first() == wounds - ROWS_CAP && span.rows() == ROWS_CAP, "滚到底不是最后六处 %s", span);
        checkState(span.notes() == readout.notes().size(), "滚到底说明行少了 %s", span);
        verifyWholeRows(readout);
        checkState(window.bounds().height() == sheetHeight(readout), "滚动时窗口高度变了 %s", window.bounds());
        System.out.println("R4_SURFACE " + width + " scroll wounds=" + wounds + " end=" + span + " window="
                + window.bounds());
        scrolled = true;
    }

    private void scroll(Minecraft c) {
        // 受伤不到七处时没有可翻的，这一步跳过。
        if (completedCapture == null && readout().wounds().size() <= ROWS_CAP) {
            next(Stage.HOVER);
            return;
        }
        if (completedCapture == null) {
            verifyScroll(c);
        }
        if (capture(c, "scroll", Cursor.KEEP)) {
            wheel(c, WHEEL_TO_END);
            checkState(view().listSpan().first() == 0, "滚回顶上没有回到第一处 %s", view().listSpan());
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
        System.out.println("R4_SURFACE " + width + " model window=" + bounds);
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
        var bounds = window.bounds();
        checkState(bounds.width() == SHEET_WIDTH && bounds.height() == sheetHeight(readout()),
                "换回平面人形后窗口没有回到正文的大小 %s", bounds);
        next(Stage.CRAMP);
    }

    private void cramp(Minecraft c) {
        // 和玩家拖右下角走的是同一个入口：拉矮以后以玩家的尺寸为准。
        checkState(runtime.workspace().windows().resize(window.key(), Integer.toString(SHEET_WIDTH), CRAMPED_HEIGHT),
                "窗口拉不动");
        next(Stage.CRAMPED);
    }

    private void cramped(Minecraft c) {
        if (completedCapture == null) {
            var bounds = window.bounds();
            checkState(Integer.toString(bounds.height()).equals(CRAMPED_HEIGHT), "玩家拉过的窗口又被内容改了 %s", bounds);
            var readout = readout();
            verifyWholeRows(readout);
            System.out.println("R4_SURFACE " + width + " cramped span=" + view().listSpan() + " view="
                    + view().listViewHeight() + " wounds=" + readout.wounds().size() + " window=" + bounds);
        }
        if (capture(c, "cramped", Cursor.AWAY)) {
            checkState(scrolled || targetWounds <= ROWS_CAP, "要了 %s 处伤却没有验到滚动", targetWounds);
            finish(c, null);
        }
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
            checkState(window.bounds().height() == sheetHeight(readout()), "有了脚注窗口高度不对 %s，该高 %s",
                    window.bounds(), sheetHeight(readout()));
            verifyWholeRows(readout());
            System.out.println("R4_SURFACE " + width + " footnote=" + notes.getFirst().getString() + " window="
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
        Screenshot.grab(c.gameDirectory, "r4-surface-" + width + "-" + name + ".png",
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
