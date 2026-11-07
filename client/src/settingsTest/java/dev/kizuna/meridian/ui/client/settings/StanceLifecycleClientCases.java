package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.meridian.combat.core.CombatViewSettings;
import dev.kizuna.meridian.combat.fabric.client.CombatSettingsAccess;
import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import dev.kizuna.meridian.ui.api.settings.MeridianSettings;
import dev.kizuna.meridian.ui.client.settings.combat.CombatCameraScreen;
import dev.kizuna.meridian.ui.client.settings.combat.CombatSettingsProvider;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import static com.google.common.base.Preconditions.checkState;
import static dev.kizuna.meridian.ui.client.settings.SettingsTestInput.*;

/** 服务端驱动真实旁观、死亡重生和维度切换，验证相机预览失效。 */
final class StanceLifecycleClientCases {
    private static final int PLATFORM_Y = 80;
    private static final int PLAYER_Y = PLATFORM_Y + 1;
    private static final int PLATFORM_RADIUS = 3;
    private static final int CLEARANCE = 3;
    private final Minecraft game = Minecraft.getInstance();
    private final Supplier<ParentUIComponent> page;
    private CompletableFuture<?> pending;
    private CombatSettingsAccess.Preview preview;
    private CombatViewSettings savedView;
    private LocalPlayer deadPlayer;

    StanceLifecycleClientCases(Supplier<ParentUIComponent> page) {
        // 服务端操作只修改隔离存档中的测试玩家，客户端等待真实同步。
        this.page = page;
    }

    boolean openPage() {
        // 每次失效场景都从正式打开接口进入，保留真实页面生命周期。
        checkState(CombatViewController.forShoulder(game.player) != null);
        MeridianSettings.open(CombatSettingsProvider.ID);
        return true;
    }

    boolean spectatorPreview() throws Exception {
        // 先进入真实预览，再由服务端切换旁观，不能只改客户端标志。
        beginPreview();
        submit(player -> player.setGameMode(GameType.SPECTATOR));
        return true;
    }

    boolean spectatorEntered() {
        // 等待同步后检查预览令牌释放及草稿未保存。
        if (!serverReady() || !game.player.isSpectator()) {
            return false;
        }
        assertInvalidated();
        submit(player -> player.setGameMode(GameType.SURVIVAL));
        return true;
    }

    boolean spectatorRestored() {
        // 回到生存不应恢复旧架势，须玩家重新按 R。
        if (!serverReady() || game.player.isSpectator()) {
            return false;
        }
        checkState(CombatViewController.forShoulder(game.player) == null);
        game.gui.setScreen(null);
        return true;
    }

    boolean resumeStance() throws Exception {
        // 关屏首帧必须先清理残留输入，下一独立步骤才发送新 R。
        checkState(game.gui.screen() == null);
        tap(GLFW.GLFW_KEY_R);
        return true;
    }

    boolean deathPreview() throws Exception {
        // 死亡通过服务端玩家和原版伤害来源广播给客户端。
        beginPreview();
        deadPlayer = game.player;
        submit(player -> {
            player.setHealth(0);
            player.die(player.damageSources().genericKill());
        });
        return true;
    }

    boolean deathEntered() {
        // 死亡替换编辑屏幕不反向打开工作区，原参数仍在。
        if (!serverReady() || game.player.isAlive()) {
            return false;
        }
        assertInvalidated();
        game.player.respawn();
        return true;
    }

    boolean respawned() {
        // 等待新玩家对象后再进入架势，旧预览令牌不得附着新实体。
        if (game.player == deadPlayer || !game.player.isAlive() || game.gui.overlay() != null) {
            return false;
        }
        checkState(!preview.active());
        checkState(CombatViewController.forShoulder(game.player) == null);
        submit(player -> player.connection.teleport(0, PLAYER_Y, 0, 0, 0));
        game.gui.setScreen(null);
        return true;
    }

    boolean dimensionPreview() throws Exception {
        // 维度传送走原版服务端同步，客户端会建立新的 ClientLevel。
        if (!serverReady()) {
            return false;
        }
        beginPreview();
        submit(player -> transfer(player, Level.NETHER));
        return true;
    }

    boolean dimensionChanged() {
        // 世界变化必须释放原草稿，不能在接收新世界时重新启用它。
        if (!serverReady() || game.level.dimension() != Level.NETHER || game.gui.overlay() != null) {
            return false;
        }
        assertInvalidated();
        submit(player -> transfer(player, Level.OVERWORLD));
        return true;
    }

    boolean dimensionReturned() {
        // 返回原维度只使用已保存参数，临时预览不会自动恢复。
        if (!serverReady() || game.level.dimension() != Level.OVERWORLD || game.gui.overlay() != null) {
            return false;
        }
        checkState(!preview.active());
        checkState(CombatSettingsAccess.snapshot().view().equals(savedView));
        game.gui.setScreen(null);
        return true;
    }

    private void beginPreview() throws Exception {
        // 保存基线后通过实际按钮打开透明屏幕，并产生可丢弃的相机草稿。
        savedView = CombatSettingsAccess.snapshot().view();
        click(page.get().childById(UIComponent.class, "combat-preview"));
        checkState(game.gui.screen() instanceof CombatCameraScreen);
        preview = field(game.gui.screen(), "preview", CombatSettingsAccess.Preview.class);
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_PRESS);
    }

    private void assertInvalidated() {
        // 同时验证服务令牌、屏幕和持久化边界，避免只凭画面消失判断。
        checkState(!preview.active(), "失效事件保留了相机草稿令牌");
        checkState(!(game.gui.screen() instanceof CombatCameraScreen), "失效事件未退出编辑屏幕");
        checkState(CombatSettingsAccess.snapshot().view().equals(savedView), "失效退出保存了草稿");
    }

    private void submit(Consumer<ServerPlayer> action) {
        // 用 UUID 重新定位当前服务端玩家，死亡后不能复用旧对象。
        var server = game.getSingleplayerServer();
        var id = game.player.getUUID();
        pending = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }

    private boolean serverReady() {
        // 同步操作完成后传播服务端异常，不能把失败吞成等待。
        if (pending == null) {
            return true;
        }
        if (!pending.isDone()) {
            return false;
        }
        pending.join();
        pending = null;
        return true;
    }

    private void transfer(ServerPlayer player, ResourceKey<Level> dimension) {
        // 传送终点有最小实心平台，避免环境伤害掩盖维度生命周期行为。
        var level = game.getSingleplayerServer().getLevel(dimension);
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                level.setBlockAndUpdate(new BlockPos(x, PLATFORM_Y, z), Blocks.STONE.defaultBlockState());
                for (int y = PLAYER_Y; y <= PLAYER_Y + CLEARANCE; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        player.teleportTo(level, 0, PLAYER_Y, 0, Set.of(), 0, 0, false /* resetCamera */);
    }
}
