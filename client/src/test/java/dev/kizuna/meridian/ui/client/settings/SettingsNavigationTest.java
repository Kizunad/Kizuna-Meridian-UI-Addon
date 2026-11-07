package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.meridian.ui.api.settings.SettingsPageCatalog;
import dev.kizuna.meridian.ui.api.settings.SettingsPageContext;
import dev.kizuna.meridian.ui.api.settings.SettingsPageProvider;
import dev.kizuna.meridian.ui.api.settings.SettingsPageSession;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import static org.junit.Assert.*;

/** 测试专用提供者验证目录与导航无需认识 Combat 即可扩展。 */
public final class SettingsNavigationTest {
    private static final Identifier FIRST = Identifier.fromNamespaceAndPath("test", "first");
    private static final Identifier SECOND = Identifier.fromNamespaceAndPath("test", "second");

    @Test
    public void catalogRejectsDuplicatesAndFreezesStableOrder() {
        // 同优先级按 ID 排序，冻结后拒绝继续注册。
        var catalog = new SettingsPageCatalog();
        var first = new Provider(FIRST);
        var second = new Provider(SECOND);
        catalog.register(second);
        catalog.register(first);
        assertThrows(IllegalArgumentException.class, () -> catalog.register(first));
        catalog.freeze();
        assertEquals(List.of(first, second), catalog.pages());
        assertThrows(IllegalStateException.class, () -> catalog.register(second));
        assertNull(catalog.find(Identifier.fromNamespaceAndPath("test", "unknown")));
    }

    @Test
    public void repeatedOpenPreservesDraftAndConfirmedSwitchClosesOnce() {
        // 脏页拦截后可以继续编辑；确认放弃只关闭原会话一次。
        var created = new ArrayList<Session>();
        var navigation = new SettingsNavigation(id -> {
            var session = new Session();
            created.add(session);
            return session;
        });
        assertEquals(SettingsNavigation.Change.SELECTED, navigation.select(FIRST));
        Session first = created.getFirst();
        first.dirty = true;
        assertEquals(SettingsNavigation.Change.UNCHANGED, navigation.select(FIRST));
        assertEquals(SettingsNavigation.Change.NEEDS_DISCARD, navigation.select(SECOND));
        navigation.keepEditing();
        assertSame(first, navigation.session());
        assertEquals(0, first.closes);
        navigation.select(SECOND);
        navigation.discardAndSwitch();
        assertEquals(1, first.closes);
        assertEquals(2, created.size());
        navigation.close();
        navigation.close();
        assertEquals(1, created.getLast().closes);
    }

    private record Provider(Identifier id) implements SettingsPageProvider {
        @Override
        public Component title() {
            // 纯测试标题无需加载任何业务模块。
            return Component.literal(id.toString());
        }

        @Override
        public int order() {
            // 相同顺序测试稳定 ID 决胜规则。
            return 0;
        }

        @Override
        public SettingsPageSession create(SettingsPageContext context) {
            // 测试页只记录生命周期，不构造 Minecraft 控件。
            return new Session();
        }
    }

    private static final class Session implements SettingsPageSession {
        private boolean dirty;
        private int closes;

        @Override
        public UIComponent component() {
            // 导航测试不挂载组件树，渲染由真实客户端另行验证。
            return null;
        }

        @Override
        public boolean dirty() {
            // 测试显式改变草稿状态，检查确认路径。
            return dirty;
        }

        @Override
        public void close() {
            // 计数而非幂等掩盖错误，验证宿主确实只清理一次。
            closes++;
        }
    }
}
