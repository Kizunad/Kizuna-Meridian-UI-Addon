package dev.kizuna.meridian.ui.client.settings;

import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** 真实屏幕鼠标事件和原版键盘分发器，用于验证组件焦点及游戏输入隔离。 */
final class SettingsTestInput {
    private SettingsTestInput() {
        // 反射模拟只允许在测试源码集中使用。
    }

    static void click(UIComponent component) {
        // 点击屏幕上的实际组件中心，不直接调用按钮回调。
        clickAt(component.x() + component.width() / 2.0, component.y() + component.height() / 2.0);
    }

    static void clickNamed(String title) {
        // 工作区功能目录使用原版 Widget，仍通过屏幕坐标分发鼠标。
        var widget = Minecraft.getInstance().gui.screen().children().stream()
                .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                .filter(component -> component.getMessage().getString().equals(title))
                .findFirst().orElseThrow();
        clickAt(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
    }

    private static void clickAt(double x, double y) {
        // 同一按下和释放路径覆盖原版目录按钮及 owo 内容按钮。
        var screen = Minecraft.getInstance().gui.screen();
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false /* doubleClick */);
        screen.mouseReleased(event);
    }

    static void key(int key, int action) throws ReflectiveOperationException {
        // 原版分发包括 Screen 消费与 KeyMapping 排队，避免绕开被验收的输入路径。
        var client = Minecraft.getInstance();
        var method = net.minecraft.client.KeyboardHandler.class
                .getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
        method.setAccessible(true /* accessible */);
        method.invoke(client.keyboardHandler, client.getWindow().handle(), action, new KeyEvent(key, 0, 0));
    }

    static void tap(int key) throws ReflectiveOperationException {
        // 同一 tick 的按下和松开仍必须保留一次离散操作。
        key(key, GLFW.GLFW_PRESS);
        key(key, GLFW.GLFW_RELEASE);
    }

    static void mouseLook(double deltaX, double deltaY) throws ReflectiveOperationException {
        // 经过原版鼠标累积、灵敏度和 turnPlayer 的 Mixin 路径，避免直接调用业务控制器。
        var client = Minecraft.getInstance();
        var mouse = client.mouseHandler;
        long window = client.getWindow().handle();
        double startX = mouse.xpos();
        double startY = mouse.ypos();
        var method = net.minecraft.client.MouseHandler.class
                .getDeclaredMethod("onMove", long.class, double.class, double.class);
        method.setAccessible(true /* accessible */);
        method.invoke(mouse, window, startX, startY);
        method.invoke(mouse, window, startX + deltaX, startY + deltaY);
        mouse.handleAccumulatedMovement();
    }

    static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
        // 只读验证内部会话状态，不在生产 API 增加验收用可变对象。
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true /* accessible */);
        return type.cast(field.get(owner));
    }

    static void focus(long window) throws Exception {
        // 无窗口管理器的 Xvfb 直接切换 X11 输入焦点，仍由真实 GLFW 事件通知游戏。
        long nativeWindow = org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(window);
        String script = "import ctypes,sys; x=ctypes.CDLL('libX11.so.6'); "
                + "x.XOpenDisplay.restype=ctypes.c_void_p; d=x.XOpenDisplay(None); "
                + "x.XMapWindow.argtypes=[ctypes.c_void_p,ctypes.c_ulong]; x.XMapWindow(d,int(sys.argv[1])); "
                + "x.XSync.argtypes=[ctypes.c_void_p,ctypes.c_int]; x.XSync(d,0); "
                + "x.XSetInputFocus.argtypes=[ctypes.c_void_p,ctypes.c_ulong,ctypes.c_int,ctypes.c_ulong]; "
                + "x.XSetInputFocus(d,int(sys.argv[1]),1,0); "
                + "x.XSync.argtypes=[ctypes.c_void_p,ctypes.c_int]; x.XSync(d,0)";
        var process = new ProcessBuilder("python3", "-c", script, Long.toString(nativeWindow)).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("X11 焦点切换失败");
        }
    }
}
