package dev.kizuna.meridian.ui.client;

import java.util.HashSet;
import java.util.Set;

/** 选择与拖动只保存功法身份；按键锁存不依赖每帧时序，不持有物品或配置副本。 */
final class CombatBookSelection {
    record Binding(int number, String techniqueId) { }
    private static final int LAST_SLOT = 10;
    private Set<String> known = Set.of();
    private final Set<Integer> held = new HashSet<>();
    private String selected = "";
    private String dragging = "";
    private String pressedSource = "";

    void update(Set<String> ids) {
        // 已学刷新立即撤销失效选择，释放拖动时仍须经过 Combat 的再次校验。
        known = Set.copyOf(ids);
        if (!known.contains(selected)) {
            selected = "";
        }
        if (!known.contains(dragging)) {
            cancelDrag();
        }
    }

    void select(String id) {
        // 点击仅改变展示选择，不产生槽位写入。
        if (known.contains(id)) {
            selected = id;
        }
    }

    String selected() {
        // 选择不随 hover 或失焦改变。
        return selected;
    }

    void beginDrag(String id) {
        // 只允许来自当前已学列表的引用进入拖动。
        if (known.contains(id) && pressedSource.equals(id)) {
            dragging = id;
        }
    }

    void pressSource(String id) {
        // 只在实际按下时武装来源；Escape 取消后继续移动鼠标不能重新发起拖动。
        if (known.contains(id)) {
            pressedSource = id;
        }
    }

    String dragging() {
        // 空身份表示没有可提交的拖动。
        return dragging;
    }

    String takeDrag() {
        // 一次释放最多提交一次，即使落点或保存回调重入也不会重复写入。
        String id = dragging;
        cancelDrag();
        return id;
    }

    void cancelDrag() {
        // 取消保留选择及原配置，释放键由窗口路由独立清理。
        dragging = "";
        pressedSource = "";
    }

    Binding press(int key) {
        // 长按事件仅锁存首次，1–9 与 0 分别对应第 1–9 与第 10 槽。
        int number = slotNumber(key);
        if (number == 0 || !held.add(key) || selected.isEmpty()) {
            return null;
        }
        return new Binding(number, selected);
    }

    void release(int key) {
        // 非活动窗口也会收到释放，重新聚焦后可正常再次配置。
        held.remove(key);
    }

    void resetKeys() {
        // 新工作台会话不继承旧屏幕关闭期间未送达的释放事件。
        held.clear();
    }

    static int slotNumber(int key) {
        // GLFW 的主键盘数字键使用 ASCII；不劫持小键盘或修改键组合。
        if (key == '0') {
            return LAST_SLOT;
        }
        if (key >= '1' && key <= '9') {
            return key - '0';
        }
        return 0;
    }
}
