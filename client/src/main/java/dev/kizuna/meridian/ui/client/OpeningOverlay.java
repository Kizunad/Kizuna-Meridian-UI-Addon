package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.ui.api.OpeningEffectProvider;
import dev.kizuna.meridian.ui.api.OpeningEffectProvider.Node;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;
import java.util.function.Function;

/** 同一权威任务的三维路线叠层；普通检视、站姿内观和打坐共用绘制。 */
final class OpeningOverlay {
    private static final OpeningFeedback FEEDBACK = new OpeningFeedback();
    private final OpeningEffectProvider provider;
    private final Map<String, BodyVisual> visuals = new LinkedHashMap<>();
    private final Map<String, Path> paths = new HashMap<>();
    private BodySnapshot body;
    private double holdMargin;

    OpeningOverlay(BodyScene scene) {
        // 解剖方向和骨架绑定必须由必装生物包提供，通用 UI 不猜人体左右。
        var providers = FabricLoader.getInstance().getEntrypoints(OpeningEffectProvider.ENTRYPOINT, OpeningEffectProvider.class);
        if (providers.size() != 1) {
            throw UiErrors.invalid(UiErrors.REQUIRED_OPENING_EFFECTS);
        }
        provider = providers.get(0);
        for (BodyVisual visual : scene.presentation().getVisualsList()) {
            if (visual.getTargetKind() == BodyTargetKind.MERIDIAN) {
                visuals.put(visual.getTargetId(), visual);
            }
        }
    }

    static void receive(BodySnapshot body) {
        // 连接入口只调用一次；多脉调试完成也合并为一个音效，避免叠音爆响。
        var events = FEEDBACK.receive(body, System.nanoTime());
        if (!events.isEmpty()) {
            boolean completed = events.stream().anyMatch(event -> event.outcome() == OpeningFeedback.Outcome.COMPLETED);
            String sound = completed ? "opening.complete" : "opening.interrupted";
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(
                    SoundEvent.of(new Identifier("meridian_ui", sound)), 1, .38f));
        }
    }

    static void clear() {
        // 连接结束撤销旧任务反馈，资源缓存随各画布一同释放。
        FEEDBACK.clear();
    }

    void update(BodySnapshot body) {
        // 只在快照到达时计算预警余量，不逐帧扫描身体账本或解释附属策略。
        this.body = body;
        holdMargin = OpeningFeedback.active(body.getOpeningTask()) ? provider.holdMargin(body) : 1;
    }

    float meshBrightness(String id, float baseline, long now) {
        // 完成反馈覆盖整条网格的主干和支路，随后稳定回到已开亮度。
        OpeningFeedback.Flash flash = FEEDBACK.flash(id, now);
        if (flash == null) {
            return baseline;
        }
        if (flash.outcome() == OpeningFeedback.Outcome.COMPLETED) {
            double age = (now - flash.atNanos()) / 1e9;
            return EffectSettings.reducedMotion() ? Math.max(.85f, baseline)
                    : Math.max(baseline, (float) (.8 + .8 * Math.exp(-age * 4)));
        }
        double remaining = Math.max(0, 1 - (now - flash.atNanos()) / 2_400_000_000.0);
        return EffectSettings.reducedMotion() ? .6f : baseline + (float) remaining * (.7f - baseline);
    }

    float restingBrightness(String id) {
        // 检视和经脉窗口使用相同开通底色，短暂结果反馈结束后仍能区分已开与未开。
        if (body == null) {
            return .18f;
        }
        boolean opened = body.getMeridianStatesList().stream().anyMatch(state -> state.getId().equals(id)
                && state.getOpening().getStage() == MeridianOpeningStage.OPENED);
        return opened ? .73f : .18f;
    }

    int meshColor(String id, int baseline, long now) {
        // 失败连同支路变成暗红，主路线另画退光；静态模式同样保留结果颜色。
        OpeningFeedback.Flash flash = FEEDBACK.flash(id, now);
        if (flash == null) {
            return baseline;
        }
        return flash.outcome() == OpeningFeedback.Outcome.COMPLETED ? 0xFFF4CE : 0xB84B52;
    }

    int draw(Matrix4f matrix, Function<String, Matrix4f> pose, Set<String> visible, long now,
             float unit, boolean showOpened) {
        // 普通检视尊重经脉层勾选；打坐在同一模型上使用动画骨矩阵，不另画站姿身体。
        if (body == null) {
            return 0;
        }
        boolean reduced = EffectSettings.reducedMotion();
        int particleBudget = reduced ? 0 : Math.min(16, EffectSettings.particleLimit());
        int emitted = 0;
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (MeridianState state : body.getMeridianStatesList()) {
            String id = state.getId();
            BodyVisual visual = visuals.get(id);
            if (visual == null || !BodyScene.realmAllows(body, visual) || (visible != null && !visible.contains(id))) {
                continue;
            }
            OpeningTask task = body.getOpeningTask();
            boolean active = OpeningFeedback.active(task) && task.getRule().getTargetMeridianId().equals(id);
            boolean opened = state.getOpening().getStage() == MeridianOpeningStage.OPENED;
            OpeningFeedback.Flash flash = FEEDBACK.flash(id, now);
            if (!active && flash == null && !(showOpened && opened)) {
                continue;
            }

            Path path = paths.computeIfAbsent(id, key -> new Path(provider.route(visual)));
            List<Vector3f> points = path.project(matrix, pose);
            double progress = opened ? 1 : state.getOpening().getProgress();
            double seconds = now / 1e9;
            double pulse = reduced || !active ? 1 : .78 + .22 * Math.sin(seconds * 3.8);
            int color = 0xFFE6A1;
            int alpha = (int) ((150 + 85 * progress) * pulse);
            boolean warning = active && holdMargin < .08;
            if (warning) {
                // 低余量同时以虚线和琥珀暗色提示，静态模式也不只靠闪烁表达风险。
                color = 0xD58A50;
                alpha = reduced ? 165 : (int) (85 + 70 * (.5 + .5 * Math.sin(seconds * 7)));
            }
            float width = unit * (1 + (float) progress * .45f);
            if (flash != null) {
                double age = (now - flash.atNanos()) / 1e9;
                if (flash.outcome() == OpeningFeedback.Outcome.COMPLETED) {
                    // 爆亮只由 OPENED 状态跃迁产生；约一秒落回常亮，减少动态时改为柔和定色。
                    double flare = reduced ? .2 : Math.exp(-age * 3.5);
                    color = 0xFFF5D6;
                    alpha = (int) (205 + 50 * flare);
                    width *= 1 + 2.5f * (float) flare;
                    progress = 1;
                } else {
                    color = 0xC64D51;
                    alpha = (int) (210 * Math.max(0, 1 - age / 2.4));
                    progress = reduced ? flash.progress() : flash.progress() * Math.max(0, 1 - age / 1.7);
                    // 零进度中断仍沿全脉给一次低亮暗红，玩家能看见停止而非静默消失。
                    for (int i = 1; i < points.size(); i++) {
                        line(buffer, points.get(i - 1), points.get(i), unit, alpha / 3, color);
                    }
                }
            }

            for (int i = 1; i < points.size(); i++) {
                Vector3f from = points.get(i - 1);
                Vector3f to = points.get(i);
                double start = path.fractions[i - 1];
                double end = path.fractions[i];
                line(buffer, from, to, unit * .7f, 100, 0x705832);
                if (start >= progress || (warning && i % 5 == 0)) {
                    continue;
                }
                Vector3f litEnd = end > progress ? new Vector3f(from).lerp(to, (float) ((progress - start) / (end - start))) : to;
                line(buffer, from, litEnd, width * 3, alpha / 5, color);
                line(buffer, from, litEnd, width, alpha, color);
            }
            if (active && flash == null) {
                // 前沿是权威进度标记，装饰光点则始终沿相同方向流动，不代表额外真元入账。
                Vector3f front = path.point(points, progress);
                diamond(buffer, front, unit * 3.5f, 245, warning ? 0xF0A76A : 0xFFF4D5);
                int count = Math.min(12, particleBudget - emitted);
                for (int i = 0; i < count; i++) {
                    double along = (seconds * .23 + i / (double) Math.max(1, count)) % 1;
                    Vector3f point = path.point(points, along);
                    diamond(buffer, point, unit * 1.6f, 170, color);
                }
                emitted += count;
            }
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        return emitted;
    }

    private static void line(BufferBuilder buffer, Vector3f a, Vector3f b, float radius, int alpha, int color) {
        // 以相机空间窄面片绘制稳定线宽，不依赖平台 GL 宽线实现。
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float length = (float) Math.hypot(dx, dy);
        if (length < .000001f) {
            return;
        }
        float x = -dy / length * radius;
        float y = dx / length * radius;
        int argb = alpha << 24 | color;
        buffer.vertex(a.x - x, a.y - y, a.z).color(argb).next();
        buffer.vertex(b.x - x, b.y - y, b.z).color(argb).next();
        buffer.vertex(b.x + x, b.y + y, b.z).color(argb).next();
        buffer.vertex(a.x + x, a.y + y, a.z).color(argb).next();
    }

    private static void diamond(BufferBuilder buffer, Vector3f p, float r, int alpha, int color) {
        // 前沿的菱形与普通流光可从形状区分，静态模式仍保留前沿。
        int argb = alpha << 24 | color;
        buffer.vertex(p.x - r, p.y, p.z).color(argb).next();
        buffer.vertex(p.x, p.y - r, p.z).color(argb).next();
        buffer.vertex(p.x + r, p.y, p.z).color(argb).next();
        buffer.vertex(p.x, p.y + r, p.z).color(argb).next();
    }

    private static final class Path {
        private final List<Node> nodes;
        private final double[] fractions;

        Path(List<Node> nodes) {
            // 路线只缓存一次；弧长比例保证曲折和粗细变化不会造成进度跳跃。
            if (nodes == null || nodes.size() < 2 || nodes.size() > 4096) {
                throw UiErrors.invalid(UiErrors.REQUIRED_OPENING_EFFECTS);
            }
            this.nodes = List.copyOf(nodes);
            fractions = new double[nodes.size()];
            for (int i = 1; i < nodes.size(); i++) {
                Node a = nodes.get(i - 1);
                Node b = nodes.get(i);
                fractions[i] = fractions[i - 1] + new Vector3f(a.x(), a.y(), a.z()).distance(b.x(), b.y(), b.z());
            }
            double length = fractions[fractions.length - 1];
            if (!Double.isFinite(length) || length <= 0) {
                throw UiErrors.invalid(UiErrors.REQUIRED_OPENING_EFFECTS);
            }
            for (int i = 0; i < fractions.length; i++) {
                fractions[i] /= length;
            }
        }

        List<Vector3f> project(Matrix4f matrix, Function<String, Matrix4f> pose) {
            // 所有点用相同姿态采样，关节附近混合两个骨矩阵，避免盘腿后连出站姿直线。
            var points = new ArrayList<Vector3f>(nodes.size());
            for (Node node : nodes) {
                Vector3f point = new Vector3f(node.x(), node.y(), node.z());
                if (pose != null) {
                    Vector3f adjacent = new Vector3f(point);
                    pose.apply(node.bone()).transformPosition(point);
                    pose.apply(node.adjacentBone()).transformPosition(adjacent);
                    point.lerp(adjacent, node.blend());
                }
                points.add(matrix.transformPosition(point));
            }
            return points;
        }

        Vector3f point(List<Vector3f> points, double fraction) {
            // 光点和边界复用原始弧长，骨架姿态不会改变服务端进度在经脉上的位置。
            for (int i = 1; i < fractions.length; i++) {
                if (fraction <= fractions[i]) {
                    double span = fractions[i] - fractions[i - 1];
                    float along = span > 0 ? (float) ((fraction - fractions[i - 1]) / span) : 0;
                    return new Vector3f(points.get(i - 1)).lerp(points.get(i), along);
                }
            }
            return new Vector3f(points.get(points.size() - 1));
        }
    }
}
