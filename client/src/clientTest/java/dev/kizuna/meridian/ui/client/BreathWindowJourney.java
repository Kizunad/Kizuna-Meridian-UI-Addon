package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import io.wispforest.owo.ui.core.UIComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * 新版吐纳窗口的验收：场景在上，下面依次是余力一行、空气和纳入一行、「自动」和主按钮一行；
 * 用真实点击走一遍没开始、首次教学、手动吐纳、自动吐纳、停下，主按钮的字跟着变；
 * 每个状态都把窗口里的三项读数和同一帧的呼吸回执、吐纳小窗对一遍。
 */
public final class BreathWindowJourney {
    private static final String BREATH = "meridian_ui:cultivation";
    private static final List<String> PREPARE = List.of("meridian progression unlock", "gamemode survival @s",
            "weather clear", "time set noon");
    private static final int SETTLE = 20;
    private static final int TIMEOUT = 4000;
    private static final int NARROW_WIDTH = 854;
    private static final int NARROW_HEIGHT = 480;
    private static final int WIDE_HEIGHT = 720;
    private static final int SPAWN_SETTLE = 140;
    private static final int SHEET_WIDTH = 170;
    private static final int SHEET_HEIGHT = 167;
    private static final int NOTE_HEIGHT = 20;
    private static final int NOTE_LINES = 2;
    private static final int SCENE_HEIGHT = 88;
    private static final int LINE = 9;
    private static final int INHALE_TICKS = 50;
    private static final int AUTOMATIC_TICKS = 120;
    private enum Stage {
        JOIN, READY, OPEN, IDLE, HOVER, TUTORIAL, START, INHALE, ACTIVE, AUTOMATIC, AUTOMATIC_RUNNING, STOP, STOPPED,
        DONE
    }
    private Stage stage = Stage.JOIN;
    private int ticks;
    private int since;
    private int width;
    private BodyUiModule module;
    private ClientRuntime runtime;
    private WindowState window;
    private CompletableFuture<Void> commands;
    private MouseButtonEvent held;
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
                case OPEN -> open(c);
                case IDLE -> idle(c);
                case HOVER -> hover(c);
                case TUTORIAL -> tutorial(c);
                case START -> start(c);
                case INHALE -> inhale(c);
                case ACTIVE -> active(c);
                case AUTOMATIC -> automatic(c);
                case AUTOMATIC_RUNNING -> automaticRunning(c);
                case STOP -> stop(c);
                case STOPPED -> stopped(c);
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
        width = Integer.getInteger("breath.width", NARROW_WIDTH);
        int height = WIDE_HEIGHT;
        if (width == NARROW_WIDTH) {
            height = NARROW_HEIGHT;
        }
        GLFW.glfwSetWindowSize(c.getWindow().handle(), width, height);
        c.options.guiScale().set(Integer.getInteger("breath.scale", 0));
        c.options.particles().set(ParticleStatus.MINIMAL);
        c.resizeGui();
        run(c, PREPARE);
        next(Stage.READY);
    }

    private void ready(Minecraft c) {
        if (!ClientProgression.canGather() || module.snapshot() == null) {
            return;
        }
        next(Stage.OPEN);
    }

    private void open(Minecraft c) {
        runtime.openWorkspace();
        window = runtime.open(BREATH, null);
        checkState(!window.minimized(), "吐纳没有展开");
        next(Stage.IDLE);
    }

    private ModernCultivationPanel panel() {
        checkState(module.cultivationPanel() instanceof ModernCultivationPanel, "新版吐纳窗用的不是新版正文");
        return (ModernCultivationPanel) module.cultivationPanel();
    }

    private BreathingView breath() {
        return module.gatheringView().getBreathing();
    }

    private UIComponent part(String id) {
        var found = panel().root().childById(UIComponent.class, id);
        checkState(found != null, "窗口里没有 %s", id);
        return found;
    }

    private static String number(String text) {
        // 读数都是「名字 空格 数」，只比数的那一段。
        return text.substring(text.lastIndexOf(' ') + 1);
    }

    private void verifyLayout(Minecraft c, int noteLines) {
        // 自上而下：场景、余力、空气和纳入、按钮；一行压一行都不行，也都不出正文。
        var bounds = window.bounds();
        int tall = SHEET_HEIGHT;
        if (noteLines > 0) {
            tall += NOTE_HEIGHT;
        }
        checkState(bounds.width() == SHEET_WIDTH && bounds.height() == tall, "吐纳窗尺寸不对 %s，该高 %s", bounds, tall);
        checkState(panel().noteLines() == noteLines, "自动吐纳的说明该 %s 行，实际 %s 行", noteLines, panel().noteLines());
        var root = panel().root();
        var scene = part("breathing-scene");
        var reserve = part("breathing-reserve");
        var air = part("breathing-air");
        var qi = part("breathing-qi");
        var toggle = part("breathing-automatic");
        var start = part("breathing-start");
        var hint = part("breathing-hint");
        checkState(scene.height() == SCENE_HEIGHT, "场景高度不对 %s", scene.height());
        checkState(scene.y() + scene.height() <= reserve.y(), "场景压着余力一行");
        checkState(reserve.y() + reserve.height() <= air.y() && air.y() == qi.y(), "余力一行压着空气和纳入");
        checkState(air.y() + LINE <= toggle.y() && air.x() + air.width() <= qi.x(), "空气、纳入和按钮一行相叠");
        checkState(toggle.x() + toggle.width() <= start.x() + start.width() / 2, "自动勾选和主按钮相叠");
        checkState(toggle.y() + toggle.height() + noteLines * LINE <= hint.y(), "提示行压着上面的内容");
        checkState(hint.y() + LINE <= root.y() + root.height(), "提示行出了窗口");
        for (var each : List.of(scene, reserve, air, qi, toggle)) {
            checkState(each.x() >= root.x() && each.x() + each.width() <= root.x() + root.width(),
                    "%s 横向出了窗口", each.id());
        }
        // 用游戏里的真字体量：名字和百分数不相叠，空气和纳入不相叠。
        var probe = new SummaryProbe(c.font::width);
        BreathSheet.reserve(probe, reserve.width(), panel().reserve());
        var texts = probe.texts();
        checkState(texts.get(0).x() + texts.get(0).width() < texts.get(1).x(), "余力的名字和百分数相叠");
        checkState(texts.get(1).x() + texts.get(1).width() == reserve.width(), "百分数没贴右边");
        checkState(c.font.width(panel().airText()) <= air.width() && c.font.width(panel().intakeText()) <= qi.width(),
                "空气或纳入的字比它那半行宽");
        checkState(c.font.width(panel().canvas().hint()) <= hint.width(), "操作提示一行写不下");
    }

    private void verifyReadings(Minecraft c, String state) {
        // 同一拍里取三份：呼吸回执、窗口里写的、吐纳小窗写的。
        var summary = BreathSummary.of(breath());
        var reserve = panel().reserve();
        checkState(reserve.value().equals(summary.reservePercent()) && reserve.fraction() == summary.reserve(),
                "余力和回执不一致 %s", reserve);
        checkState(panel().airText().equals(summary.airLabel()), "空气和回执不一致 %s", panel().airText());
        checkState(panel().intakeText().equals("纳入 " + summary.intakeAmount()), "纳入和回执不一致 %s",
                panel().intakeText());
        var probe = new SummaryProbe(c.font::width);
        MeridianSummaries.breath(probe, MeridianSummaries.BREATH, summary);
        var compact = probe.texts().stream().map(SummaryProbe.Text::text).toList();
        checkState(compact.contains(reserve.value()), "小窗的余力和展开窗不一致 %s", compact);
        checkState(compact.stream().anyMatch(text -> text.startsWith("气 ")
                && number(text).equals(number(panel().airText()))), "小窗的空气和展开窗不一致 %s", compact);
        checkState(compact.stream().anyMatch(text -> text.startsWith("纳 ")
                && number(text).equals(number(panel().intakeText()))), "小窗的纳入和展开窗不一致 %s", compact);
        System.out.println("R4_BREATH " + width + " " + state + " button=" + panel().actionLabel() + " expanded=["
                + reserve.label() + " " + reserve.value() + ", " + panel().airText() + ", " + panel().intakeText()
                + "] compact=" + compact + " window=" + window.bounds());
    }

    private void idle(Minecraft c) {
        if (module.cultivationPanel() == null || !breath().getAvailable()) {
            return;
        }
        if (completedCapture == null) {
            checkState(panel().actionLabel().equals("开始吐纳") && !breath().getActive(), "没开始时按钮写的是 %s",
                    panel().actionLabel());
            checkState(panel().reserve().label().equals("余力") && panel().reserve().marks().isEmpty(),
                    "手动吐纳的余力一行不对 %s", panel().reserve());
            verifyLayout(c, 0);
            verifyReadings(c, "idle");
        }
        if (capture(c, "idle", Cursor.AWAY)) {
            next(Stage.HOVER);
        }
    }

    private void hover(Minecraft c) {
        // 鼠标停在窗口里：最底下一行出现操作提示。
        if (capture(c, "hover", Cursor.INSIDE)) {
            next(Stage.TUTORIAL);
        }
    }

    private MouseButtonEvent press(UIComponent target, int button) {
        return new MouseButtonEvent(target.x() + target.width() / 2.0, target.y() + target.height() / 2.0,
                new MouseButtonInfo(button, 0));
    }

    private void click(Minecraft c, String id) {
        // 和玩家一样从当前屏幕的鼠标入口进去。
        var event = press(part(id), GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.gui.screen().mouseClicked(event, false /* doubleClick */);
        c.gui.screen().mouseReleased(event);
    }

    private void tutorial(Minecraft c) {
        // 第一次点主按钮只出教学，按钮改写「开始引导」。
        if (completedCapture == null && !panel().actionLabel().equals("开始引导")) {
            click(c, "breathing-start");
            checkState(panel().actionLabel().equals("开始引导") && !breath().getActive(), "第一次点没有出教学 %s",
                    panel().actionLabel());
        }
        if (capture(c, "tutorial", Cursor.AWAY)) {
            click(c, "breathing-start");
            next(Stage.START);
        }
    }

    private void start(Minecraft c) {
        if (!breath().getActive()) {
            return;
        }
        checkState(panel().actionLabel().equals("结束吐纳") && !breath().getAutomatic(), "开始以后按钮写的是 %s",
                panel().actionLabel());
        // 在场景正中按住左键吸一口气。
        held = press(part("breathing-scene"), GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.gui.screen().mouseClicked(held, false /* doubleClick */);
        next(Stage.INHALE);
    }

    private void inhale(Minecraft c) {
        if (ticks - since < INHALE_TICKS) {
            return;
        }
        c.gui.screen().mouseReleased(held);
        checkState(breath().getAirLiters() > 0, "按住左键没有吸进气");
        next(Stage.ACTIVE);
    }

    private void active(Minecraft c) {
        if (completedCapture == null) {
            verifyLayout(c, 0);
            verifyReadings(c, "active");
        }
        if (capture(c, "active", Cursor.INSIDE)) {
            click(c, "breathing-automatic");
            next(Stage.AUTOMATIC);
        }
    }

    private void automatic(Minecraft c) {
        if (!breath().getAutomatic()) {
            return;
        }
        next(Stage.AUTOMATIC_RUNNING);
    }

    private void automaticRunning(Minecraft c) {
        if (ticks - since < AUTOMATIC_TICKS) {
            return;
        }
        if (completedCapture == null) {
            checkState(panel().actionLabel().equals("结束吐纳"), "自动吐纳时按钮写的是 %s", panel().actionLabel());
            checkState(panel().reserve().label().equals("呼吸耐力") && panel().reserve().marks().size() == 2,
                    "自动吐纳的余力一行不对 %s", panel().reserve());
            verifyLayout(c, NOTE_LINES);
            verifyReadings(c, "automatic");
        }
        if (capture(c, "automatic", Cursor.INSIDE)) {
            click(c, "breathing-start");
            next(Stage.STOP);
        }
    }

    private void stop(Minecraft c) {
        if (breath().getActive()) {
            return;
        }
        checkState(panel().actionLabel().equals("开始吐纳"), "停下以后按钮写的是 %s", panel().actionLabel());
        verifyReadings(c, "stopped");
        next(Stage.STOPPED);
    }

    private void stopped(Minecraft c) {
        // 停下以后服务端回到手动，勾选跟着去掉，说明收起，窗口缩回去。
        if (completedCapture == null) {
            checkState(!breath().getAutomatic(), "停下以后还是自动");
            verifyLayout(c, 0);
        }
        if (capture(c, "stopped", Cursor.AWAY)) {
            finish(c, null);
        }
    }

    private void next(Stage next) {
        stage = next;
        since = ticks;
    }

    /** 截图前把鼠标放哪：挪到角上，或者停在场景正中。 */
    private enum Cursor { AWAY, INSIDE }

    private boolean capture(Minecraft c, String name, Cursor cursor) {
        if (name.equals(completedCapture)) {
            completedCapture = null;
            return true;
        }
        if (pendingCapture == null) {
            if (cursor == Cursor.AWAY) {
                moveCursor(c, 1, 1);
            } else {
                var scene = part("breathing-scene");
                moveCursor(c, scene.x() + scene.width() / 2.0, scene.y() + scene.height() / 2.0);
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
        Screenshot.grab(c.gameDirectory, "r4-breath-" + width + "-" + name + ".png",
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
