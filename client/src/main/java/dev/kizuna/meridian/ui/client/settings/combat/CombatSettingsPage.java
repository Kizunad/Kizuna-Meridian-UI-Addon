package dev.kizuna.meridian.ui.client.settings.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.core.CombatStanceSettings;
import dev.kizuna.meridian.combat.fabric.client.CombatSettingsAccess;
import dev.kizuna.meridian.ui.api.settings.SettingsPageContext;
import dev.kizuna.meridian.ui.api.settings.SettingsPageSession;
import dev.kizuna.meridian.ui.client.settings.SettingsWidgets;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 页面只维护草稿与交互；配置校验、相机和持久化全部交给 Combat。 */
public final class CombatSettingsPage implements SettingsPageSession {
    private final SettingsPageContext context;
    private final FlowLayout root;
    private final TextBoxComponent radius;
    private final LabelComponent feedback;
    private final ButtonComponent lockButton;
    private final Map<CombatSettingsAccess.Action, InputConstants.Key> keys =
            new EnumMap<>(CombatSettingsAccess.Action.class);
    private final Map<CombatSettingsAccess.Action, InputConstants.Key> keyBaseline =
            new EnumMap<>(CombatSettingsAccess.Action.class);
    private final Map<CombatSettingsAccess.Action, ButtonComponent> keyButtons =
            new EnumMap<>(CombatSettingsAccess.Action.class);
    private CombatStanceSettings baseline;
    private CombatStanceSettings.ViewLock lock;
    private CombatSettingsAccess.Action recording;
    private CombatCameraScreen preview;
    private boolean closed;

    public CombatSettingsPage(SettingsPageContext context) {
        // 页面构造时复制不可变快照，任何控件变化都不直接写正式配置。
        this.context = context;
        var runtime = context.runtime();
        var snapshot = CombatSettingsAccess.snapshot();
        baseline = snapshot.stance();
        lock = baseline.viewLock();
        root = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "settings"))
                .expandTemplate(FlowLayout.class, "combat-settings", Map.of());
        root.child(SettingsWidgets.label(runtime, "Combat 姿态设置"));
        lockButton = button("combat-lock", "", this::toggleLock);
        refreshLock();
        addKey(CombatSettingsAccess.Action.LOCK_VIEW);
        addPreviewButton();
        root.child(SettingsWidgets.label(runtime, "Auto Face Target"));
        addKey(CombatSettingsAccess.Action.FACE_TARGET);
        root.child(SettingsWidgets.label(runtime, "自动解除距离（1–128 格）"));
        radius = UIComponents.textBox(Sizing.fill(SettingsWidgets.FULL), Double.toString(baseline.targetRadius()));
        radius.id("combat-radius");
        root.child(radius);
        feedback = SettingsWidgets.label(runtime, "");
        feedback.horizontalSizing(Sizing.fill(SettingsWidgets.FULL));
        root.child(feedback);
        button("combat-save", "保存", this::save);
        button("combat-cancel", "取消", context.closeWindow());
        if (!snapshot.writable()) {
            message("原配置已保护，当前不可持久化；保存不会覆盖原件");
        }
    }

    private ButtonComponent button(String id, String title, Runnable action) {
        // 稳定组件 ID 也供键盘导航和真实客户端验收定位。
        var button = SettingsWidgets.button(context.runtime(), title, action);
        button.id(id);
        root.child(button);
        return button;
    }

    private void toggleLock() {
        // 开关只修改草稿，保存后才改变人物与镜头联动偏好。
        if (lock == CombatStanceSettings.ViewLock.OFF) {
            lock = CombatStanceSettings.ViewLock.ON;
        } else {
            lock = CombatStanceSettings.ViewLock.OFF;
        }
        refreshLock();
    }

    private void refreshLock() {
        // OFF/ON 对应持久化偏好，不表示当前有有效战斗会话。
        lockButton.setMessage(Component.literal("Lock current view — " + lock));
        lockButton.tooltip(Component.literal("开启：鼠标带动视角和人物朝向；关闭：独立绕看。目标锁定优先。"));
    }

    private void addKey(CombatSettingsAccess.Action action) {
        // 记录两项映射的独立基线，取消不覆盖任何原版选项。
        var runtime = context.runtime();
        String name = action.name();
        var key = InputConstants.getKey(CombatSettingsAccess.keyMapping(action).saveString());
        keys.put(action, key);
        keyBaseline.put(action, key);
        var row = UIContainers.horizontalFlow(Sizing.fill(SettingsWidgets.FULL), Sizing.content());
        row.gap(SettingsWidgets.GAP);
        var button = SettingsWidgets.button(runtime, "快捷键：" + key.getDisplayName().getString(), () -> {
            recording = action;
            message("按键录入中；Esc 取消，Backspace 清除");
            CombatSettingsAccess.clearInput();
        });
        button.id("combat-key-" + name);
        button.horizontalSizing(Sizing.expand());
        keyButtons.put(action, button);
        var clear = SettingsWidgets.button(runtime, "清除", () -> setKey(action, InputConstants.UNKNOWN));
        clear.id("combat-clear-" + name);
        clear.horizontalSizing(Sizing.content());
        row.child(button);
        row.child(clear);
        root.child(row);
    }

    private void addPreviewButton() {
        // 架势外只显示原因，不自动切换玩家模式。
        var runtime = context.runtime();
        String reason = CombatSettingsAccess.previewUnavailableReason();
        boolean available = reason.isEmpty();
        root.child(SettingsWidgets.label(runtime, "Change Thirdperson view"));
        var button = button("combat-preview", "调整视角", this::openPreview);
        button.active(available);
        if (!available) {
            root.child(SettingsWidgets.label(runtime, reason));
        }
    }

    private void openPreview() {
        // 同一父页会话保持存活，透明屏幕持有真实世界预览。
        try {
            var session = CombatSettingsAccess.beginPreview();
            preview = new CombatCameraScreen(context.runtime(), session, this::previewReturned);
            Minecraft.getInstance().gui.setScreen(preview);
        } catch (IllegalStateException failure) {
            message(failure.getMessage());
        }
    }

    private void previewReturned(String result) {
        // 相机已保存的几何不在父草稿中，之后取消父页也不会将其回滚。
        preview = null;
        message(result);
        if (!closed && Minecraft.getInstance().level != null) {
            context.runtime().openWorkspace();
        }
    }

    @Override
    public boolean captureKey(KeyEvent event) {
        // 录入先于窗口控件和游戏快捷键；Esc 只取消本次录入。
        if (recording == null) {
            return false;
        }
        var action = recording;
        recording = null;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            message("已取消快捷键录入");
        } else if (key == GLFW.GLFW_KEY_BACKSPACE) {
            setKey(action, InputConstants.UNKNOWN);
        } else {
            setKey(action, InputConstants.getKey(event));
        }
        CombatSettingsAccess.clearInput();
        return true;
    }

    private void setKey(CombatSettingsAccess.Action action, InputConstants.Key key) {
        // 冲突只提示，不在录入期间修改共享映射。
        recording = null;
        keys.put(action, key);
        keyButtons.get(action).setMessage(Component.literal("快捷键：" + key.getDisplayName().getString()));
        message(conflicts(action, key));
    }

    private String conflicts(CombatSettingsAccess.Action action, InputConstants.Key key) {
        // 同时检查本页草稿和当前原版控制表，避免第二份冲突键表。
        if (key.equals(InputConstants.UNKNOWN)) {
            return "快捷键未绑定";
        }
        for (var entry : keys.entrySet()) {
            if (entry.getKey() != action && entry.getValue().equals(key)) {
                return "键位冲突：另一项姿态快捷键";
            }
        }
        var own = CombatSettingsAccess.keyMapping(action);
        for (var mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping != own && mapping.matches(key)) {
                return "键位冲突：" + Component.translatable(mapping.getName()).getString();
            }
        }
        return "快捷键草稿已更新";
    }

    private void save() {
        // 先统一校验字段，再分别保存两个文件；成功项更新基线，失败项保留重试。
        try {
            var settings = new CombatStanceSettings(lock, Double.parseDouble(radius.getValue()));
            var configResult = CombatSettingsAccess.save(settings);
            if (configResult.status() == CombatSettingsAccess.Status.SAVED) {
                baseline = settings;
                radius.text(Double.toString(settings.targetRadius()));
            }
            String keysResult = saveKeys();
            message(configResult.message() + "；" + keysResult);
        } catch (IllegalArgumentException failure) {
            message("未保存：自动解除距离须为 1–128 的有限数值");
        }
    }

    private String saveKeys() {
        // 没有编辑的映射从当前原版读取，不覆盖控制页已经提交的修改。
        if (keys.equals(keyBaseline)) {
            return "快捷键未改动";
        }
        var first = keyToSave(CombatSettingsAccess.Action.LOCK_VIEW);
        var second = keyToSave(CombatSettingsAccess.Action.FACE_TARGET);
        var result = CombatSettingsAccess.saveKeys(new CombatSettingsAccess.Keys(first, second));
        if (result.status() == CombatSettingsAccess.Status.SAVED) {
            keyBaseline.putAll(keys);
        }
        return result.message();
    }

    private InputConstants.Key keyToSave(CombatSettingsAccess.Action action) {
        // 逐键判断草稿所有权，避免用全 options 快照回滚其他设置。
        var draft = keys.get(action);
        if (draft.equals(keyBaseline.get(action))) {
            return InputConstants.getKey(CombatSettingsAccess.keyMapping(action).saveString());
        }
        return draft;
    }

    private void message(String text) {
        // 页面内显示持久化与录入结果，失败时保留控件和草稿。
        feedback.text(Component.literal(text));
    }

    @Override
    public UIComponent component() {
        // 宿主只挂载整页，不读取其中业务字段。
        return root;
    }

    @Override
    public boolean dirty() {
        // 非法输入同样算未保存，切页不能静默丢掉。
        return lock != baseline.viewLock() || !radius.getValue().equals(Double.toString(baseline.targetRadius()))
                || !keys.equals(keyBaseline);
    }

    @Override
    public boolean ownsScreen(Screen screen) {
        // 只有当前页面发起的预览能延续本会话。
        return preview != null && preview == screen;
    }

    @Override
    public void cancelInput() {
        // 工作区在预览期间也会通知取消交互，仅窗口失焦才清掉预览输入。
        recording = null;
        if (preview != null && !Minecraft.getInstance().isWindowActive()) {
            preview.clearHeld();
        }
    }

    @Override
    public void close() {
        // 先标记关闭，预览清理回调不得再次打开已关闭的父窗口。
        if (!closed) {
            closed = true;
            recording = null;
            if (preview != null) {
                preview.abandon();
                preview = null;
            }
            CombatSettingsAccess.clearInput();
        }
    }
}
