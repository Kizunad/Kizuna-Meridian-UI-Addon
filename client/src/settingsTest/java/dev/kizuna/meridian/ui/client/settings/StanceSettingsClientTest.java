package dev.kizuna.meridian.ui.client.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.SettingsWindowProbe;
import dev.kizuna.inventoryui.client.WorkspaceScreen;
import dev.kizuna.meridian.combat.core.CombatConfigFile;
import dev.kizuna.meridian.combat.core.CombatStanceSettings;
import dev.kizuna.meridian.combat.core.CombatView;
import dev.kizuna.meridian.combat.core.CombatViewSettings;
import dev.kizuna.meridian.combat.core.CombatViewMath;
import dev.kizuna.meridian.combat.core.CombatDirection;
import dev.kizuna.meridian.combat.fabric.client.CombatSettingsAccess;
import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import dev.kizuna.meridian.ui.api.settings.MeridianSettings;
import dev.kizuna.meridian.ui.client.settings.combat.CombatCameraScreen;
import dev.kizuna.meridian.ui.client.settings.combat.CombatSettingsProvider;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import static com.google.common.base.Preconditions.checkState;
import static dev.kizuna.meridian.ui.client.settings.SettingsTestInput.*;

/** 隔离完整装配客户端验证设置、真实镜头、输入分发和实体朝向，并保留截图。 */
public final class StanceSettingsClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../stance-settings-results.txt");
    private static final Path SCREENS = Path.of("../stance-settings-screens");
    private static final int READY_TICKS = 80;
    private static final int STEP_TICKS = 15;
    private static final int TIMEOUT_TICKS = 3600;
    private static final int FLOOR_Y = 80;
    private static final int PLAYER_Y = FLOOR_Y + 1;
    private static final int PLATFORM_RADIUS = 16;
    private static final int TARGET_Z = 8;
    private static final float INITIAL_PITCH = 5;
    private static final float LOOK_DELTA = 30;
    private static final float TARGET_ORBIT_DELTA = 240;
    private static final double EPSILON = 0.001;
    private static final int FOCUS_WINDOW_WIDTH = 200;
    private static final int FOCUS_WINDOW_HEIGHT = 100;
    private static final int CLEARANCE_HEIGHT = 3;
    private static final int WALL_Z = -2;
    private static final int WALL_MIN_X = -2;
    private static final int SCROLL_TO_END = -100;
    private static final int SCROLL_INSET = 12;
    private static final double MIN_MOVEMENT = 0.25;
    private static final double FACING_DOT_MINIMUM = 0.98;
    private final List<String> evidence = new ArrayList<>();
    private final StanceInputClientCases inputCases = new StanceInputClientCases(this::settingsRoot, evidence::add);
    private final StanceLifecycleClientCases lifecycleCases = new StanceLifecycleClientCases(this::settingsRoot);
    private final List<Step> steps = List.of(this::prepare, this::openBook, this::openOutside, this::outsideChecks,
            this::confirmSwitch,
            this::enterStance, this::enterShoulder, this::openInside, this::recordKey, this::cancelParent,
            this::reopenAndPreview, this::previewMoving, this::previewCancel, this::previewCancelled,
            this::previewSaveStart, this::previewSave, this::parentCancelAfterSave, this::verifySaved,
            this::lockView, this::verifyLock, this::lockBackpedal, this::lockStrafe, this::lockReset,
            this::targetSelect, this::targetOrbit, this::targetOrbitChecked, this::targetFollow, this::targetMoved,
            this::targetStrafe, this::targetBackpedal, this::targetJump, this::targetMovementRestore,
            this::targetOutOfRange, this::targetCleared, this::invalidSave, this::invalidSaved,
            this::scales, this::scaleThree, this::scaleFour, this::scaleFourSaved,
            this::targetBoundaries, this::collisionPreview,
            this::collisionChecked, this::focusLost, this::focusRegained, this::focusChecked,
            this::saveFailure, this::failureRetained, this::cameraInvalidated, this::invalidatedChecked,
            inputCases::lockLongPress, inputCases::lockRepeated, inputCases::unlockLook,
            inputCases::firstPerson, inputCases::firstPersonCheck, inputCases::returnShoulder,
            inputCases::rebindPage, inputCases::rebindPreview, inputCases::oldKeyIgnored,
            inputCases::newKeyMoves, inputCases::previewEscCleanup,
            inputCases::frameLowStart, inputCases::frameLowChecked, inputCases::frameHighChecked,
            inputCases::partialSavePage, inputCases::partialSaveStart, inputCases::partialSaveChecked,
            inputCases::partialSaveRetried,
            lifecycleCases::openPage, lifecycleCases::spectatorPreview, lifecycleCases::spectatorEntered,
            lifecycleCases::spectatorRestored, lifecycleCases::resumeStance, lifecycleCases::openPage,
            lifecycleCases::deathPreview, lifecycleCases::deathEntered, lifecycleCases::respawned,
            lifecycleCases::resumeStance, lifecycleCases::openPage,
            lifecycleCases::dimensionPreview, lifecycleCases::dimensionChanged,
            lifecycleCases::dimensionReturned,
            this::finishChecks);
    private int ticks;
    private int stage;
    private int stageTick;
    private int pendingCaptures;
    private boolean finished;
    private CompletableFuture<?> serverTask;
    private UUID targetId;
    private ParentUIComponent settingsRoot;
    private CombatViewSettings beforePreview;
    private Vec3 playerPosition;
    private CombatView.Angles cameraAngles;
    private float beforeTargetYaw;
    private long focusWindow;
    private CombatViewSettings focusDraft;
    private Path savedConfig;
    private boolean reconnectPending;
    private boolean rejoinStarted;
    private CombatViewSettings persistedView;
    private final Minecraft game = Minecraft.getInstance();

    @Override
    public void onInitializeClient() {
        // 无世界时公开接口不能缓存打开请求等待以后登录。
        checkState(MeridianSettings.open().status() == MeridianSettings.Status.UNAVAILABLE);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        // 每步等待真实客户端 tick 和渲染，异常与阶段名写到可审核报告。
        if (finished) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT_TICKS, "超时 stage=%s", stage);
            if (reconnectPending) {
                reconnect();
                return;
            }
            if (ticks < READY_TICKS || client.player == null || client.gui.overlay() != null
                    || ticks - stageTick < STEP_TICKS || serverTask != null && !serverTask.isDone()) {
                return;
            }
            if (serverTask != null) {
                serverTask.join();
                serverTask = null;
            }
            if (steps.get(stage).run()) {
                evidence.add("stage=" + stage + " PASS tick=" + ticks);
                stage++;
                stageTick = ticks;
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish("stage=" + stage + " " + failure);
        }
    }

    private boolean prepare() throws Exception {
        // 测试平台、装备与目标来自真实服务端实体，避免独立预览模型代替游戏世界。
        Files.createDirectories(SCREENS);
        var fromDisk = new CombatConfigFile(configPath()).load();
        var startup = CombatSettingsAccess.snapshot();
        checkState(startup.view().equals(fromDisk.view()) && startup.stance().equals(fromDisk.stance()));
        evidence.add("fresh-process/persisted-settings-loaded PASS");
        CombatSettingsAccess.save(CombatStanceSettings.DEFAULT);
        CombatSettingsAccess.saveKeys(new CombatSettingsAccess.Keys(InputConstants.UNKNOWN, InputConstants.UNKNOWN));
        game.options.pauseOnLostFocus = false;
        game.options.guiScale().set(2);
        game.resizeGui();
        game.gui.setScreen(null);
        game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        var server = game.getSingleplayerServer();
        var playerId = game.player.getUUID();
        serverTask = server.submit(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.level();
            preparePlatform(level);
            player.connection.teleport(0, PLAYER_Y, 0, 0, INITIAL_PITCH);
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            var target = EntityTypes.PILLAGER.create(level, EntitySpawnReason.COMMAND);
            target.setNoAi(true /* noAi */);
            target.setPos(-CombatViewSettings.DEFAULT.shoulderOffset(), PLAYER_Y, TARGET_Z);
            level.addFreshEntity(target);
            targetId = target.getUUID();
        });
        return true;
    }

    private static void preparePlatform(net.minecraft.server.level.ServerLevel level) {
        // 清除隔离试验场上次留下的目标与墙，确保重跑不受上一局状态影响。
        var bounds = new net.minecraft.world.phys.AABB(-PLATFORM_RADIUS, FLOOR_Y, -PLATFORM_RADIUS,
                PLATFORM_RADIUS, PLAYER_Y + CLEARANCE_HEIGHT, PLATFORM_RADIUS);
        level.getEntitiesOfClass(net.minecraft.world.entity.monster.illager.Pillager.class, bounds)
                .forEach(net.minecraft.world.entity.Entity::discard);
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                level.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), Blocks.STONE.defaultBlockState());
                for (int y = PLAYER_Y; y <= PLAYER_Y + CLEARANCE_HEIGHT; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private boolean openOutside() {
        // 点击功法配置的公开入口，之后再确认与设置菜单使用同一个窗口。
        var runtime = InventoryUiClient.runtime();
        var book = runtime.workspace().windows().snapshot().stream()
                .filter(state -> state.key().windowType().equals("meridian_ui:combat_window"))
                .findFirst().orElseThrow();
        var component = SettingsWindowProbe.content(book.key());
        click(component.childById(UIComponent.class, "stance-settings"));
        var fromBook = settingsRoot();
        clickNamed("功能");
        var search = game.gui.screen().children().stream()
                .filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                .map(net.minecraft.client.gui.components.EditBox.class::cast)
                .findFirst().orElseThrow();
        search.setValue("设置");
        clickNamed("Meridian / 设置");
        settingsRoot = settingsRoot();
        checkState(settingsRoot == fromBook, "功能目录和功法按钮没有共用设置窗口");
        evidence.add("inventory-feature-button/book-button/same-window PASS");
        return true;
    }

    private boolean openBook() {
        // 功法窗口通过正式模块工厂创建，姿态按钮不能绕开工作区。
        var runtime = InventoryUiClient.runtime();
        runtime.open("meridian_ui:combat", null);
        runtime.openWorkspace();
        return true;
    }

    private boolean outsideChecks() throws Exception {
        // 架势外预览置灰；重复打开不创建窗口、不替换草稿组件。
        var preview = settingsRoot.childById(io.wispforest.owo.ui.component.ButtonComponent.class, "combat-preview");
        checkState(!preview.active(), "架势外预览未置灰");
        var radius = settingsRoot.childById(TextBoxComponent.class, "combat-radius");
        radius.text("25");
        checkState(MeridianSettings.open(CombatSettingsProvider.ID).status() == MeridianSettings.Status.FOCUSED);
        checkState(settingsRoot == settingsRoot(), "重复打开丢失组件");
        checkState(radius.getValue().equals("25"));
        var unknown = Identifier.fromNamespaceAndPath("test", "unknown");
        checkState(MeridianSettings.open(unknown).status() == MeridianSettings.Status.UNKNOWN_PAGE);
        screenshot("outside-disabled");
        checkState(MeridianSettings.open(SettingsSmokeProvider.ID).status() == MeridianSettings.Status.DIRTY_PAGE);
        return true;
    }

    private boolean confirmSwitch() throws Exception {
        // 程序定向打开同样触发草稿确认，确认后才切到测试提供者页面。
        click(child("settings-discard"));
        checkState(MeridianSettings.open(SettingsSmokeProvider.ID).status() == MeridianSettings.Status.FOCUSED);
        MeridianSettings.open(CombatSettingsProvider.ID);
        settingsRoot = settingsRoot();
        checkState(settingsRoot.childById(TextBoxComponent.class, "combat-radius").getValue().equals("50.0"));
        tap(GLFW.GLFW_KEY_ESCAPE);
        game.gui.setScreen(null);
        return true;
    }

    private boolean enterStance() throws Exception {
        // R 仍进入战斗架势，不被设置入口替换。
        tap(GLFW.GLFW_KEY_R);
        return true;
    }

    private boolean enterShoulder() throws Exception {
        // 首次默认第一人称，V 切换越肩。
        if (CombatViewController.forShoulder(game.player) == null) {
            tap(GLFW.GLFW_KEY_V);
        }
        return true;
    }

    private boolean openInside() {
        // 从公开接口打开后记录同一个宿主组件，后续预览必须回到它。
        checkState(CombatViewController.forShoulder(game.player) != null, "R/V 未进入越肩");
        describeCamera();
        MeridianSettings.open(CombatSettingsProvider.ID);
        settingsRoot = settingsRoot();
        return true;
    }

    private boolean recordKey() throws Exception {
        // 原版键盘分发 R 被录入，不能同时退出架势。
        click(child("combat-key-LOCK_VIEW"));
        tap(GLFW.GLFW_KEY_R);
        checkState(CombatViewController.forShoulder(game.player) != null);
        checkState(CombatSettingsAccess.keyMapping(CombatSettingsAccess.Action.LOCK_VIEW).isUnbound());
        screenshot("key-conflict-draft");
        return true;
    }

    private boolean cancelParent() {
        // 明确取消父页不写键位，不覆盖 options 的其他字段。
        var button = settingsRoot.childById(io.wispforest.owo.ui.component.ButtonComponent.class,
                "combat-key-LOCK_VIEW");
        checkState(button.getMessage().getString().contains("R"), "录入未写入页面草稿");
        screenshot("key-conflict-recorded");
        click(child("combat-cancel"));
        checkState(CombatSettingsAccess.keyMapping(CombatSettingsAccess.Action.LOCK_VIEW).isUnbound());
        return true;
    }

    private boolean reopenAndPreview() {
        // 父页其他字段留在草稿中，子编辑不会把它们一起保存。
        MeridianSettings.open(CombatSettingsProvider.ID);
        settingsRoot = settingsRoot();
        settingsRoot.childById(TextBoxComponent.class, "combat-radius").text("25");
        beforePreview = CombatSettingsAccess.snapshot().view();
        playerPosition = game.player.position();
        cameraAngles = controller().view().camera();
        return true;
    }

    private boolean previewMoving() throws Exception {
        // 模拟实际新按下事件，鼠标保持未捕获；按住键只能调整草稿。
        click(child("combat-preview"));
        checkState(game.gui.screen() instanceof CombatCameraScreen, "未进入真实预览屏幕");
        checkState(!game.mouseHandler.isMouseGrabbed(), "预览鼠标仍捕获");
        key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean previewCancel() throws Exception {
        // 玩家未被移动键驱动，正式配置未提前改变，实际相机显示独立草稿。
        checkState(game.player.position().distanceTo(playerPosition) < EPSILON, "预览驱动了玩家移动");
        checkState(CombatSettingsAccess.snapshot().view().equals(beforePreview), "预览提前写正式配置");
        checkState(controller().view().camera().equals(cameraAngles), "预览旋转了相机");
        screenshot("preview-left-down");
        tap(GLFW.GLFW_KEY_ESCAPE);
        return true;
    }

    private boolean previewCancelled() {
        // 返回同一个父页，父页未保存字段仍在，预览取消恢复进入参数。
        checkState(game.gui.screen() instanceof WorkspaceScreen);
        checkState(settingsRoot == settingsRoot());
        checkState(settingsRoot.childById(TextBoxComponent.class, "combat-radius").getValue().equals("25"));
        checkState(CombatSettingsAccess.snapshot().view().equals(beforePreview));
        checkState(!game.mouseHandler.isMouseGrabbed());
        return true;
    }

    private boolean previewSaveStart() throws Exception {
        // 再次预览通过真实保存按钮提交，随后父页取消不得回滚。
        click(child("combat-preview"));
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean previewSave() throws Exception {
        // 坐标点击进入 owo 保存按钮，不直接调用门面模拟 UI 成功。
        screenshot("preview-save-visible-cursor");
        click(previewRoot().childById(UIComponent.class, "camera-save"));
        return true;
    }

    private boolean parentCancelAfterSave() {
        // 相机已经明确保存，父页取消只放弃尚未保存的半径。
        checkState(game.gui.screen() instanceof WorkspaceScreen);
        checkState(settingsRoot == settingsRoot());
        checkState(!CombatSettingsAccess.snapshot().view().equals(beforePreview));
        click(child("combat-cancel"));
        game.gui.setScreen(null);
        return true;
    }

    private boolean verifySaved() throws Exception {
        // 独立读取磁盘确认相机持久化，父半径仍为默认值。
        var loaded = new CombatConfigFile(configPath()).load();
        checkState(loaded.view().equals(CombatSettingsAccess.snapshot().view()));
        checkState(loaded.stance().targetRadius() == CombatStanceSettings.DEFAULT_RADIUS);
        var file = new CombatConfigFile(configPath());
        var originalGeometry = loaded.configured(beforePreview, loaded.stance());
        file.save(originalGeometry);
        // 后续选目标回到默认几何，通过正式预览恢复默认并保存。
        var preview = CombatSettingsAccess.beginPreview();
        preview.reset();
        checkState(preview.save().status() == CombatSettingsAccess.Status.SAVED);
        return true;
    }

    private boolean lockView() throws Exception {
        // 真实共享 KeyMapping 注册键位，并沿公开保存入口验证 options 落盘。
        var keys = new CombatSettingsAccess.Keys(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F7),
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8));
        checkState(CombatSettingsAccess.saveKeys(keys).status() == CombatSettingsAccess.Status.SAVED);
        cameraAngles = controller().view().camera();
        var locked = new CombatStanceSettings(CombatStanceSettings.ViewLock.ON, CombatStanceSettings.DEFAULT_RADIUS);
        checkState(CombatSettingsAccess.save(locked).status() == CombatSettingsAccess.Status.SAVED);
        mouseLook(LOOK_DELTA, LOOK_DELTA);
        return true;
    }

    private boolean verifyLock() throws Exception {
        // 鼠标经原版路径带动镜头、真实人物和头部；没有目标时移动不能争抢朝向。
        var camera = controller().view().camera();
        checkState(Math.abs(camera.yaw() - cameraAngles.yaw()) > EPSILON, "Lock 开启后镜头仍被固定");
        checkState(Math.abs(camera.pitch() - cameraAngles.pitch()) > EPSILON, "Lock 未响应鼠标俯仰");
        assertFacingCamera();
        screenshot("view-lock-mouse-follow");
        playerPosition = game.player.position();
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean lockBackpedal() throws Exception {
        // 后退按镜头轴位移，人物仍看向镜头前方，不掉头转向移动方向。
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_RELEASE);
        var camera = controller().view().camera();
        var forward = CombatDirection.fromInput(1, 0, camera.yaw());
        Vec3 movement = game.player.position().subtract(playerPosition);
        checkState(movement.x * forward.x() + movement.z * forward.z() < -MIN_MOVEMENT);
        assertFacingCamera();
        playerPosition = game.player.position();
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean lockStrafe() throws Exception {
        // 侧移仍沿镜头右轴，转头不会被移动转身覆盖。
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_RELEASE);
        var camera = controller().view().camera();
        var right = CombatDirection.fromInput(0, -1, camera.yaw());
        Vec3 movement = game.player.position().subtract(playerPosition);
        checkState(movement.x * right.x() + movement.z * right.z() > MIN_MOVEMENT);
        assertFacingCamera();
        screenshot("view-lock-strafe");
        evidence.add("view-lock-on/mouse-yaw-pitch/head/backpedal/strafe PASS");
        var server = game.getSingleplayerServer();
        var playerId = game.player.getUUID();
        serverTask = server.submit(() -> server.getPlayerList().getPlayer(playerId)
                .connection.teleport(0, PLAYER_Y, 0, 0, INITIAL_PITCH));
        return true;
    }

    private boolean lockReset() throws Exception {
        // 恢复选靶场景的相机方向，后续射线仍命中同一个真实服务端目标。
        checkState(game.player.position().distanceTo(new Vec3(0, PLAYER_Y, 0)) < EPSILON);
        controller().view().restoreCamera(cameraAngles);
        tap(GLFW.GLFW_KEY_F8);
        return true;
    }

    private void assertFacingCamera() {
        // 直接观察真实实体及模型角度，不以配置开关代替联动结果。
        var camera = controller().view().camera();
        var player = game.player;
        checkState(Math.abs(CombatViewMath.wrapYaw(player.getYRot() - camera.yaw())) < EPSILON);
        checkState(Math.abs(player.getXRot() - camera.pitch()) < EPSILON);
        checkState(Math.abs(CombatViewMath.wrapYaw(player.getYHeadRot() - camera.yaw())) < EPSILON);
        checkState(Math.abs(CombatViewMath.wrapYaw(player.yBodyRot - camera.yaw())) < EPSILON);
    }

    private boolean targetSelect() throws Exception {
        // 验证选择的是实际世界中射线命中的掠夺者。
        Object targets = field(controller(), "targets", Object.class);
        describeCamera();
        var selected = field(targets, "target", net.minecraft.world.entity.Entity.class);
        checkState(selected != null && selected.getUUID().equals(targetId), "未选中实际相机射线目标");
        beforeTargetYaw = game.player.getYRot();
        screenshot("target-static-lock");
        return true;
    }

    private boolean targetOrbit() throws Exception {
        // 同时启用时鼠标照常转镜头，但不能抢走人物朝向目标的优先权。
        mouseLook(TARGET_ORBIT_DELTA, LOOK_DELTA);
        cameraAngles = controller().view().camera();
        return true;
    }

    private boolean targetOrbitChecked() throws Exception {
        // 人物继续瞄准目标，镜头方向已经与人物明显分离。
        assertFacingTarget();
        checkState(Math.abs(CombatViewMath.wrapYaw(game.player.getYRot() - cameraAngles.yaw())) > LOOK_DELTA);
        screenshot("target-priority-mouse-orbit");
        mouseLook(-TARGET_ORBIT_DELTA, -LOOK_DELTA);
        cameraAngles = controller().view().camera();
        evidence.add("target-priority/mouse-orbit-keeps-target-facing PASS");
        return true;
    }

    private boolean targetFollow() {
        // 服务端移动真实目标，客户端依靠跟踪实体更新而非测试伪造朝向。
        var server = game.getSingleplayerServer();
        serverTask = server.submit(() -> server.overworld().getEntity(targetId).setPos(TARGET_Z, PLAYER_Y, TARGET_Z));
        return true;
    }

    private boolean targetMoved() throws Exception {
        // 镜头角不变，真实人物 yaw 跟随移动目标。
        checkState(Math.abs(game.player.getYRot() - beforeTargetYaw) > 1, "目标移动未带动人物 yaw");
        checkState(controller().view().camera().equals(cameraAngles));
        screenshot("target-moving-lock");
        playerPosition = game.player.position();
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean targetStrafe() throws Exception {
        // 人物朝向右前方目标时，D 仍沿镜头右轴侧移，不被真实 yaw 拖偏。
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_RELEASE);
        checkState(game.player.getX() < playerPosition.x - MIN_MOVEMENT, "目标朝向改变了侧移方向");
        assertFacingTarget();
        playerPosition = game.player.position();
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean targetBackpedal() throws Exception {
        // 后退不强迫人物掉头，目标朝向优先于移动转身。
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_RELEASE);
        checkState(game.player.getZ() < playerPosition.z - MIN_MOVEMENT, "目标朝向改变了后退方向");
        assertFacingTarget();
        screenshot("target-strafe-backpedal");
        playerPosition = game.player.position();
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean targetJump() throws Exception {
        // 前进疾跑跳跃的额外冲量沿镜头前轴，不追逐人物朝向的目标。
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE);
        key(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_RELEASE);
        checkState(game.player.getZ() > playerPosition.z + MIN_MOVEMENT, "疾跑跳跃没有沿镜头前进");
        checkState(game.player.getY() > playerPosition.y, "跳跃输入未生效");
        assertFacingTarget();
        screenshot("target-sprint-jump");
        var server = game.getSingleplayerServer();
        var playerId = game.player.getUUID();
        serverTask = server.submit(() -> server.getPlayerList().getPlayer(playerId)
                .connection.teleport(0, PLAYER_Y, 0, 0, INITIAL_PITCH));
        return true;
    }

    private boolean targetMovementRestore() {
        // 还原独立场景起点，以免后续固定墙坐标受到移动测试影响。
        checkState(game.player.position().distanceTo(new Vec3(0, PLAYER_Y, 0)) < EPSILON);
        evidence.add("target-priority/strafe/backpedal/sprint-jump PASS");
        return true;
    }

    private void assertFacingTarget() throws Exception {
        // 用真实视线与目标眼位夹角断言行为，不复制控制器的限速算法。
        Object targets = field(controller(), "targets", Object.class);
        var target = field(targets, "target", net.minecraft.world.entity.Entity.class);
        checkState(target != null);
        Vec3 desired = target.getEyePosition().subtract(game.player.getEyePosition()).normalize();
        checkState(game.player.getLookAngle().dot(desired) > FACING_DOT_MINIMUM, "移动转身覆盖了目标朝向");
        checkState(controller().view().camera().equals(cameraAngles));
    }

    private boolean targetOutOfRange() {
        // 保存更小半径后立即校验已锁定目标，不能等下一次按键。
        var settings = new CombatStanceSettings(CombatStanceSettings.ViewLock.ON, CombatStanceSettings.MIN_RADIUS);
        checkState(CombatSettingsAccess.save(settings).status() == CombatSettingsAccess.Status.SAVED);
        return true;
    }

    private boolean targetCleared() throws Exception {
        // 清理后不自动挑下一个目标，仍开启的视角联动重新让人物朝向镜头。
        Object targets = field(controller(), "targets", Object.class);
        checkState(field(targets, "target", net.minecraft.world.entity.Entity.class) == null);
        assertFacingCamera();
        evidence.add("target-cleared/view-lock-follow-resumes PASS");
        MeridianSettings.open(CombatSettingsProvider.ID);
        settingsRoot = settingsRoot();
        return true;
    }

    private boolean invalidSave() {
        // 文本框接受草稿，但非法 NaN 必须由业务校验拒绝。
        settingsRoot.childById(TextBoxComponent.class, "combat-radius").text("NaN");
        click(child("combat-save"));
        return true;
    }

    private boolean invalidSaved() throws Exception {
        // 错误留在同一页面，没有伪造成功或关闭编辑器。
        checkState(settingsRoot == settingsRoot());
        checkState(CombatSettingsAccess.snapshot().stance().targetRadius() == CombatStanceSettings.MIN_RADIUS);
        screenshot("invalid-radius-retained");
        settingsRoot.childById(TextBoxComponent.class, "combat-radius").text("50");
        return true;
    }

    private boolean scales() throws Exception {
        // 实际窗口以三种常用 GUI scale 布局并留存帧缓冲证据。
        screenshot("settings-scale-2");
        game.options.guiScale().set(3);
        game.resizeGui();
        return true;
    }

    private boolean scaleThree() throws Exception {
        // 缩放不能重建业务草稿或把输入丢给游戏。
        checkState(settingsRoot == settingsRoot());
        screenshot("settings-scale-3");
        game.options.guiScale().set(4);
        game.resizeGui();
        return true;
    }

    private boolean scaleFour() throws Exception {
        // 最紧凑尺寸必须能滚动到保存按钮，不能只凭初始布局截图判定可用。
        screenshot("settings-scale-4");
        game.gui.screen().mouseScrolled(settingsRoot.x() + settingsRoot.width() - SCROLL_INSET,
                settingsRoot.y() + settingsRoot.height() / 2.0, 0, SCROLL_TO_END);
        return true;
    }

    private boolean scaleFourSaved() {
        // 实际点击滚动后的按钮并核对正式配置，验证按钮位于屏幕可见范围。
        var save = child("combat-save");
        checkState(save.y() >= 0 && save.y() + save.height() <= game.getWindow().getGuiScaledHeight());
        click(save);
        checkState(CombatSettingsAccess.snapshot().stance().targetRadius() == CombatStanceSettings.DEFAULT_RADIUS);
        screenshot("settings-scale-4-save");
        game.options.guiScale().set(2);
        game.resizeGui();
        game.gui.setScreen(null);
        return true;
    }

    private boolean targetBoundaries() {
        // 专用测试实体使用真实客户端包围盒与生命周期，覆盖不可由单元测试代替的世界查询。
        dev.kizuna.meridian.combat.fabric.client.CombatTargetClientCases.run(game);
        evidence.add("ray-block-occlusion/repeat-toggle/radius-50-boundary/death/unload/id-reuse PASS");
        MeridianSettings.open(CombatSettingsProvider.ID);
        settingsRoot = settingsRoot();
        return true;
    }

    private boolean collisionPreview() {
        // 在正式相机到玩家的路径放置墙，预览必须使用同一碰撞收缩。
        var server = game.getSingleplayerServer();
        serverTask = server.submit(() -> {
            var level = server.overworld();
            for (int x = WALL_MIN_X; x <= 1; x++) {
                for (int y = PLAYER_Y; y <= PLAYER_Y + CLEARANCE_HEIGHT; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, WALL_Z), Blocks.STONE.defaultBlockState());
                }
            }
        });
        click(child("combat-preview"));
        return true;
    }

    private boolean collisionChecked() throws Exception {
        // 碰撞反馈不改期望距离，保存的是草稿而非当前被墙推近的相机位置。
        var preview = field(game.gui.screen(), "preview", CombatSettingsAccess.Preview.class);
        checkState(preview.clipped(), "贴墙没有经过正式碰撞路径");
        checkState(preview.draft().distance() == beforePreview.distance());
        screenshot("preview-wall-collision");
        key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS);
        return true;
    }

    private boolean focusLost() throws Exception {
        // 创建第二个 X11 窗口触发真实 GLFW 焦点变化，不只调用屏幕取消方法。
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        focusWindow = GLFW.glfwCreateWindow(FOCUS_WINDOW_WIDTH, FOCUS_WINDOW_HEIGHT, "stance-focus-test", 0, 0);
        checkState(focusWindow != 0, "无法创建失焦验收窗口");
        focus(focusWindow);
        return true;
    }

    private boolean focusRegained() throws Exception {
        // 失焦期间相机不继续调整，返回前记录草稿作为比较基线。
        checkState(!game.isWindowActive(), "真实窗口未失焦");
        var preview = field(game.gui.screen(), "preview", CombatSettingsAccess.Preview.class);
        focusDraft = preview.draft();
        focus(game.getWindow().handle());
        return true;
    }

    private boolean focusChecked() throws Exception {
        // 重新聚焦后的 repeat 不能重启旧按住状态；显式释放后才能重新输入。
        checkState(game.isWindowActive());
        var preview = field(game.gui.screen(), "preview", CombatSettingsAccess.Preview.class);
        checkState(preview.draft().equals(focusDraft), "重新聚焦补放了编辑输入");
        key(GLFW.GLFW_KEY_A, GLFW.GLFW_REPEAT);
        checkState(preview.draft().equals(focusDraft));
        key(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE);
        GLFW.glfwDestroyWindow(focusWindow);
        focusWindow = 0;
        return true;
    }

    private boolean saveFailure() throws Exception {
        // 仅在隔离测试目录把配置路径临时占为目录，触发真实文件替换失败。
        savedConfig = configPath().resolveSibling("stance-test-saved-config.json");
        Files.move(configPath(), savedConfig, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectory(configPath());
        Files.writeString(configPath().resolve("keep"), "force non-empty directory");
        click(previewRoot().childById(UIComponent.class, "camera-save"));
        return true;
    }

    private boolean failureRetained() throws Exception {
        // 保存失败不关闭编辑器，恢复测试文件后同一草稿可重试。
        checkState(game.gui.screen() instanceof CombatCameraScreen, "保存失败关闭了编辑器");
        screenshot("preview-save-failed");
        var failedDirectory = configPath().resolveSibling("stance-test-failure-directory");
        Files.move(configPath(), failedDirectory.resolveSibling("stance-failure-" + System.nanoTime()));
        Files.move(savedConfig, configPath());
        click(previewRoot().childById(UIComponent.class, "camera-save"));
        return true;
    }

    private boolean cameraInvalidated() {
        // 保存重试已返回同页，切换相机实体时预览必须丢弃而非恢复战斗会话。
        checkState(game.gui.screen() instanceof WorkspaceScreen);
        checkState(settingsRoot == settingsRoot());
        click(child("combat-preview"));
        var other = game.level.entitiesForRendering().iterator();
        while (other.hasNext()) {
            var entity = other.next();
            if (entity != game.player) {
                game.setCameraEntity(entity);
                return true;
            }
        }
        throw new IllegalStateException("缺少测试相机实体");
    }

    private boolean invalidatedChecked() {
        // 失效后覆盖令牌已释放，仍显示的父页不能重新激活旧预览。
        checkState(!(game.gui.screen() instanceof CombatCameraScreen));
        checkState(CombatViewController.forShoulder(game.player) == null);
        game.setCameraEntity(game.player);
        game.gui.setScreen(null);
        return true;
    }

    private boolean finishChecks() throws Exception {
        // 退出战斗恢复最初的原版正面第三人称，截图写盘完成才报告成功。
        if (pendingCaptures != 0) {
            return false;
        }
        if (controller() != null) {
            tap(GLFW.GLFW_KEY_R);
            return false;
        }
        checkState(game.options.getCameraType() == CameraType.THIRD_PERSON_FRONT);
        persistedView = CombatSettingsAccess.snapshot().view();
        MeridianSettings.open(CombatSettingsProvider.ID);
        settingsRoot = settingsRoot();
        settingsRoot.childById(TextBoxComponent.class, "combat-radius").text("27");
        game.disconnectWithSavingScreen();
        reconnectPending = true;
        stageTick = ticks;
        return false;
    }

    private void reconnect() throws Exception {
        // 真实断线后确认没有残留窗口，再重进同一存档检查已保存偏好和草稿丢弃。
        if (!rejoinStarted) {
            if (ticks - stageTick < STEP_TICKS || game.getSingleplayerServer() != null) {
                return;
            }
            checkState(MeridianSettings.open().status() == MeridianSettings.Status.UNAVAILABLE);
            checkState(InventoryUiClient.runtime().workspace().windows().snapshot().isEmpty());
            game.createWorldOpenFlows().openWorld("stance-settings-test", () -> { });
            rejoinStarted = true;
            return;
        }
        if (game.player == null || game.gui.overlay() != null) {
            return;
        }
        checkState(CombatSettingsAccess.snapshot().view().equals(persistedView));
        MeridianSettings.open(CombatSettingsProvider.ID);
        var radius = settingsRoot().childById(TextBoxComponent.class, "combat-radius");
        checkState(!radius.getValue().equals("27"), "断线保留了未保存草稿");
        evidence.add("disconnect/rejoin/persisted-camera/discard-draft PASS");
        finish(null);
    }

    private ParentUIComponent settingsRoot() {
        // 只按固定窗口类型定位，不把 pageId 用作 objectId。
        var windows = InventoryUiClient.runtime().workspace().windows().snapshot();
        var selected = windows.stream()
                .filter(window -> window.key().windowType().equals("meridian_ui:settings_window"))
                .toList();
        checkState(selected.size() == 1, "设置窗口不是唯一实例");
        return SettingsWindowProbe.content(selected.getFirst().key());
    }

    private UIComponent child(String id) {
        // 使用实际挂载的页面组件，不构造测试 UI 替身。
        return settingsRoot.childById(UIComponent.class, id);
    }

    private ParentUIComponent previewRoot() throws Exception {
        // 父类受保护适配器只由测试反射读取，正式 API 不暴露组件内部。
        var screen = game.gui.screen();
        var adapterField = io.wispforest.owo.ui.base.BaseOwoScreen.class.getDeclaredField("uiAdapter");
        adapterField.setAccessible(true /* accessible */);
        var adapter = (io.wispforest.owo.ui.core.OwoUIAdapter<?>) adapterField.get(screen);
        return adapter.rootComponent;
    }

    private CombatViewController controller() {
        // 复用正式有效性检查，架势外或换相机实体时返回空。
        return CombatViewController.forShoulder(game.player);
    }

    private static Path configPath() {
        // 隔离测试运行目录，绝不触碰玩家实际安装配置。
        return FabricLoader.getInstance().getConfigDir().resolve("kizuna-meridian-combat.json");
    }

    private void screenshot(String name) {
        // 保存真实 framebuffer；异步回读完成前测试不会退出。
        pendingCaptures++;
        Screenshot.takeScreenshot(game.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(SCREENS.resolve(name + ".png"));
                evidence.add("screenshot=" + name);
                pendingCaptures--;
            } catch (Exception failure) {
                finish(failure.toString());
            }
        });
    }

    private void describeCamera() {
        // 记录实际相机与玩家坐标，区分场景夹具问题和相机适配问题。
        var camera = game.gameRenderer.mainCamera();
        String state = "player=" + game.player.position() + " camera=" + camera.position()
                + " cameraAngles=" + camera.yRot() + "," + camera.xRot()
                + " session=" + controller().view().camera() + " entities=" + game.level.getEntityCount();
        evidence.add(state);
        System.out.println("STANCE TEST " + state);
    }

    private void finish(String failure) {
        // 每次结果明确写入实际通过的阶段，不把启动成功当作验收完成。
        finished = true;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\n" + String.join("\n", evidence)
                    + "\nerror=" + failure + "\n");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        game.stop();
    }

    @FunctionalInterface
    private interface Step {
        boolean run() throws Exception;
    }
}
