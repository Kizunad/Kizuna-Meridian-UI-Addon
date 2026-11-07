package dev.kizuna.meridian.ui.api.settings;

import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/** 单次开页的组件及草稿；close 必须幂等且释放预览和输入占用。 */
public interface SettingsPageSession extends AutoCloseable {
    /** 返回单页组件树，宿主只负责挂载。 */
    UIComponent component();
    /** 未提交或非法草稿都须阻止静默切页。 */
    boolean dirty();
    /** 幂等丢弃草稿，并释放预览及输入占用。 */
    void close();

    default boolean captureKey(KeyEvent event) {
        // 录入页可在普通控件之前消费键盘，防止快捷键同时作用于游戏。
        return false;
    }

    default boolean ownsScreen(Screen screen) {
        // 临时预览屏幕保留页面会话，其他离开工作区的路径按取消处理。
        return false;
    }

    default void cancelInput() {
        // 普通页面没有持续输入；编辑器可在失焦时清除按住状态。
    }
}
