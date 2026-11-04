package dev.kizuna.meridian.ui.client;

import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.Method;

/** 真实工作台事件与原版键盘分发的测试驱动，不直接调用功法面板写入方法。 */
final class CombatSlotTestInput {
    private CombatSlotTestInput() {
        // 仅测试源码可以模拟用户事件。
    }

    static MouseButtonEvent point(UIComponent component) {
        // 点在实际挂载组件中心，不依赖窗口初始位置或 GUI scale。
        return mouse(component.x() + component.width() / 2.0,
                component.y() + component.height() / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    static MouseButtonEvent mouse(double x, double y, int button) {
        // 保留无修饰键的普通鼠标语义。
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    static void click(UIComponent component) {
        // 点击和释放都经过工作台，确认窗口焦点和 owo 捕获路径。
        var screen = Minecraft.getInstance().gui.screen();
        var event = point(component);
        screen.mouseClicked(event, false /* doubleClick */);
        screen.mouseReleased(event);
    }

    static void startDrag(UIComponent source, UIComponent target) {
        // 使用真实鼠标移动事件建立引用拖动，不触碰物品拖动管理器。
        var screen = Minecraft.getInstance().gui.screen();
        var start = point(source);
        var end = point(target);
        screen.mouseClicked(start, false /* doubleClick */);
        screen.mouseDragged(end, end.x() - start.x(), end.y() - start.y());
    }

    static void drag(UIComponent source, UIComponent target) {
        // 释放只交给工作台，由原按下窗口及聚焦组件决定是否提交。
        startDrag(source, target);
        Minecraft.getInstance().gui.screen().mouseReleased(point(target));
    }

    static void key(int key, int action) throws ReflectiveOperationException {
        // 调用原版私有分发器，覆盖 Screen 消费、KeyMapping 排队及关闭后的输入行为。
        var client = Minecraft.getInstance();
        Method method = net.minecraft.client.KeyboardHandler.class
                .getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
        method.setAccessible(true /* accessible */);
        method.invoke(client.keyboardHandler, client.getWindow().handle(), action, new KeyEvent(key, 0, 0));
    }
}
