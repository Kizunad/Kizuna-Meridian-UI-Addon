package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatStanceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import static com.google.common.base.Preconditions.checkState;

/** 在真实客户端世界中验证射线、范围、死亡与数字 ID 重用，无生产测试接线。 */
public final class CombatTargetClientCases {
    private static final int TEST_ENTITY_ID = 2_000_001;
    private static final double RAY_DISTANCE = 8;
    private static final double TARGET_HEIGHT = 1;
    private static final double BEYOND_BOUNDARY = 0.01;

    private CombatTargetClientCases() {
        // 测试实体只加在隔离客户端世界，结束时清理。
    }

    public static void run(Minecraft client) {
        // 从实际渲染相机中心建立可控目标，仍经过正式包围盒与方块射线。
        var camera = client.gameRenderer.mainCamera();
        Vec3 position = camera.position().add(new Vec3(camera.forwardVector()).scale(RAY_DISTANCE));
        var target = EntityTypes.PILLAGER.create(client.level, EntitySpawnReason.COMMAND);
        target.setId(TEST_ENTITY_ID);
        target.setPos(position.x, position.y - TARGET_HEIGHT, position.z);
        client.level.addEntity(target);
        try {
            selection(client, target);
            rangeAndDeath(client, target);
            identityReuse(client, target);
        } finally {
            client.level.removeEntity(TEST_ENTITY_ID, Entity.RemovalReason.DISCARDED);
        }
    }

    private static void selection(Minecraft client, Entity target) {
        // 遮挡阻止首次选择；空目标不会自动改选最近敌人。
        double radius = CombatStanceSettings.DEFAULT_RADIUS;
        checkState(CombatTargets.select(client, radius) == target, "中心射线未命中近处目标");
        var camera = client.gameRenderer.mainCamera();
        Vec3 midpoint = camera.position().add(new Vec3(camera.forwardVector()).scale(RAY_DISTANCE / 2));
        var block = BlockPos.containing(midpoint);
        var original = client.level.getBlockState(block);
        client.level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
        try {
            checkState(CombatTargets.select(client, radius) == null, "选择射线穿过方块");
        } finally {
            client.level.setBlockAndUpdate(block, original);
        }
        var lock = new CombatTargets();
        lock.toggle(client, radius);
        checkState(lock.current() == target);
        lock.toggle(client, radius);
        checkState(lock.current() == null, "重复按键未解除");
    }

    private static void rangeAndDeath(Minecraft client, net.minecraft.world.entity.monster.illager.Pillager target) {
        // 范围采用玩家到实体位置的三维距离，边界包含，超界立即解除。
        double radius = CombatStanceSettings.DEFAULT_RADIUS;
        var original = target.position();
        var lock = new CombatTargets();
        lock.toggle(client, radius);
        target.setPos(client.player.position().add(radius, 0, 0));
        lock.validate(client, radius);
        checkState(lock.current() == target, "半径边界错误解除");
        target.setPos(client.player.position().add(radius + BEYOND_BOUNDARY, 0, 0));
        lock.validate(client, radius);
        checkState(lock.current() == null, "超过半径未解除");
        target.setPos(original);
        lock.validate(client, radius);
        checkState(lock.current() == null, "目标回到范围后自动恢复");
        lock.toggle(client, radius);
        target.setHealth(0);
        lock.validate(client, radius);
        checkState(lock.current() == null, "目标死亡未解除");
        target.setHealth(target.getMaxHealth());
    }

    private static void identityReuse(Minecraft client, Entity target) {
        // 数字 ID 被另一 UUID 重用时不得继承旧锁定。
        var lock = new CombatTargets();
        lock.toggle(client, CombatStanceSettings.DEFAULT_RADIUS);
        client.level.removeEntity(TEST_ENTITY_ID, Entity.RemovalReason.UNLOADED_TO_CHUNK);
        var replacement = EntityTypes.PILLAGER.create(client.level, EntitySpawnReason.COMMAND);
        replacement.setId(TEST_ENTITY_ID);
        replacement.setPos(target.position());
        client.level.addEntity(replacement);
        lock.validate(client, CombatStanceSettings.DEFAULT_RADIUS);
        checkState(lock.current() == null, "实体 ID 重用继承了目标锁定");
    }
}
