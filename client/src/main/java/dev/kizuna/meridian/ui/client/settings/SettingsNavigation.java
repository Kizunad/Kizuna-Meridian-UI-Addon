package dev.kizuna.meridian.ui.client.settings;

import dev.kizuna.meridian.ui.api.settings.SettingsPageSession;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/** 导航与草稿生命周期；程序打开和鼠标切页共用同一路径。 */
public final class SettingsNavigation implements AutoCloseable {
    public enum Change { SELECTED, UNCHANGED, NEEDS_DISCARD }
    private final Function<Identifier, SettingsPageSession> factory;
    private Identifier selected;
    private Identifier pending;
    private SettingsPageSession session;

    public SettingsNavigation(Function<Identifier, SettingsPageSession> factory) {
        // 页面工厂由已冻结目录提供，宿主不认识业务字段。
        this.factory = Objects.requireNonNull(factory);
    }

    public Change select(Identifier id) {
        // 同页聚焦保留草稿，脏页必须等待明确放弃。
        if (id.equals(selected)) {
            return Change.UNCHANGED;
        }
        if (session != null && session.dirty()) {
            pending = id;
            return Change.NEEDS_DISCARD;
        }
        replace(id);
        return Change.SELECTED;
    }

    public void discardAndSwitch() {
        // 确认后才释放旧页，预览和输入清理由单页关闭契约负责。
        var destination = Objects.requireNonNull(pending, "没有等待确认的切页");
        replace(destination);
    }

    public void keepEditing() {
        // 继续编辑不会触碰当前组件树与草稿。
        pending = null;
    }

    public SettingsPageSession session() {
        // 页面会话的可变状态不暴露给注册目录。
        return session;
    }

    private void replace(Identifier id) {
        // 先创建新页，工厂失败时保留旧页供用户继续编辑。
        SettingsPageSession created = Objects.requireNonNull(factory.apply(id), "设置页工厂返回空会话");
        close();
        session = created;
        selected = id;
        pending = null;
    }

    @Override
    public void close() {
        // 先移除引用再清理，避免重入关闭释放两次。
        var previous = session;
        session = null;
        selected = null;
        pending = null;
        if (previous != null) {
            previous.close();
        }
    }
}
