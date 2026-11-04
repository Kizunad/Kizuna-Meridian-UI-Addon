package dev.kizuna.meridian.ui.client;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

/** 窗口引用交互的失效、取消与长按边界，不需要启动渲染器。 */
public final class CombatBookSelectionTest {
    private static final String FIRST = "test:first";
    private static final String SECOND = "test:second";

    @Test
    public void numbersBindSelectionOnceUntilReleased() {
        // 无选择不写入；主键盘两端和第十槽映射都必须正确。
        var selection = new CombatBookSelection();
        selection.update(Set.of(FIRST));
        assertNull(selection.press('1'));
        selection.release('1');
        selection.select(FIRST);
        for (int key : new int[]{'1', '9', '0'}) {
            var binding = selection.press(key);
            int expected = key - '0';
            if (key == '0') {
                expected = 10;
            }
            assertEquals(expected, binding.number());
            assertEquals(FIRST, binding.techniqueId());
            assertNull(selection.press(key));
            selection.release(key);
            assertNotNull(selection.press(key));
            selection.release(key);
        }
    }

    @Test
    public void cancelledDragCannotRestartUntilAnotherMousePress() {
        // Escape、失焦及外部松开共享取消语义，后续移动不能偷偷恢复一次拖动。
        var selection = new CombatBookSelection();
        selection.update(Set.of(FIRST));
        selection.select(FIRST);
        selection.pressSource(FIRST);
        selection.beginDrag(FIRST);
        assertEquals(FIRST, selection.dragging());
        selection.cancelDrag();
        selection.beginDrag(FIRST);
        assertEquals("", selection.takeDrag());
        assertEquals(FIRST, selection.selected());
        selection.pressSource(FIRST);
        selection.beginDrag(FIRST);
        assertEquals(FIRST, selection.takeDrag());
        assertEquals("", selection.takeDrag());
    }

    @Test
    public void authoritativeRefreshInvalidatesSelectionAndPendingDrop() {
        // 已学被移除时既不能按键绑定，也不能提交旧的拖动引用。
        var selection = new CombatBookSelection();
        selection.update(Set.of(FIRST, SECOND));
        selection.select(FIRST);
        selection.pressSource(FIRST);
        selection.beginDrag(FIRST);
        selection.update(Set.of(SECOND));
        assertEquals("", selection.selected());
        assertEquals("", selection.takeDrag());
        assertNull(selection.press('9'));
        selection.release('9');
        selection.select(FIRST);
        assertEquals("", selection.selected());
        selection.select(SECOND);
        assertEquals(SECOND, selection.press('9').techniqueId());
        selection.update(Set.of());
        assertEquals("", selection.selected());
    }
}
