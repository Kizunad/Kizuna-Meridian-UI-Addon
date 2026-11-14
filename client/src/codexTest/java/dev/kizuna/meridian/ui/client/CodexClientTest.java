package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.combat.fabric.client.CombatSlotAccess;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import static com.google.common.base.Preconditions.checkState;

/**
 * 功法谱实物书在完整装配客户端里以生存模式走一遍：读第一卷收到功法谱、拿在手上、右键翻开总览和破门斩三态，
 * 再读一卷看小卡和轻响，最后重新翻开看点亮，每步截图。
 */
public final class CodexClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../codex-results.txt");
    private static final int TIMEOUT_TICKS = 3000;
    private static final int READY_TICKS = 80;
    private static final int SETTLE_TICKS = 20;
    private static final int HELD_TICKS = 40;
    private static final int GIFT_TICKS = 200;
    private static final double DROP_RADIUS = 8;
    private static final int TOAST_TICKS = 60;
    private static final int GLOW_TICKS = 6;
    private static final int AFTER_TICKS = 110;
    private static final String CODEX = "meridian_technique:technique_codex";
    private static final String FIRST = "meridian_basic_techniques:breath_volume";
    private static final String DOOR = "meridian_door_breaker:";
    private static final List<String> REST = List.of("meridian_basic_techniques:light_step_volume",
            "meridian_basic_techniques:charge_volume", DOOR + "upper", "meridian_peddler:road", "meridian_witch:guard",
            "meridian_evoker:nourish");
    private static final int LEARNED_TECHNIQUES = 3 + 3 + 4 + 2 + 10;
    private enum Stage {
        PREPARE, FIRST, GIFT, HOLD, OPEN, OVERVIEW, BOOK, GIVE, READ, TOAST, REOPEN, GLOW, AFTER, DONE
    }
    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private int screenshots;
    private int chimes;
    private CompletableFuture<?> serverTask;

    @Override
    public void onInitializeClient() {
        // 隔离存档里按游戏 tick 推进，失败写进报告后退出。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        // 等真实网络投影和布局帧落定再推进下一步。
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT_TICKS, "客户端截图超时：" + stage + "，" + describe(client));
            if (ticks < READY_TICKS || client.player == null || client.getSingleplayerServer() == null
                    || client.gui.overlay() != null || ticks - stageTick < wait(stage)) {
                return;
            }
            advance(client);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(client, stage + ": " + failure);
        }
    }

    private static int wait(Stage stage) {
        // 手持要等小卡滑入，点亮要截在褪色中途，其余等布局稳定。
        return switch (stage) {
            case HOLD -> HELD_TICKS;
            case TOAST -> TOAST_TICKS;
            case GLOW -> GLOW_TICKS;
            case AFTER -> AFTER_TICKS;
            default -> SETTLE_TICKS;
        };
    }

    private void advance(Minecraft client) {
        // 每一步只做一件事，等下一批帧再看结果。
        switch (stage) {
            case PREPARE -> prepare(client);
            case FIRST -> first(client);
            case GIFT -> gift(client);
            case HOLD -> hold(client);
            case OPEN -> open(client);
            case OVERVIEW -> overview(client);
            case BOOK -> book(client);
            case GIVE -> giveMiddle(client);
            case READ -> readMiddle(client);
            case TOAST -> toast(client);
            case REOPEN -> reopen(client);
            case GLOW -> glow(client);
            case AFTER -> after(client);
            case DONE -> { }
        }
    }

    private void prepare(Minecraft client) {
        // 等服务端认定客户端加载完（在那之前的右键会被丢掉）；记下功法谱那声轻响；
        // 和真玩家一样切到生存，卷轴读完就没了（难度调成和平，免得被怪打断）；第一卷卷轴放进空着的主手。
        var server = client.getSingleplayerServer();
        var player = server.getPlayerList().getPlayer(client.player.getUUID());
        if (player == null || !player.connection.hasClientLoaded()) {
            return;
        }
        client.getSoundManager().addListener((sound, events, range) -> {
            if (sound.getIdentifier().equals(CodexMoments.LIT_SOUND.location())) {
                chimes++;
            }
        });
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(FIRST + "_scroll")));
        serverTask = server.submit(() -> {
            server.setDifficulty(Difficulty.PEACEFUL, true /* forced */);
            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        });
        next(Stage.FIRST);
    }

    private void first(Minecraft client) {
        // 和玩家一样右手读第一卷。
        if (!serverTask.isDone() || !holding(client, FIRST + "_scroll")) {
            return;
        }
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next(Stage.GIFT);
    }

    private void gift(Minecraft client) {
        // 学会第一卷时进度奖励把功法谱送进背包（卷轴读完空出主手，书就落在这格）；
        // 合上卷轴阅读页，换到功法谱那格，其余几卷走真实功法服务学会。
        var codex = hotbarSlot(client, CODEX);
        if (!CodexMoments.fresh(FIRST) || codex.isEmpty()) {
            checkState(ticks - stageTick < GIFT_TICKS, "读第一卷后没收到功法谱：" + describe(client) + "；服务端："
                    + serverSide(client));
            return;
        }
        checkState(chimes > 0, "读第一卷时没有响那一声");
        client.gui.setScreen(null);
        client.player.getInventory().setSelectedSlot(codex.getAsInt());
        var playerId = client.player.getUUID();
        serverTask = client.getSingleplayerServer().submit(() -> {
            REST.forEach(volume -> TechniqueFabric.service().learn(playerId, volume));
        });
        next(Stage.HOLD);
    }

    private void hold(Minecraft client) {
        // 已学投影到齐后截一张拿着功法谱的样子，小卡和进度小卡还在右上角。
        if (!serverTask.isDone() || CombatSlotAccess.snapshot().known().getTechniquesCount() < LEARNED_TECHNIQUES) {
            return;
        }
        serverTask.join();
        checkState(holding(client, CODEX), "主手不是功法谱");
        capture("codex-held.png");
        next(Stage.OPEN);
    }

    private void open(Minecraft client) {
        // 右键功法谱翻开书页，先翻到五脏诀看一卷里分境界的式；送书时那几张小卡已经拍过，收掉免得压住右页。
        client.gui.toastManager().clear();
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next(Stage.OVERVIEW);
    }

    private void overview(Minecraft client) {
        // 书页是独立屏幕，没有工作台窗口。
        screen(client).panel().select("meridian_evoker:five_organs");
        next(Stage.BOOK);
    }

    private void book(Minecraft client) {
        // 总览截图后翻到破门斩：上卷已学、中卷未得、下卷要真炁。
        capture("codex-overview.png");
        var progress = CodexProgress.current();
        var door = CodexMoments.catalog().books().stream()
                .filter(book -> book.id().equals(DOOR + "door_breaker")).findFirst().orElseThrow();
        var states = door.volumes().stream().map(progress::state).toList();
        checkState(states.equals(List.of(CodexProgress.State.LEARNED, CodexProgress.State.MISSING,
                CodexProgress.State.OUT_OF_REACH)), "破门斩三卷状态不对：" + states);
        screen(client).panel().select(door.id());
        next(Stage.GIVE);
    }

    private void giveMiddle(Minecraft client) {
        // 三态截图后合上书，中卷卷轴放进背包。
        capture("codex-book-states.png");
        client.gui.setScreen(null);
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(DOOR + "middle_scroll")));
        serverTask = server.submit(() -> server.getPlayerList().getPlayer(playerId).getInventory().add(stack));
        next(Stage.READ);
    }

    private void readMiddle(Minecraft client) {
        // 卷轴到了快捷栏就换到那一格读一次。
        var scroll = hotbarSlot(client, DOOR + "middle_scroll");
        if (!serverTask.isDone() || scroll.isEmpty()) {
            return;
        }
        chimes = 0;
        client.player.getInventory().setSelectedSlot(scroll.getAsInt());
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next(Stage.TOAST);
    }

    private void toast(Minecraft client) {
        // 学会中卷时弹小卡、响一声，小卡滑进来以后截一张。
        if (!CodexMoments.fresh(DOOR + "middle") || chimes == 0) {
            return;
        }
        capture("codex-lit-toast.png");
        client.gui.setScreen(null);
        client.player.getInventory().setSelectedSlot(hotbarSlot(client, CODEX).orElseThrow());
        next(Stage.REOPEN);
    }

    private void reopen(Minecraft client) {
        // 再右键功法谱，自动翻到刚添新卷的破门斩。
        checkState(holding(client, CODEX), "主手不是功法谱");
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next(Stage.GLOW);
    }

    private void glow(Minecraft client) {
        // 截在剪影褪成实色、边框起伏的那一刻。
        checkState(screen(client).panel().selectedBook().equals(DOOR + "door_breaker"), "没有翻到新卷所在的书");
        capture("codex-lit-cell.png");
        next(Stage.AFTER);
    }

    private void after(Minecraft client) {
        // 动画播完只留「新」字，最后一张对照。
        capture("codex-lit-after.png");
        finish(client, null);
    }

    private static String describe(Minecraft client) {
        // 超时时报出手上、背包、已学和屏幕，失败原因一眼可见。
        if (client.player == null) {
            return "玩家还没进世界";
        }
        var inventory = client.player.getInventory();
        var items = IntStream.range(0, inventory.getContainerSize()).filter(slot -> !inventory.getItem(slot).isEmpty())
                .mapToObj(slot -> slot + ":" + itemId(inventory.getItem(slot))).toList();
        return "主手=" + BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()) + " 背包=" + items
                + " 第一卷记新=" + CodexMoments.fresh(FIRST) + " 已学式数="
                + CombatSlotAccess.snapshot().known().getTechniquesCount() + " 屏幕=" + client.gui.screen();
    }

    private static String serverSide(Minecraft client) {
        // 在服务端线程里看同一个玩家的背包和脚边掉落物，区分「没发」「发了又没了」「掉在地上」。
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        return server.submit(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var inventory = player.getInventory();
            var items = IntStream.range(0, inventory.getContainerSize())
                    .filter(slot -> !inventory.getItem(slot).isEmpty())
                    .mapToObj(slot -> slot + ":" + itemId(inventory.getItem(slot))).toList();
            var near = player.getBoundingBox().inflate(DROP_RADIUS);
            var dropped = player.level().getEntitiesOfClass(ItemEntity.class, near).stream()
                    .map(entity -> itemId(entity.getItem())).toList();
            return "背包=" + items + " 选中格=" + inventory.getSelectedSlot() + " 脚边=" + dropped;
        }).join();
    }

    private static String itemId(ItemStack stack) {
        // 物品的注册名，报错用。
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static boolean holding(Minecraft client, String item) {
        // 客户端主手已经同步成这件物品。
        return BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).toString().equals(item);
    }

    private static OptionalInt hotbarSlot(Minecraft client, String item) {
        // 快捷栏里第一格放着这件物品的位置。
        var inventory = client.player.getInventory();
        return IntStream.range(0, Inventory.getSelectionSize()).filter(slot -> BuiltInRegistries.ITEM
                .getKey(inventory.getItem(slot).getItem()).toString().equals(item)).findFirst();
    }

    private static CodexScreen screen(Minecraft client) {
        // 右键之后当前屏幕必须是功法谱书页。
        checkState(client.gui.screen() instanceof CodexScreen, "右键功法谱没有翻开书页：" + client.gui.screen());
        return (CodexScreen) client.gui.screen();
    }

    private void next(Stage next) {
        // 进入下一步并重新计时。
        stage = next;
        stageTick = ticks;
    }

    private void capture(String name) {
        // 原版截图写入器保存帧缓冲。
        var client = Minecraft.getInstance();
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> { });
        screenshots++;
    }

    private void finish(Minecraft client, String failure) {
        // Gradle 按报告判定成败。
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
