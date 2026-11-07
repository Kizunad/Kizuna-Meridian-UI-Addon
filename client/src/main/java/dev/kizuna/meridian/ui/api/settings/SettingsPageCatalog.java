package dev.kizuna.meridian.ui.api.settings;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** 一次注册、稳定排序的设置页目录，不在渲染期间发现或反射模块。 */
public final class SettingsPageCatalog {
    private final Map<Identifier, SettingsPageProvider> providers = new LinkedHashMap<>();
    private List<SettingsPageProvider> ordered;

    public void register(SettingsPageProvider provider) {
        // 重复身份和不完整接线在启动阶段失败，不能静默覆盖另一模块。
        if (ordered != null) {
            throw new IllegalStateException("设置页目录已冻结");
        }
        Objects.requireNonNull(provider.title(), "设置页缺少标题");
        var id = Objects.requireNonNull(provider.id(), "设置页缺少 ID");
        if (providers.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("重复设置页：" + id);
        }
    }

    public void freeze() {
        // 相同排序值用稳定 ID 决胜，安装顺序不改变导航顺序。
        if (ordered != null || providers.isEmpty()) {
            throw new IllegalStateException("设置页目录必须非空且只冻结一次");
        }
        ordered = providers.values().stream()
                .sorted(Comparator.comparingInt(SettingsPageProvider::order)
                        .thenComparing(provider -> provider.id().toString()))
                .toList();
    }

    public List<SettingsPageProvider> pages() {
        // 冻结前禁止消费目录，避免初始化时只看到部分提供者。
        return Objects.requireNonNull(ordered, "设置页目录尚未冻结");
    }

    public SettingsPageProvider find(Identifier id) {
        // 未知 ID 保留为空，由公开打开接口给出明确结果。
        return providers.get(id);
    }
}
