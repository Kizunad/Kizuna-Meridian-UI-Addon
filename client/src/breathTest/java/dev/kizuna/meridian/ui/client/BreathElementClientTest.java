package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.core.UIComponent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import static com.google.common.base.Preconditions.checkState;

/**
 * 真客户端里看吐纳攒火行：开全脉的玩家到下界，开正式的吐纳窗口，勾自动吐纳、点开始，照正常速度吐纳 5 分钟，
 * 每秒拍一张。纳入量下面的「其中火行 x」是服务端真实入脉明细算出来的，这里不喂数据。游戏不调快：
 * 调快以后服务端比客户端走得快，拍下来的就不是 5 分钟的量了；实际走了多少游戏 tick 写进报告。
 */
public final class BreathElementClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../breath-element-results.txt");
    /** 每张图对应的事实一行：服务端发来的吐纳视图里写着什么。 */
    private static final Path FACTS = Path.of("../breath-element-facts.tsv");
    private static final String FACTS_HEADER = "张\t动作\t在吐纳\t自动\t空气\t呼吸耐力\t吸入灵气\t纳入\t其中火行"
            + "\t受阻原因\t状态\t当地浓度\t当地火行\n";
    private static final int READY_TICKS = 60;
    private static final int SETTLE_TICKS = 40;
    /** 吐纳这么多游戏 tick（5 分钟），每这么多 tick 拍一张。 */
    private static final int BREATH_TICKS = 6_000;
    private static final int SHOT_EVERY = 20;
    private static final int TIMEOUT_TICKS = BREATH_TICKS + 2_000;
    private static final int GUI_SCALE = 2;
    private static final String FIRE = "meridian:fire";
    /** 两位小数下看得见的火行真元。 */
    private static final double VISIBLE_FIRE = 0.005;
    private static final List<String> SETUP = List.of("meridian progression unlock", "meridian opening complete @s all",
            "gamemode creative", "execute in minecraft:the_nether run tp @s 8.5 100 8.5");
    private static final List<String> ROOM = List.of("fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:netherrack",
            "fill ~-3 ~ ~-3 ~3 ~3 ~3 minecraft:air");

    private enum Stage { PREPARE, ARRIVE, OPEN, START, FILM, DONE }

    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private CompletableFuture<Void> command;
    private BodyUiModule module;
    private WindowState window;
    private int screenshots;
    private long startGameTime;
    private final StringBuilder shotFacts = new StringBuilder(FACTS_HEADER);

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
            checkState(++ticks < TIMEOUT_TICKS, "吐纳攒火行客户端验收超时：" + stage);
            if (ticks < READY_TICKS || client.player == null || client.getSingleplayerServer() == null
                    || client.gui.overlay() != null || !ClientProgression.synchronizedWithServer()) {
                return;
            }
            switch (stage) {
                case PREPARE -> prepare(client);
                case ARRIVE -> arrive(client);
                case OPEN -> open(client);
                case START -> start(client);
                case FILM -> film(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            finish(client, failure.toString(), "");
        }
    }

    private void prepare(Minecraft client) {
        // 用正式命令解锁、开全脉、换创造，再传到下界。
        module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        command = run(client, SETUP);
        next(Stage.ARRIVE);
    }

    private void arrive(Minecraft client) {
        // 到了下界先给脚下垫一层、四周清空，免得卡在石头里或者掉进岩浆。
        if (!command.isDone() || client.player.level().dimension() != Level.NETHER
                || ticks - stageTick < SETTLE_TICKS) {
            return;
        }
        command.join();
        command = run(client, ROOM);
        next(Stage.OPEN);
    }

    private void open(Minecraft client) {
        // 窗口、面板都走正式工厂；等整合服发来带当地环境的吐纳视图。
        if (!command.isDone() || !ClientProgression.canGather() || ticks - stageTick < SETTLE_TICKS) {
            return;
        }
        command.join();
        if (window == null) {
            var runtime = InventoryUiClient.runtime();
            runtime.motion(false /* enabled */);
            runtime.openWorkspace();
            window = runtime.open("meridian_ui:cultivation", null);
        }
        GatheringView view = module.gatheringView();
        if (module.cultivationPanel() == null || view == null || !view.getEnvironment().getAvailable()) {
            return;
        }
        next(Stage.START);
    }

    private void start(Minecraft client) {
        // 和玩家一样：先勾「自动吐纳」，再点开始；等服务端确认吐纳开始了再开拍。
        if (ticks - stageTick == 1) {
            click("breathing-automatic");
        } else if (ticks - stageTick == SETTLE_TICKS / 2) {
            click("breathing-start");
        }
        if (ticks - stageTick < SETTLE_TICKS) {
            return;
        }
        checkState(module.gatheringView().getBreathing().getActive(), "点了开始，吐纳没有开始");
        capture(client, "breath_0000.png");
        startGameTime = client.level.getGameTime();
        next(Stage.FILM);
    }

    private void film(Minecraft client) {
        // 每 20 tick 拍一张；到时核对纳入里真有火行、那一行也写出来了，再收工。
        int elapsed = ticks - stageTick;
        if (elapsed % SHOT_EVERY == 0) {
            capture(client, String.format(Locale.ROOT, "breath_%04d.png", elapsed / SHOT_EVERY));
            shotFacts.append(elapsed / SHOT_EVERY).append('\t').append(factsLine(module.gatheringView()));
        }
        if (elapsed < BREATH_TICKS) {
            return;
        }
        var breathing = module.gatheringView().getBreathing();
        Map<String, Double> stored = new TreeMap<>();
        breathing.getStoredByKind().getEntriesList()
                .forEach(entry -> stored.merge(entry.getAttribute(), entry.getAmount(), Double::sum));
        String caption = StoredElementCaption.of(breathing).orElse("");
        String facts = "game_ticks=" + (client.level.getGameTime() - startGameTime) + "\nstored="
                + breathing.getStoredQi() + "\nby_attribute=" + stored + "\ncaption=" + caption + "\n";
        boolean fire = stored.getOrDefault(FIRE, 0.0) >= VISIBLE_FIRE && caption.contains("火行");
        finish(client, fire ? null : "吐纳 5 分钟，纳入里没有看得见的火行：" + stored + " 那一行：" + caption, facts);
    }

    private static String factsLine(GatheringView view) {
        // 这一刻吐纳视图里的数，原样记下来；录像里看不清的（受阻原因、当地有多少火行）靠这张表对。
        var breath = view.getBreathing();
        double fire = breath.getStoredByKind().getEntriesList().stream()
                .filter(entry -> entry.getAttribute().equals(FIRE)).mapToDouble(entry -> entry.getAmount()).sum();
        double localFire = view.getEnvironment().getQi().getEntriesList().stream()
                .filter(entry -> entry.getAttribute().equals(FIRE)).mapToDouble(entry -> entry.getAmount()).sum();
        return String.format(Locale.ROOT, "%s\t%s\t%s\t%.0f%%\t%.0f%%\t%.2f\t%.2f\t%.2f\t%s\t%s\t%.3f\t%.1f%n",
                breath.getAction(), breath.getActive(), breath.getAutomatic(),
                100 * breath.getAirLiters() / Math.max(breath.getCapacityLiters(), 1e-9),
                100 * breath.getStaminaRatio(), breath.getAbsorbedQi(), breath.getStoredQi(), fire,
                breath.getFailure(), breath.getStatus(), view.getEnvironment().getConcentrationPerBlock(), localFire);
    }

    private void click(String id) {
        // 用真实的鼠标按下事件点正式组件。
        UIComponent target = module.cultivationPanel().root().childById(UIComponent.class, id);
        boolean handled = target.onMouseDown(new MouseButtonEvent(target.x() + 4, target.y() + 4,
                new MouseButtonInfo(0, 0)), false);
        checkState(handled, "点 " + id + " 没有反应");
    }

    private static CompletableFuture<Void> run(Minecraft client, List<String> commands) {
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

    private void next(Stage next) {
        // 记录阶段起点，为渲染和网络响应留出真实 tick。
        stage = next;
        stageTick = ticks;
    }

    private void capture(Minecraft client, String name) {
        // 保存帧缓冲原图。
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> { });
        screenshots++;
    }

    private void finish(Minecraft client, String failure, String facts) {
        // 成败都结束隔离客户端，报告由 Gradle 验证。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure
                    + "\nscreenshots=" + screenshots + "\n" + facts);
            Files.writeString(FACTS, shotFacts);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        client.stop();
    }
}
