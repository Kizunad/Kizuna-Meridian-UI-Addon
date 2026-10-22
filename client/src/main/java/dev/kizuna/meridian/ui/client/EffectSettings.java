package dev.kizuna.meridian.ui.client;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.io.IOException;

/** 全部内观窗口共享的本地显示偏好，永远不参与身体或呼吸计算。 */
public final class EffectSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("meridian-ui-effects.properties");
    private static boolean reduced;
    private static int particleLimit = 96;
    static {
        // 配置只读一次；非法值明确报错，不能悄悄应用另一个玩家不知情的上限。
        if (Files.exists(FILE)) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(FILE)) {
                properties.load(input);
                String motion = properties.getProperty("reduced_motion", "false");
                if (!motion.equals("true") && !motion.equals("false")) {
                    throw new IllegalArgumentException(UiErrors.INVALID_EFFECT_SETTINGS);
                }
                reduced = Boolean.parseBoolean(motion);
                particleLimit = Integer.parseInt(properties.getProperty("particle_limit", "96"));
                if (particleLimit < 0 || particleLimit > 384) {
                    throw new IllegalArgumentException(UiErrors.INVALID_EFFECT_SETTINGS);
                }
            } catch (IOException | NumberFormatException failure) {
                throw new IllegalStateException(UiErrors.INVALID_EFFECT_SETTINGS, failure);
            }
        }
    }

    private EffectSettings() {
        // 只允许通过统一入口保存偏好，三个视图不能持有互相矛盾的设置。
    }

    public static boolean reducedMotion() {
        // 每帧读取同一内存值，不做磁盘 IO。
        return reduced;
    }

    public static int particleLimit() {
        // 上限按每个三维画布合计，包含流光光点和环境粒子。
        return particleLimit;
    }

    public static void toggleMotion() {
        // 静态模式保留边界和颜色，关闭脉动、闪烁与装饰流光。
        reduced = !reduced;
        save();
    }

    public static void cycleLimit() {
        // 控件提供易比较的档位；文件还允许 0～384 内任意上限。
        particleLimit = particleLimit >= 192 ? 0 : particleLimit == 0 ? 24 : particleLimit < 96 ? 96 : 192;
        save();
    }

    private static void save() {
        // 偏好跨窗口与客户端重启保存，写入失败直接指出配置问题。
        Properties properties = new Properties();
        properties.setProperty("reduced_motion", Boolean.toString(reduced));
        properties.setProperty("particle_limit", Integer.toString(particleLimit));
        try {
            Files.createDirectories(FILE.getParent());
            try (var output = Files.newOutputStream(FILE)) {
                properties.store(output, "Meridian visual effects");
            }
        } catch (IOException failure) {
            throw new IllegalStateException(UiErrors.INVALID_EFFECT_SETTINGS, failure);
        }
    }
}
