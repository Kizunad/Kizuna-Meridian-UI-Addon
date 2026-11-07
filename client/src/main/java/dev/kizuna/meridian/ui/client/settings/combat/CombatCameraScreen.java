package dev.kizuna.meridian.ui.client.settings.combat;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.combat.core.CombatCameraEdit;
import dev.kizuna.meridian.combat.fabric.client.CombatSettingsAccess;
import dev.kizuna.meridian.ui.client.settings.SettingsWidgets;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 透明世界编辑屏幕，鼠标自由可见；只把移动键转换为相机草稿输入。 */
public final class CombatCameraScreen extends BaseOwoScreen<FlowLayout> {
    private final ClientRuntime runtime;
    private final CombatSettingsAccess.Preview preview;
    private final Consumer<String> returned;
    private final Map<Integer, KeyEvent> held = new HashMap<>();
    private final Set<Integer> blocked = new HashSet<>();
    private LabelComponent values;
    private LabelComponent feedback;
    private boolean finished;
    private String saveFailure = "";

    CombatCameraScreen(ClientRuntime runtime, CombatSettingsAccess.Preview preview, Consumer<String> returned) {
        // 相机与配置服务属于 Combat，屏幕只管理输入事件和返回路径。
        super(Component.literal("调整第三人称视角"));
        this.runtime = runtime;
        this.preview = preview;
        this.returned = returned;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        // 整屏透明，仅左上角参数面板覆盖真实游戏世界。
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        // XML 面板使用框架主题，按钮始终可直接点击。
        var panel = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "settings"))
                .expandTemplate(FlowLayout.class, "camera-preview", Map.of());
        panel.surface(Surface.flat(runtime.color(ThemeTokens.PANEL)));
        panel.child(SettingsWidgets.label(runtime, "移动键：靠近/远离、左右平移"));
        panel.child(SettingsWidgets.label(runtime, "跳跃/潜行键：上移/下移；Esc 取消"));
        values = SettingsWidgets.label(runtime, "");
        feedback = SettingsWidgets.label(runtime, "");
        panel.child(values);
        panel.child(feedback);
        panel.child(SettingsWidgets.button(runtime, "恢复默认", preview::reset).id("camera-defaults"));
        panel.child(SettingsWidgets.button(runtime, "保存", this::save).id("camera-save"));
        panel.child(SettingsWidgets.button(runtime, "取消", this::onClose).id("camera-cancel"));
        root.child(panel);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 时间来自单调时钟；正式 CameraMixin 渲染同一草稿和碰撞路径。
        if (!minecraft.isWindowActive()) {
            clearHeld();
        }
        preview.step(axes(), System.nanoTime());
        var draft = preview.draft();
        values.text(Component.literal(String.format(Locale.ROOT, "横向 %.2f　高度 %.2f　距离 %.2f",
                draft.shoulderOffset(), draft.heightOffset(), draft.distance())));
        if (!saveFailure.isEmpty()) {
            feedback.text(Component.literal(saveFailure));
        } else if (preview.clipped()) {
            feedback.text(Component.literal("碰撞：相机临时拉近，保存期望距离"));
        } else {
            feedback.text(Component.empty());
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 不绘制原版暗幕和模糊，保留真实人物、装备与相机画面。
    }

    private CombatCameraEdit.Axes axes() {
        // 跟随原版重绑定，相反方向抵消；归一化和速度只在 Combat core 定义。
        var options = minecraft.options;
        return new CombatCameraEdit.Axes(axis(options.keyRight, options.keyLeft),
                axis(options.keyJump, options.keyShift), axis(options.keyDown, options.keyUp));
    }

    private int axis(KeyMapping positive, KeyMapping negative) {
        // 只读本屏幕接到的新按下事件，不轮询原版移动状态。
        int value = 0;
        for (var event : held.values()) {
            if (positive.matches(event)) {
                value++;
            }
            if (negative.matches(event)) {
                value--;
            }
        }
        return value;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 所有游戏键都被屏幕消费，SPACE 不激活当前焦点按钮或驱动玩家跳跃。
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
        } else if (minecraft.isWindowActive() && !blocked.contains(key)) {
            held.put(key, event);
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        // 松开清除阻塞，失焦前按住的键需要重新按下才继续。
        int key = event.key();
        held.remove(key);
        blocked.remove(key);
        return true;
    }

    void clearHeld() {
        // Alt-Tab 不补放输入，重复事件也不能恢复旧长按。
        blocked.addAll(held.keySet());
        held.clear();
    }

    @Override
    public void tick() {
        // 先处理会话失效；重力、受击和服务端继续正常推进。
        if (!preview.active() && !finished) {
            finish("预览已失效，未保存相机草稿");
        } else if (!minecraft.isWindowActive()) {
            clearHeld();
        }
        CombatSettingsAccess.clearInput();
    }

    private void save() {
        // 保存失败保留编辑器和草稿，允许继续修改、重试或取消。
        var result = preview.save();
        String message = result.message();
        feedback.text(Component.literal(message));
        if (result.status() == CombatSettingsAccess.Status.SAVED) {
            finish("相机参数已保存");
        } else {
            saveFailure = message;
        }
    }

    @Override
    public void onClose() {
        // Esc 与取消按钮走相同返回路径，不丢父页其他草稿。
        finish("已取消相机修改");
    }

    private void finish(String result) {
        // 回调前结束覆盖，父页返回仍保持可见光标。
        if (!finished) {
            finished = true;
            preview.close();
            clearHeld();
            returned.accept(result);
        }
    }

    void abandon() {
        // 断线或父窗口关闭时只清理，不试图再打开设置窗口。
        finished = true;
        preview.close();
        clearHeld();
    }

    @Override
    public void removed() {
        // 被死亡界面等替换时不反向恢复屏幕，宿主随后按顶层关闭清理。
        abandon();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        // 编辑只屏蔽玩家输入，不冻结世界模拟。
        return false;
    }
}
