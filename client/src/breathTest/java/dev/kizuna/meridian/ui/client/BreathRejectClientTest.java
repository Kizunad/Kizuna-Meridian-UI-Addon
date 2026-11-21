package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import static com.google.common.base.Preconditions.checkState;

/**
 * 真客户端里看吐纳纳不进时窗口写不写原因：开全脉的玩家先到末地（那里没有灵气），开正式的吐纳窗口自动吐纳，
 * 拍 25 秒（头几秒不该写，之后余力条下面写出原因）；再回主世界重开窗口、重新开始吐纳，拍 25 秒（纳入量在涨，不写）。
 * 写不写、写什么都是窗口照服务端发来的吐纳视图自己判断的，这里不喂数据，游戏也不调快。
 */
public final class BreathRejectClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../breath-reject-results.txt");
    /** 每张图对应的事实一行：服务端发来的吐纳视图里写着什么、窗口那一行写了什么。 */
    private static final Path FACTS = Path.of("../breath-reject-facts.tsv");
    private static final String FACTS_HEADER = "张\t段\t在吐纳\t纳入\t受阻原因\t当地有灵气\t当地浓度\t窗口那一行\n";
    private static final int READY_TICKS = 60;
    private static final int SETTLE_TICKS = 40;
    /** 末地、主世界各拍这么多游戏 tick（25 秒），每这么多 tick 拍一张。 */
    private static final int PART_TICKS = 500;
    private static final int SHOT_EVERY = 10;
    private static final int TIMEOUT_TICKS = 2 * PART_TICKS + 2_000;
    /** 纳入量要比一个呼吸周期还久没涨才写原因，头这么多 tick 里不该出现。 */
    private static final int QUIET_TICKS = 160;
    private static final int GUI_SCALE = 2;
    /** 两位小数下看得见的纳入量。 */
    private static final double VISIBLE_QI = 0.005;
    private static final List<String> SETUP = List.of("meridian progression unlock", "meridian opening complete @s all",
            "gamemode creative", "execute in minecraft:the_end run tp @s 8.5 100 8.5");
    private static final List<String> HOME = List.of("execute in minecraft:overworld run tp @s 8.5 100 8.5");
    /** 到了以后给脚下垫一层、四周清空，免得掉进虚空或者卡在石头里。 */
    private static final List<String> ROOM = List.of("fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone",
            "fill ~-3 ~ ~-3 ~3 ~3 ~3 minecraft:air");

    private enum Stage { PREPARE, ARRIVE, OPEN, START, BARREN, RETURN, REOPEN, RESTART, HOME, DONE }

    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private CompletableFuture<Void> command;
    private BodyUiModule module;
    private WindowState window;
    private int screenshots;
    private int firstWritten = -1;
    private String written = "";
    private double storedAtHome;
    private final StringBuilder shotFacts = new StringBuilder(FACTS_HEADER);

    @Override
    public void onInitializeClient() {
        // 测试入口只在专用运行任务中加载，失败也写报告并退出；点的是别的场景时不动手。
        if (BreathClipTools.selected("reject")) {
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT_TICKS, "吐纳被拒客户端验收超时：" + stage);
            if (ticks < READY_TICKS || client.player == null || client.getSingleplayerServer() == null
                    || client.gui.overlay() != null || !ClientProgression.synchronizedWithServer()) {
                return;
            }
            switch (stage) {
                case PREPARE -> prepare(client);
                case ARRIVE -> arrive(client, Level.END, Stage.OPEN);
                case OPEN -> open(Stage.START);
                case START -> start(Stage.BARREN);
                case BARREN -> barren(client);
                case RETURN -> arrive(client, Level.OVERWORLD, Stage.REOPEN);
                case REOPEN -> open(Stage.RESTART);
                case RESTART -> restart();
                case HOME -> home(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            finish(client, failure.toString());
        }
    }

    private void prepare(Minecraft client) {
        // 用正式命令解锁、开全脉、换创造，再传到末地。
        module = BreathClipTools.module();
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        command = BreathClipTools.run(client, SETUP);
        next(Stage.ARRIVE);
    }

    private void arrive(Minecraft client, ResourceKey<Level> dimension, Stage after) {
        // 等传送落地，再垫脚、清空四周。
        if (!command.isDone() || client.player.level().dimension() != dimension
                || ticks - stageTick < SETTLE_TICKS) {
            return;
        }
        command.join();
        command = BreathClipTools.run(client, ROOM);
        next(after);
    }

    private void open(Stage after) {
        // 窗口、面板都走正式工厂；换维度会把窗口关掉，回主世界以后照样重开一次。
        // 末地没有灵气，这里只管开窗，不等当地环境。
        if (!command.isDone() || !ClientProgression.canGather() || ticks - stageTick < SETTLE_TICKS) {
            return;
        }
        command.join();
        var runtime = InventoryUiClient.runtime();
        runtime.motion(false /* enabled */);
        runtime.uiVersion(dev.kizuna.inventoryui.workspace.UiVersion.CLASSIC);
        runtime.openWorkspace();
        window = runtime.open("meridian_ui:cultivation", null);
        next(after);
    }

    private void start(Stage after) {
        // 和玩家一样：没勾「自动吐纳」就先勾上，再点开始；等服务端确认吐纳开始了再开拍。
        GatheringView view = module.gatheringView();
        if (module.cultivationPanel() == null || view == null || ticks - stageTick < SETTLE_TICKS) {
            return;
        }
        int waited = ticks - stageTick - SETTLE_TICKS;
        if (waited == 0 && !view.getBreathing().getAutomatic()) {
            BreathClipTools.click(module, "breathing-automatic");
        } else if (waited == SETTLE_TICKS / 2) {
            BreathClipTools.click(module, "breathing-start");
        }
        if (waited < SETTLE_TICKS) {
            return;
        }
        checkState(view.getBreathing().getActive(), "点了开始，吐纳没有开始");
        next(after);
    }

    private void barren(Minecraft client) {
        // 末地：记下那一行第一次出现在第几 tick、写的什么；拍满 25 秒后核对，再回主世界。
        int elapsed = ticks - stageTick;
        String line = String.join(" / ", notice().lines());
        if (!line.isEmpty() && firstWritten < 0) {
            firstWritten = elapsed;
            written = line;
        }
        shoot(client, elapsed, "末地", line);
        if (elapsed < PART_TICKS) {
            return;
        }
        checkState(firstWritten >= QUIET_TICKS, "原因出现得太早或者没出现：第 " + firstWritten + " tick");
        checkState(line.startsWith("未纳入："), "拍完时窗口没写原因：" + line);
        command = BreathClipTools.run(client, HOME);
        next(Stage.RETURN);
    }

    private void restart() {
        // 回到主世界：等带当地环境的吐纳视图，再照开头那样重新开始吐纳。
        GatheringView view = module.gatheringView();
        if (!command.isDone() || view == null || !view.getEnvironment().getAvailable()) {
            stageTick = ticks;
            return;
        }
        storedAtHome = view.getBreathing().getStoredQi();
        start(Stage.HOME);
    }

    private void home(Minecraft client) {
        // 主世界：纳入量涨起来，那一行就该没了。
        int elapsed = ticks - stageTick;
        String line = String.join(" / ", notice().lines());
        shoot(client, elapsed, "主世界", line);
        if (elapsed < PART_TICKS) {
            return;
        }
        double gained = module.gatheringView().getBreathing().getStoredQi() - storedAtHome;
        checkState(gained >= VISIBLE_QI, "回到主世界以后纳入量没涨：" + gained);
        checkState(line.isEmpty(), "纳入量在涨，窗口还写着：" + line);
        finish(client, null);
    }

    private BreathingMeters.RejectionNotice notice() {
        // 窗口里画原因的那个正式组件。
        return module.cultivationPanel().root().childById(BreathingMeters.RejectionNotice.class,
                "breathing-rejection");
    }

    private void shoot(Minecraft client, int elapsed, String part, String line) {
        // 每 10 tick 拍一张，同时把这一刻吐纳视图里的数和窗口那一行记进事实表。
        if (elapsed % SHOT_EVERY != 0) {
            return;
        }
        BreathClipTools.capture(client, String.format(Locale.ROOT, "reject_%04d.png", screenshots));
        var breath = module.gatheringView().getBreathing();
        var environment = module.gatheringView().getEnvironment();
        shotFacts.append(String.format(Locale.ROOT, "%d\t%s\t%s\t%.2f\t%s\t%s\t%.3f\t%s%n", screenshots, part,
                breath.getActive(), breath.getStoredQi(), breath.getFailure(), environment.getAvailable(),
                environment.getConcentrationPerBlock(), line));
        screenshots++;
    }

    private void next(Stage next) {
        // 记录阶段起点，为渲染和网络响应留出真实 tick。
        stage = next;
        stageTick = ticks;
    }

    private void finish(Minecraft client, String failure) {
        // 成败都结束隔离客户端，报告由 Gradle 验证。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure
                    + "\nscreenshots=" + screenshots + "\nfirst_written_tick=" + firstWritten
                    + "\nwritten=" + written + "\n");
            Files.writeString(FACTS, shotFacts);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        client.stop();
    }
}
