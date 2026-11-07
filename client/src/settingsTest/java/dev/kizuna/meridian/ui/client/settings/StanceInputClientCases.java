package dev.kizuna.meridian.ui.client.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.core.CombatStanceSettings;
import dev.kizuna.meridian.combat.core.CombatCameraEdit;
import dev.kizuna.meridian.combat.core.CombatViewSettings;
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
import java.util.function.Supplier;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import static com.google.common.base.Preconditions.checkState;
import static dev.kizuna.meridian.ui.client.settings.SettingsTestInput.*;

/** 真实输入分发验证长按、模式隔离、原版重绑定和跨文件部分保存。 */
final class StanceInputClientCases {
    private static final float LOOK_DELTA = 30;
    private static final double POSITION_EPSILON = 0.001;
    private static final double PARTIAL_SAVE_RADIUS = 41;
    private static final int LOW_FRAME_LIMIT = 20;
    private static final int HIGH_FRAME_LIMIT = 120;
    private static final long SAMPLE_NANOS = 1_200_000_000L;
    private static final double NANOS_PER_SECOND = 1_000_000_000;
    private static final double SPEED_TOLERANCE = 0.25;
    private final Minecraft game = Minecraft.getInstance();
    private final Supplier<ParentUIComponent> page;
    private final Consumer<String> evidence;
    private InputConstants.Key originalForward;
    private CombatViewSettings geometry;
    private Vec3 position;
    private Path savedOptions;
    private int originalFrameLimit;
    private long sampleStarted;
    private double sampleOffset;

    StanceInputClientCases(Supplier<ParentUIComponent> page, Consumer<String> evidence) {
        // 组件来自实际窗口，测试不另建页面或直接调用控件回调。
        this.page = page;
        this.evidence = evidence;
    }

    boolean lockLongPress() throws Exception {
        // 按住锁键只执行一次，后续系统 repeat 不能反复写配置。
        checkState(controller() != null);
        CombatSettingsAccess.save(new CombatStanceSettings(CombatStanceSettings.ViewLock.ON,
                CombatStanceSettings.DEFAULT_RADIUS));
        key(GLFW.GLFW_KEY_F7, GLFW.GLFW_PRESS);
        return true;
    }

    boolean lockRepeated() throws Exception {
        // 下一客户端 tick 已消费首次按下，再投递真实键盘 repeat。
        checkState(!controller().view().locked());
        key(GLFW.GLFW_KEY_F7, GLFW.GLFW_REPEAT);
        return true;
    }

    boolean unlockLook() throws Exception {
        // 解锁后鼠标只绕看，真实人物的水平和俯仰不再跟随。
        var controller = controller();
        checkState(!controller.view().locked());
        var before = controller.view().camera();
        float yaw = game.player.getYRot();
        float pitch = game.player.getXRot();
        mouseLook(LOOK_DELTA, LOOK_DELTA);
        checkState(!controller.view().camera().equals(before));
        checkState(game.player.getYRot() == yaw && game.player.getXRot() == pitch);
        evidence.accept("view-lock-off/independent-mouse-orbit PASS");
        key(GLFW.GLFW_KEY_F7, GLFW.GLFW_RELEASE);
        tap(GLFW.GLFW_KEY_F7);
        return true;
    }

    boolean firstPerson() throws Exception {
        // Combat 内 F5 不切入原版第三种视角，随后用 V 验证第一人称隔离。
        checkState(controller().view().locked());
        tap(GLFW.GLFW_KEY_F5);
        return true;
    }

    boolean firstPersonCheck() throws Exception {
        // F5 已经过原版 tick，越肩仍有效。
        checkState(controller() != null);
        tap(GLFW.GLFW_KEY_V);
        return true;
    }

    boolean returnShoulder() throws Exception {
        // 第一人称不响应新增快捷键，也没有有效的越肩联动。
        checkState(controller() == null);
        checkState(game.options.getCameraType() == CameraType.FIRST_PERSON);
        tap(GLFW.GLFW_KEY_F7);
        tap(GLFW.GLFW_KEY_F8);
        return true;
    }

    boolean rebindPage() throws Exception {
        // 先检查第一人称按键未修改偏好，再切回越肩。
        checkState(CombatSettingsAccess.snapshot().stance().viewLock() == CombatStanceSettings.ViewLock.ON);
        tap(GLFW.GLFW_KEY_V);
        return true;
    }

    boolean rebindPreview() {
        // 将原版前进改为 I，预览必须实时读取同一 KeyMapping。
        checkState(controller() != null);
        originalForward = InputConstants.getKey(game.options.keyUp.saveString());
        game.options.keyUp.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_I));
        KeyMapping.resetMapping();
        MeridianSettings.open(CombatSettingsProvider.ID);
        return true;
    }

    boolean oldKeyIgnored() throws Exception {
        // 屏幕有焦点的文本框不让移动键泄漏，预览的旧 W 也不再表示前进。
        var radius = page.get().childById(TextBoxComponent.class, "combat-radius");
        click(radius);
        position = game.player.position();
        tap(GLFW.GLFW_KEY_I);
        geometry = CombatSettingsAccess.snapshot().view();
        click(child("combat-preview"));
        checkState(game.gui.screen() instanceof CombatCameraScreen);
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
        return true;
    }

    boolean newKeyMoves() throws Exception {
        // 旧键不会调整距离，新的前进键开始连续调整。
        checkState(preview().draft().equals(geometry));
        checkState(game.player.position().distanceTo(position) < POSITION_EPSILON);
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
        key(GLFW.GLFW_KEY_I, GLFW.GLFW_PRESS);
        return true;
    }

    boolean previewEscCleanup() throws Exception {
        // 重绑定输入只改变相机草稿，Esc 清理按住状态且不保存。
        checkState(preview().draft().distance() < geometry.distance());
        checkState(game.player.position().distanceTo(position) < POSITION_EPSILON);
        tap(GLFW.GLFW_KEY_ESCAPE);
        key(GLFW.GLFW_KEY_I, GLFW.GLFW_RELEASE);
        checkState(CombatSettingsAccess.snapshot().view().equals(geometry));
        checkState(!game.options.keyUp.isDown());
        game.options.keyUp.setKey(originalForward);
        KeyMapping.resetMapping();
        return true;
    }

    boolean partialSavePage() throws Exception {
        // 构造有效 Combat 偏好和键位草稿，分别检查两个文件的提交结果。
        page.get().childById(TextBoxComponent.class, "combat-radius").text(Double.toString(PARTIAL_SAVE_RADIUS));
        click(child("combat-key-FACE_TARGET"));
        tap(GLFW.GLFW_KEY_F9);
        return true;
    }

    boolean frameLowStart() throws Exception {
        // 同一真实编辑器分别以两种帧率上限运行，测量单位墙钟时间的位移。
        originalFrameLimit = game.options.framerateLimit().get();
        click(child("combat-preview"));
        beginSpeedSample(LOW_FRAME_LIMIT);
        return true;
    }

    boolean frameLowChecked() throws Exception {
        // 采样跨越完整一秒，FPS 统计才有机会反映当前限制。
        if (System.nanoTime() - sampleStarted < SAMPLE_NANOS) {
            return false;
        }
        checkSpeed(LOW_FRAME_LIMIT);
        beginSpeedSample(HIGH_FRAME_LIMIT);
        return true;
    }

    boolean frameHighChecked() throws Exception {
        // 高帧率也必须遵守同一速度，恢复设置后取消草稿。
        if (System.nanoTime() - sampleStarted < SAMPLE_NANOS) {
            return false;
        }
        checkSpeed(HIGH_FRAME_LIMIT);
        game.options.framerateLimit().set(originalFrameLimit);
        tap(GLFW.GLFW_KEY_ESCAPE);
        return true;
    }

    private void beginSpeedSample(int frameLimit) throws Exception {
        // 每个采样从默认几何出发，避免范围边界截断位移。
        game.options.framerateLimit().set(frameLimit);
        var preview = preview();
        preview.reset();
        sampleOffset = preview.draft().shoulderOffset();
        sampleStarted = System.nanoTime();
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_PRESS);
    }

    private void checkSpeed(int frameLimit) throws Exception {
        // 容忍一个渲染帧的采样边界误差，直接检查屏幕实际推进的位移速度。
        key(GLFW.GLFW_KEY_D, GLFW.GLFW_RELEASE);
        double seconds = (System.nanoTime() - sampleStarted) / NANOS_PER_SECOND;
        double speed = (preview().draft().shoulderOffset() - sampleOffset) / seconds;
        checkState(Math.abs(speed - CombatCameraEdit.BLOCKS_PER_SECOND) < SPEED_TOLERANCE,
                "实际编辑速度与时间不一致：%s", speed);
        evidence.accept("preview-speed frameLimit=" + frameLimit + " fps=" + game.getFps()
                + " blocksPerSecond=" + speed + " PASS");
    }

    boolean partialSaveStart() throws Exception {
        // 只阻断隔离目录的 options 文件，Combat 配置应仍能明确保存。
        var path = optionsPath();
        savedOptions = path.resolveSibling("stance-test-saved-options.txt");
        Files.move(path, savedOptions, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep"), "force options write failure");
        click(child("combat-save"));
        return true;
    }

    boolean partialSaveChecked() throws Exception {
        // 一个文件失败不能撤回已保存的偏好，也不能发布未落盘的新映射。
        checkState(CombatSettingsAccess.snapshot().stance().targetRadius() == PARTIAL_SAVE_RADIUS);
        checkState(CombatSettingsAccess.keyMapping(CombatSettingsAccess.Action.FACE_TARGET)
                .matches(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8)));
        var path = optionsPath();
        Files.move(path, path.resolveSibling("stance-options-failure-" + System.nanoTime()));
        Files.move(savedOptions, path);
        click(child("combat-save"));
        return true;
    }

    boolean partialSaveRetried() {
        // 同一草稿重试后新键位落盘，取消父页不回滚已成功保存的半径。
        checkState(CombatSettingsAccess.keyMapping(CombatSettingsAccess.Action.FACE_TARGET)
                .matches(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9)));
        click(child("combat-cancel"));
        checkState(CombatSettingsAccess.snapshot().stance().targetRadius() == PARTIAL_SAVE_RADIUS);
        game.gui.setScreen(null);
        return true;
    }

    private UIComponent child(String id) {
        // 按稳定 ID 定位真实挂载组件，保留工作区焦点分发路径。
        return page.get().childById(UIComponent.class, id);
    }

    private CombatSettingsAccess.Preview preview() throws Exception {
        // 只读屏幕令牌验证草稿，不向生产接口添加测试专属入口。
        return field(game.gui.screen(), "preview", CombatSettingsAccess.Preview.class);
    }

    private CombatViewController controller() {
        // 用正式有效性判断区分越肩与其他模式。
        return CombatViewController.forShoulder(game.player);
    }

    private static Path optionsPath() {
        // 所有失败注入限定于 Gradle 客户端验收的独立运行目录。
        return FabricLoader.getInstance().getGameDir().resolve("options.txt");
    }
}
