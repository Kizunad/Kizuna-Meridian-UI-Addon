package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.UIComponent.FocusSource;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.*;

/** 独立深度缓冲的三维吐纳场景；逐帧只插值展示，身体资源由宿主结算。 */
public final class BreathingCanvas extends BaseUIComponent {
    private static final int BACKGROUND = 0xFF030507;
    private static final float[] CIRCLE_X = new float[49];
    private static final float[] CIRCLE_Y = new float[49];

    static {
        // 光晕和地面共用固定圆周采样，避免每帧为每个粒子重复计算三角函数。
        for (int index = 0; index <= 48; index++) {
            double angle = index * Math.PI / 24;
            CIRCLE_X[index] = (float) Math.cos(angle);
            CIRCLE_Y[index] = (float) Math.sin(angle);
        }
    }

    private final BodyScene scene;
    private final Consumer<BreathControl> sender;
    private final BreathingCamera camera = new BreathingCamera();
    private final BreathingAudio audio = new BreathingAudio();
    private final Map<Integer, ParticleDisplay> particles = new HashMap<>();
    private final Map<String, Vector3f> centers = new HashMap<>();
    private final Map<String, BodyMesh> meshes = new HashMap<>();
    private BreathingView view = BreathingView.getDefaultInstance();
    private List<BodyVisual> organs = List.of();
    private final MeditationAvatar avatar;
    private final OpeningOverlay openingOverlay;
    private int openingParticles;
    private BodySceneRenderer renderer;
    private BreathAction held = HOLD;
    private Vector3f aim = new Vector3f(.5f, .4f, .75f);
    private long heartbeatNanos;
    private long frameNanos;
    private double shownAir;
    private double ageSeconds;
    private boolean tutorial;
    private boolean rotating;

    public BreathingCanvas(BodyScene scene, Consumer<BreathControl> sender) {
        // 器官坐标和动画挂点由生物内容提供，UI 把口鼻对齐三维场景入口。
        this.scene = scene;
        openingOverlay = new OpeningOverlay(scene);
        this.sender = sender;
        sizing(Sizing.fill(100), Sizing.fill(100));
        id("breathing-scene");
        for (BodyVisual visual : scene.presentation().getVisualsList()) {
            Vector3f center = new Vector3f();
            for (ModelPoint point : visual.getVerticesList()) {
                center.add(point.getX(), point.getY(), point.getZ());
            }
            centers.put(visual.getId(), center.div(visual.getVerticesCount()));
        }
        BreathingPresentation presentation = scene.presentation().getBreathing();
        avatar = MeditationAvatar.create(presentation.getAnimationId());
    }

    public void update(GatheringView gathering) {
        // 按实际接收间隔估计展示速度；重复快照不重置轨迹，重生不跨旧位置插值。
        BreathingView incoming = gathering.getBreathing();
        openingOverlay.update(gathering.getBody());
        if (!view.getParticlesList().equals(incoming.getParticlesList())) {
            long now = System.nanoTime();
            for (BreathParticle particle : incoming.getParticlesList()) {
                particles.computeIfAbsent(particle.getId(), id -> new ParticleDisplay()).receive(particle, now);
            }
            particles.keySet().removeIf(id -> incoming.getParticlesList().stream().noneMatch(particle -> particle.getId() == id));
        }
        view = incoming;
        organs = scene.presentation().getVisualsList().stream()
                .filter(node -> node.getTargetKind() == BodyTargetKind.ORGAN
                        && view.getOrganIdsList().contains(node.getTargetId()) && BodyScene.exists(gathering.getBody(), node))
                .toList();
        if (!view.getActive()) {
            held = HOLD;
        }
        audio.update(view, view.getAutomatic() ? view.getAction() : held, tutorial || rotating || !Minecraft.getInstance().isWindowActive());
    }

    public void tutorial(boolean visible) {
        // 教学阻断呼吸做功，相机仍可观察模型。
        tutorial = visible;
        held = HOLD;
        audio.stop();
    }

    public BreathingView breathingView() {
        // 附属与交互验收只读服务端投影，不暴露可写身体或粒子模拟器。
        return view;
    }

    public void stopInput() {
        // 关窗和失焦立即结束操作，短输入租约另作断线保护。
        held = HOLD;
        audio.stop();
        if (view.getActive()) {
            send(END_BREATHING);
        }
    }

    public void dispose() {
        // 窗口拥有自己的画中画纹理，关闭时释放，反复练习不会积累显存。
        meshes.clear();
        audio.close();
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    private void send(BreathAction action) {
        // 上传三维目标而非屏幕位置，服务端不会误用相机旋转前的投影。
        sender.accept(BreathControl.newBuilder().setAction(action).setAimX(aim.x).setAimY(aim.y).setAimZ(aim.z).build());
        heartbeatNanos = System.nanoTime();
    }

    public void tickInput() {
        // 自动由服务器持续驱动，无需手动心跳；切换桌面焦点不会结束自动练习。
        long now = System.nanoTime();
        audio.update(view, view.getAutomatic() ? view.getAction() : held, tutorial || rotating || !Minecraft.getInstance().isWindowActive());
        if (!view.getActive() || view.getAutomatic() || tutorial || now - heartbeatNanos < 250_000_000L) {
            return;
        }
        if (!Minecraft.getInstance().isWindowActive()) {
            stopInput();
        } else {
            send(held);
        }
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 三维场景提交到画布自己的画中画纹理，文字和教学随后按 GUI 顺序覆盖，界面不共享模型深度。
        long now = System.nanoTime();
        double seconds = frameNanos == 0 ? 0 : Math.min(.1, (now - frameNanos) / 1e9);
        frameNanos = now;
        ageSeconds += seconds;
        for (ParticleDisplay particle : particles.values()) {
            particle.advance(seconds, now);
        }
        shownAir += (view.getAirLiters() - shownAir) * (1 - Math.exp(-seconds * 14));
        double airRatio = view.getCapacityLiters() > 0 ? shownAir / view.getCapacityLiters() : 0;
        context.fill(x, y, x + width, y + height, BACKGROUND);
        if (width > 0 && height > 0) {
            if (renderer == null) {
                renderer = new BodySceneRenderer();
            }
            context.guiRenderState.addPicturesInPictureState(BodySceneRenderer.State.of(renderer, breathingScene(airRatio, now), true,
                    x, y, x + width, y + height, new Matrix3x2f(context.pose()), context.scissorStack.peek()));
        }
        context.enableScissor(x, y, x + width, y + height);
        drawText(context);
        if (tutorial) {
            drawTutorial(context);
        }
        context.disableScissor();
    }

    private BodySceneRenderer.Scene breathingScene(double airRatio, long now) {
        // 姿态、相机和器官矩阵在绘制时定下；场景稍后在透视投影下按相机空间提交。
        ModelPoint intake = scene.presentation().getBreathing().getIntakePoint();
        Vector3f mouth = new Vector3f(intake.getX(), intake.getY(), intake.getZ());
        // 自动呼吸的姿态跟随权威吸呼阶段，不能依赖未按下的手动键。
        BreathAction displayedAction = view.getAutomatic() ? view.getAction() : held;
        avatar.sample(airRatio, displayedAction == EXHALE);
        avatar.attachment(scene.presentation().getBreathing().getIntakeBone()).transformPosition(mouth);
        Matrix4f viewMatrix = camera.view();
        Matrix4f bodyMatrix = new Matrix4f(viewMatrix).translate(-mouth.x, -mouth.y, -mouth.z);
        float floor = avatar.minimumY() - mouth.y;
        List<PlacedOrgan> placedOrgans = placeOrgans(bodyMatrix, airRatio);
        return (matrices, collector) -> {
            // 各层按序号先后绘制：底座、表层、器官、路线叠层、粒子与气流。
            // 底座先画且不测深度，任何角度都不会盖住身体；表层测试并写入深度，肺随后透视高亮。
            collector.order(0).submitCustomGeometry(matrices, BodyRenderTypes.TRIANGLES_FLAT,
                    (pose, buffer) -> drawFloor(buffer, viewMatrix, floor));
            collector.order(1).submitCustomGeometry(matrices, BodyRenderTypes.QUADS_DEPTH,
                    (pose, buffer) -> avatar.draw(buffer, bodyMatrix));
            collector.order(2).submitCustomGeometry(matrices, BodyRenderTypes.TRIANGLES_FLAT, (pose, buffer) -> {
                for (PlacedOrgan organ : placedOrgans) {
                    organ.mesh().emit(buffer, organ.matrix(), 0x8BE6D5, 1, 245);
                }
            });
            collector.order(3).submitCustomGeometry(matrices, BodyRenderTypes.QUADS_FLAT, (pose, buffer) ->
                    openingParticles = openingOverlay.draw(buffer, pose.pose(), bodyMatrix, avatar::attachment, null, now, .005f, true));
            // 粒子上限扣除路线光点；各层按序号依次写入，这里读到的 openingParticles 已是本帧路线叠层的结果。
            collector.order(4).submitCustomGeometry(matrices, BodyRenderTypes.TRIANGLES_TESTED,
                    (pose, buffer) -> drawParticles(buffer, viewMatrix, displayedAction));
        };
    }

    private List<PlacedOrgan> placeOrgans(Matrix4f matrix, double airRatio) {
        // 器官随动画骨骼移动，并按空气量绕自身中心伸缩。
        var placed = new ArrayList<PlacedOrgan>();
        for (BodyVisual organ : organs) {
            Vector3f center = centers.get(organ.getId());
            Matrix4f organMatrix = new Matrix4f(matrix);
            if (!organ.getAnimationBone().isEmpty()) {
                organMatrix.mul(avatar.attachment(organ.getAnimationBone()));
            }
            float expansion = (float) (.84 + .23 * airRatio);
            organMatrix.translate(center).scale(expansion, .92f + (float) airRatio * .12f, expansion)
                    .translate(-center.x, -center.y, -center.z);
            BodyMesh mesh = meshes.computeIfAbsent(organ.getId(), id -> new BodyMesh(organ, false));
            placed.add(new PlacedOrgan(mesh, organMatrix));
        }
        return placed;
    }

    /** 本帧器官网格与其相机空间矩阵。 */
    private record PlacedOrgan(BodyMesh mesh, Matrix4f matrix) { }

    private void drawFloor(VertexConsumer buffer, Matrix4f matrix, float floorY) {
        // 底座是真实水平地面，由多层半透明同心圆叠出由内向外的渐变。
        for (int ring = 5; ring >= 0; ring--) {
            float radius = .50f + ring * .08f;
            for (int point = 0; point < 48; point++) {
                buffer.addVertex(matrix, 0, floorY - .01f, 0).setColor(27, 48, 52, 12);
                buffer.addVertex(matrix, CIRCLE_X[point] * radius, floorY - .01f, CIRCLE_Y[point] * radius)
                        .setColor(27, 48, 52, 12);
                buffer.addVertex(matrix, CIRCLE_X[point + 1] * radius, floorY - .01f, CIRCLE_Y[point + 1] * radius)
                        .setColor(27, 48, 52, 12);
            }
        }
    }

    private void drawParticles(VertexConsumer buffer, Matrix4f viewMatrix, BreathAction displayedAction) {
        // 粒子是三维位置上朝向相机的面片，深度测试负责身体前后遮挡。
        int drawnParticles = 0;
        for (BreathParticle particle : view.getParticlesList()) {
            // 真正捕获仍读取完整服务端粒子集合，上限只裁剪装饰绘制。
            if (drawnParticles++ >= Math.max(0, EffectSettings.particleLimit() - openingParticles)) {
                break;
            }
            ParticleDisplay display = particles.get(particle.getId());
            if (display == null) {
                continue;
            }
            Vector3f world = new Vector3f(display.position);
            if (!view.getActive()) {
                // 未开始也有轻缓游离展示，偏移不上传、不参与摄入结算。
                float phase = particle.getId() * 1.7f;
                world.add((float) Math.sin(ageSeconds * .4 + phase) * .10f,
                        (float) Math.cos(ageSeconds * .3 + phase) * .07f, (float) Math.sin(ageSeconds * .5 + phase) * .10f);
            }
            Vector3f old = new Vector3f(world).fma(-.15f, display.velocity);
            Vector3f eye = viewMatrix.transformPosition(world);
            viewMatrix.transformPosition(old);
            int color = elementColor(particle.getAttribute());
            for (int trail = 1; trail <= 4; trail++) {
                Vector3f position = new Vector3f(eye).lerp(old, trail / 5f);
                disc(buffer, position, .012f, (28 - trail * 5) << 24 | color);
            }
            disc(buffer, eye, .06f, 0x0B000000 | color);
            disc(buffer, eye, .032f, 0x23000000 | color);
            disc(buffer, eye, .014f, 0xD0000000 | color);
            disc(buffer, eye, .005f, 0xE5FFFFFF);
        }
        boolean flowing = view.getActive() && displayedAction != HOLD && view.getAction() == displayedAction;
        if (!EffectSettings.reducedMotion() && flowing && ((displayedAction == INHALE && view.getAirLiters() < view.getCapacityLiters())
                || (displayedAction == EXHALE && view.getAirLiters() > 0))) {
            drawAirflow(buffer, viewMatrix, displayedAction);
        }
    }

    private void drawAirflow(VertexConsumer buffer, Matrix4f viewMatrix, BreathAction displayedAction) {
        // 自动模式固定朝正前方，与权威气流一致；手动模式跟随玩家瞄准。
        Vector3f direction = view.getAutomatic() ? new Vector3f(0, 0, 1) : BreathingCamera.world(aim.x, aim.y, aim.z);
        if (direction.lengthSquared() < .0001f) {
            direction.set(0, 0, 1);
        }
        direction.normalize();
        Vector3f side = new Vector3f(direction).cross(0, 1, 0);
        if (side.lengthSquared() < .0001f) {
            side.set(1, 0, 0);
        }
        side.normalize();
        Vector3f up = new Vector3f(side).cross(direction).normalize();
        for (int strand = 0; strand < 5; strand++) {
            double angle = strand * Math.PI * 2 / 5;
            double travel = (ageSeconds * .65 + strand * .19) % 1;
            double start = displayedAction == INHALE ? .82 - travel * .55 : .06 + travel * .55;
            Vector3f previous = null;
            for (int segment = 0; segment <= 6; segment++) {
                double along = start + segment * .035;
                float spread = (float) (.015 + along * along * .22);
                Vector3f point = new Vector3f(direction).mul((float) (.035 + along * .58));
                point.add(new Vector3f(side).mul((float) Math.cos(angle) * spread));
                point.add(new Vector3f(up).mul((float) Math.sin(angle) * spread));
                viewMatrix.transformPosition(point);
                if (previous != null) {
                    int alpha = (int) (95 * Math.sin(segment * Math.PI / 7));
                    line(buffer, previous, point, .0018f, alpha << 24 | 0xB8DAD5);
                }
                previous = point;
            }
        }
    }

    private void drawText(OwoUIGraphics context) {
        // 文字在场景纹理之后绘制，模型和底座不占用前景图层。
        var font = Minecraft.getInstance().font;
        String hint = view.getActive() ? "左吸 · 右呼 · 中键旋转 · 滚轮缩放" : "中键旋转 · 滚轮缩放";
        if (view.getActive() && view.getAutomatic()) {
            // 自动模式由服务端吸呼，提示里不再要求玩家按左右键。
            hint = "自动吸呼中 · 中键旋转 · 滚轮缩放";
        }
        hint = font.plainSubstrByWidth(hint, Math.max(0, width - 24));
        context.text(font, hint, x + (width - font.width(hint)) / 2, y + height - 18, 0xFF81928F, false);
    }

    private void drawTutorial(OwoUIGraphics context) {
        // 教学随场景宽度换行，紧凑窗口精简措辞，底部仍留出开始按钮的位置。
        context.fill(x, y, x + width, y + height, 0xB0000000);
        var font = Minecraft.getInstance().font;
        int textWidth = Math.max(1, Math.min(286, width - 40));
        String instructions = "左键吸气，右键呼气，松开停气。\n气流带动全场灵气，近处更强且有惯性。\n中键旋转，滚轮缩放。\n肺满需呼气，留意耐力与精力。";
        var lines = font.split(Component.literal(instructions), textWidth);
        int lineHeight = font.lineHeight + 2;
        int textHeight = font.lineHeight + 6 + lines.size() * lineHeight;
        int left = x + (width - textWidth) / 2;
        int top = y + Math.max(8, (height - 54 - textHeight) / 2);
        context.fill(left - 8, top - 6, left + textWidth + 8, top + textHeight + 6, 0xF0101D21);
        context.fill(left - 8, top - 6, left - 6, top + textHeight + 6, 0xFFA3D7C9);
        context.text(font, "第一次吐纳 · 手动引导气息", left, top, 0xFFE5DEC9, false);
        for (int index = 0; index < lines.size(); index++) {
            context.text(font, lines.get(index), left, top + font.lineHeight + 6 + index * lineHeight, 0xFFACBFBC, false);
        }
    }

    private static void line(VertexConsumer buffer, Vector3f from, Vector3f to, float halfWidth, int color) {
        // 用窄面片代替依赖驱动宽度的 GL 线，端点保留三维深度与透视关系。
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < .000001f) {
            return;
        }
        float offsetX = -dy / length * halfWidth;
        float offsetY = dx / length * halfWidth;
        buffer.addVertex(from.x - offsetX, from.y - offsetY, from.z).setColor(color);
        buffer.addVertex(to.x - offsetX, to.y - offsetY, to.z).setColor(color);
        buffer.addVertex(to.x + offsetX, to.y + offsetY, to.z).setColor(color);
        buffer.addVertex(from.x - offsetX, from.y - offsetY, from.z).setColor(color);
        buffer.addVertex(to.x + offsetX, to.y + offsetY, to.z).setColor(color);
        buffer.addVertex(from.x + offsetX, from.y + offsetY, from.z).setColor(color);
    }

    private static final class ParticleDisplay {
        private final Vector3f position = new Vector3f();
        private final Vector3f target = new Vector3f();
        private final Vector3f velocity = new Vector3f();
        private final Vector3f predicted = new Vector3f();
        private long receivedNanos;
        private int generation = -1;

        void receive(BreathParticle particle, long now) {
            // 新世代直接从真实出生位置显示，避免回收后的粒子横穿整个场景。
            Vector3f incoming = BreathingCamera.world(particle.getX(), particle.getY(), particle.getZ());
            if (generation != particle.getGeneration()) {
                generation = particle.getGeneration();
                position.set(incoming);
                velocity.zero();
            } else {
                double seconds = (now - receivedNanos) / 1e9;
                if (seconds > .005 && seconds < 1) {
                    incoming.sub(target, velocity).div((float) seconds);
                } else {
                    velocity.zero();
                }
            }
            target.set(incoming);
            receivedNanos = now;
        }

        void advance(double seconds, long now) {
            // 有界外推填平非固定接收间隔，再平滑校正；展示位置不参与权威捕获。
            double horizon = Math.min(.3, Math.max(0, (now - receivedNanos) / 1e9));
            if (horizon < .3) {
                position.fma((float) seconds, velocity);
            }
            predicted.set(target).fma((float) horizon, velocity);
            position.lerp(predicted, (float) (1 - Math.exp(-seconds * 12)));
        }
    }

    private static void disc(VertexConsumer buffer, Vector3f eye, float radius, int color) {
        // 相机空间面片保留真实 Z，透视矩阵负责远近大小。
        for (int point = 0; point < 12; point++) {
            int start = point * 4;
            int end = start + 4;
            buffer.addVertex(eye.x, eye.y, eye.z).setColor(color);
            buffer.addVertex(eye.x + CIRCLE_X[start] * radius, eye.y + CIRCLE_Y[start] * radius, eye.z).setColor(color);
            buffer.addVertex(eye.x + CIRCLE_X[end] * radius, eye.y + CIRCLE_Y[end] * radius, eye.z).setColor(color);
        }
    }

    private float aspect() {
        // 窗口缩放时渲染和瞄准使用同一比例。
        return width / (float) Math.max(1, height);
    }

    public Vector3f projectParticle(BreathParticle particle) {
        // 演示验收复用真实投影，不在验收代码保留二维公式。
        return camera.project(BreathingCamera.world(particle.getX(), particle.getY(), particle.getZ()), aspect());
    }

    private void aim(double mouseX, double mouseY) {
        // 反投影后按附近粒子细化深度，只选择方向，仍需气流将灵气运至入口。
        double screenX = mouseX / Math.max(1, width);
        double screenY = mouseY / Math.max(1, height);
        Vector3f target = camera.target(screenX, screenY, aspect());
        double nearestPixels = 16;
        for (BreathParticle particle : view.getParticlesList()) {
            Vector3f projected = projectParticle(particle);
            double distance = Math.hypot((projected.x - screenX) * width, (projected.y - screenY) * height);
            if (projected.z > -1 && projected.z < 1 && distance < nearestPixels) {
                nearestPixels = distance;
                target = BreathingCamera.world(particle.getX(), particle.getY(), particle.getZ());
            }
        }
        aim = BreathingCamera.local(target);
    }

    static int elementColor(String attribute) {
        // 五行采用固定色相，第三方属性保留完整身份并显示中性色。
        return switch (attribute) {
            case "meridian:metal" -> 0xF1DC9A;
            case "meridian:wood" -> 0x67D998;
            case "meridian:water" -> 0x66B9EE;
            case "meridian:fire" -> 0xEF877A;
            case "meridian:earth" -> 0xBD9761;
            default -> 0xD8E5E0;
        };
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 中键随时旋转，未开始时左键也可观察；吸呼保持左右键操作。
        int button = click.button();
        if (button == 2 || (!view.getActive() && !tutorial && button == 0)) {
            rotating = true;
            held = HOLD;
            audio.stop();
            if (view.getActive()) {
                send(HOLD);
            }
            return true;
        }
        if (!view.getActive() || view.getAutomatic() || tutorial || rotating || (button != 0 && button != 1)) {
            return false;
        }
        aim(click.x(), click.y());
        held = button == 0 ? INHALE : EXHALE;
        send(held);
        return true;
    }

    @Override
    public boolean onMouseDrag(MouseButtonEvent click, double dx, double dy) {
        // 观察旋转与引导分别处理，旋转不会改变已经发出的气流方向。
        if (rotating) {
            camera.orbit(dx, dy);
            return true;
        }
        if (view.getActive() && !tutorial && (click.button() == 0 || click.button() == 1)) {
            aim(click.x(), click.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
        // 缩放只调整观察距离，不影响呼吸能力或服务端频率。
        camera.zoom(amount);
        return true;
    }

    @Override
    public boolean onMouseUp(MouseButtonEvent click) {
        // 旋转和吸呼分别清理，双键交替不被旧按键释放打断。
        int button = click.button();
        if (rotating) {
            rotating = false;
            return true;
        }
        if ((button == 0 && held == INHALE) || (button == 1 && held == EXHALE)) {
            held = HOLD;
            audio.stop();
            send(HOLD);
        }
        return true;
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // owo 将拖动和释放交给焦点组件，画布必须接收焦点才能完整处理吸呼与旋转。
        return true;
    }

    @Override
    public void onFocusLost() {
        // 切换控件暂停输入，留存粒子速度仍由权威事件推进。
        held = HOLD;
        rotating = false;
        audio.stop();
        if (view.getActive()) {
            send(HOLD);
        }
        super.onFocusLost();
    }
}
