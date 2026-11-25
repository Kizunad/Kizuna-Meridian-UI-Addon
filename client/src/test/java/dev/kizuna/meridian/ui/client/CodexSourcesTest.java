package dev.kizuna.meridian.ui.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CodexSourcesTest {
    private final Language previous = Language.getInstance();

    @After
    public void restoreLanguage() {
        // 游戏语言是全局状态，断言失败时也要恢复。
        Language.inject(previous);
    }

    @Test
    public void chineseHintsSubstituteNamesRealmAndOrigin() {
        // 中文多名沿用顿号，境界与出身来自不同的来源字段。
        useLanguage("zh_cn");
        verifyHints(List.of("村民", "掠夺者"), "村民、掠夺者", "游炁", "农民");
    }

    @Test
    public void englishHintsSubstituteNamesRealmAndOriginWithCommaSeparatedNames() {
        // 英文名字之间保留逗号后的空格，经典窗口仍沿用原有连接符。
        useLanguage("en_us");
        var names = List.of("Villager", "Pillager");
        String realm = "Wandering Qi";
        verifyHints(names, "Villager, Pillager", realm, "Farmer");
        String classic = CodexSources.namedHint("codex.hint.everyone_common", names, realm, WindowText.CLASSIC);
        assertTrue(classic, classic.contains("Villager、Pillager"));
    }

    private static void verifyHints(List<String> names, String joined, String realm, String origin) {
        // 常见和稀有都读取发行资源；漏替换、吞掉参数或漏键都不能得到完整来源句。
        for (String suffix : List.of("_common", "_rare")) {
            for (String share : List.of("everyone", "some")) {
                String key = "codex.hint." + share + suffix;
                String named = CodexSources.namedHint(key, names, realm, WindowText.MODERN);
                assertResolved(named, joined, realm);
            }
            String wide = CodexSources.realmHint("codex.hint.wide" + suffix, realm);
            assertResolved(wide, realm);
        }
        assertResolved(CodexSources.originHint(origin), origin);
    }

    private static void assertResolved(String rendered, String... values) {
        // 既核对实参也核对模板边界，防止空替换误通过。
        assertFalse(rendered, rendered.contains("{"));
        assertFalse(rendered, rendered.contains("}"));
        for (String value : values) {
            assertTrue(rendered, rendered.contains(value));
        }
    }

    private static void useLanguage(String code) {
        // 从 Gradle 处理后的发行资源加载语言，避免资源包路径依赖测试工作目录。
        var location = new PackLocationInfo("meridian_ui", Component.empty(), PackSource.BUILT_IN, Optional.empty());
        Path resourcesRoot = Path.of(System.getProperty("meridian.ui.testResources"));
        var pack = new PathPackResources(location, resourcesRoot);
        try (var resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack))) {
            Language.inject(ClientLanguage.loadFrom(resources, List.of(code), false /* defaultRightToLeft */));
        }
    }
}
