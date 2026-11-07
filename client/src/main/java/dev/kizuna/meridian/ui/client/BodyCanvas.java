package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.inventoryui.client.component.ModelPreviewCamera;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 通用三角网格画布，复用 Inventory UI 的相机，实现身体对象的精确面选取。 */
public final class BodyCanvas extends BaseUIComponent {
    private static final int SELECTED = 0xF6D888;
    private static final float DEPTH_SCALE = 0.1f;
    private static final double CLICK_DISTANCE_PIXELS = 4;
    private static final int CIRCUIT_IMAGE_SIZE = 28;
    private static final int CIRCUIT_TEXTURE_SIZE = 32;
    private final Consumer<BodyVisual> selection;
    private final Runnable showOverview;
    private final ModelPreviewCamera camera = new ModelPreviewCamera();
    private final AABB bounds;
    private List<BodyVisual> sourceVisible = List.of();
    private List<BodyVisual> visible = List.of();
    private boolean slimSkin;
    private final Map<String, BodyMesh> meshes = new HashMap<>();
    private String selectedId = "";
    private long previousFrame;
    private double draggedPixels;
    private boolean dragging;
    private MeridianFlow flow;
    private final OpeningOverlay openingOverlay;
    private final Map<String, Double> openingProgress = new HashMap<>();
    private final Map<String, Double> storedFraction = new HashMap<>();
    private final Map<String, Float> shownBrightness = new HashMap<>();
    private BodySceneRenderer renderer;
    private List<Marker> markers = List.of();
    private int transitColor = 0xFFFFE48A;
    private Identifier circuitImage;

    public void circuitImage(Identifier texture) {
        // 生物附属提供气团贴图，位置始终从真实闭环在途读取。
        circuitImage = java.util.Objects.requireNonNull(texture);
    }

    public void clearCircuitImage() {
        // 场景关闭后释放附属贴图选择，不影响其他经脉窗口。
        circuitImage = null;
    }

    /** 模型空间实线圆环；半径和线宽均为屏幕像素，生物附属决定颜色含义。 */
    public record Marker(float x, float y, float z, float radiusPixels, float thicknessPixels, int color) { }

    public void effects(List<Marker> markers, int color) {
        // 生物附属提供模型空间标记，通用画布只负责投影，不解释人体关口。
        this.markers = List.copyOf(markers);
        transitColor = color;
    }
    private Runnable disposal = () -> { /* 普通检视没有额外活动会话。 */ };

    public void onDispose(Runnable action) {
        // 附属释放自己的输入状态；画布关闭后不能留下按住的推力。
        disposal = java.util.Objects.requireNonNull(action);
    }

    public BodyCanvas(BodyScene scene, Consumer<BodyVisual> selection, Runnable showOverview) {
        // 包围盒由生物提供；旋转、缩放和聚焦均使用同一个模型坐标系。
        this.selection = selection;
        this.showOverview = showOverview;
        openingOverlay = new OpeningOverlay(scene);
        ModelPoint minimum = scene.presentation().getMinimum();
        ModelPoint maximum = scene.presentation().getMaximum();
        bounds = new AABB(minimum.getX(), minimum.getY(), minimum.getZ(), maximum.getX(), maximum.getY(), maximum.getZ());
        sizing(Sizing.fill(100), Sizing.fill(100));
        camera.focus(new Vec3(0.5, 0.5, 0.5), 1, 0, 0, false);
        camera.settle();
    }

    public void dispose() {
        // 画中画纹理属于本画布，窗口关闭时释放，反复打开不会积累显存。
        disposal.run();
        meshes.clear();
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    public void opening(BodyScene scene, BodySnapshot body) {
        // 经脉窗口复用站姿几何；颜色只读取服务端开拓进度，不按本地时间判成功。
        openingOverlay.update(body);
        if (flow == null) {
            // 路线是必需接线；装配错误直接拒绝，避免静默画出另一套人体流向。
            List<MeridianRouteProvider> providers = FabricLoader.getInstance()
                    .getEntrypoints(MeridianRouteProvider.ENTRYPOINT, MeridianRouteProvider.class);
            if (providers.size() != 1) {
                throw UiErrors.invalid(UiErrors.REQUIRED_ROUTE_PROVIDER);
            }
            flow = new MeridianFlow(scene, providers.get(0));
        }
        openingProgress.clear();
        storedFraction.clear();
        for (MeridianState state : body.getMeridianStatesList()) {
            double progress = state.getOpening().getProgress();
            if (state.getOpening().getStage() == MeridianOpeningStage.OPENED) {
                progress = 1;
            }
            openingProgress.put(state.getId(), progress);
            double capacity = body.getStructure().getMeridiansList().stream()
                    .filter(item -> item.getId().equals(state.getId())).mapToDouble(MeridianDefinition::getLumenVolume)
                    .findFirst().orElse(0);
            storedFraction.put(state.getId(), QiDisplay.storageFraction(state.getLumenQi(), capacity));
        }
        flow.update(body);
    }

    public void bodyEffects(BodySnapshot body) {
        // 普通检视仅刷新叠层状态，不切换为黑色经脉场景或改变玩家皮肤。
        openingOverlay.update(body);
    }

    public void highlight(BodyVisual node) {
        // 选择只改变高亮，镜头由 F 快捷键或拖动明确控制。
        selectedId = node.getId();
    }

    public void visible(List<BodyVisual> nodes) {
        // 更新几何时保留仍然存在的高亮，避免无关部位改造打断当前观察。
        sourceVisible = List.copyOf(nodes);
        rebuildAppearance();
        if (visible.stream().noneMatch(node -> node.getId().equals(selectedId))) {
            selectedId = "";
        }
    }

    public void select(BodyVisual node) {
        // 从网格顶点计算焦点，UI 不需要任何人类部位坐标表。
        refreshAppearance();
        String nodeId = node.getId();
        node = visible.stream().filter(candidate -> candidate.getId().equals(nodeId)).findFirst().orElse(node);
        selectedId = node.getId();
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        for (ModelPoint point : node.getVerticesList()) {
            Vector3f vertex = new Vector3f(point.getX(), point.getY(), point.getZ());
            minimum.min(vertex);
            maximum.max(vertex);
        }
        Vector3f center = new Vector3f(minimum).add(maximum).mul(0.5f);
        double extent = Math.max(maximum.x - minimum.x, Math.max(maximum.y - minimum.y, maximum.z - minimum.z));
        float zoom = (float) Math.min(4, Math.max(1.2, bounds.getYsize() * 0.5 / Math.max(0.05, extent)));
        Vec3 normalized = new Vec3((center.x - bounds.minX) / bounds.getXsize(),
                (center.y - bounds.minY) / bounds.getYsize(), (center.z - bounds.minZ) / bounds.getZsize());
        // 自动转向模型所在的前后侧，避免选中背部肌肉后仍被正面几何遮住。
        float yaw = center.z < -0.02f ? 180 : 0;
        camera.focus(normalized, zoom, yaw, 0, false);
    }

    public void overview() {
        // 返回全身只改变相机与高亮，身体状态不受影响。
        selectedId = "";
        camera.focus(new Vec3(0.5, 0.5, 0.5), 1, 0, 0, false);
    }

    public ModelPreviewCamera camera() {
        // 暴露已有相机供无动画偏好及交互验收使用。
        return camera;
    }

    private void refreshAppearance() {
        // Minecraft 自身管理皮肤加载；臂型变化时才重建几何，普通帧复用已展开的网格。
        Minecraft client = Minecraft.getInstance();
        boolean currentSlim = client.player != null && client.player.getSkin().model() == PlayerModelType.SLIM;
        if (currentSlim != slimSkin) {
            slimSkin = currentSlim;
            meshes.clear();
            rebuildAppearance();
        }
    }

    private void rebuildAppearance() {
        // 窄臂顶点与 UV 来自内容包，显示和选取共用同一有效网格。
        var effectiveVisuals = new ArrayList<BodyVisual>();
        for (BodyVisual node : sourceVisible) {
            if (slimSkin && node.hasPlayerSkin()) {
                PlayerSkinAppearance skin = node.getPlayerSkin();
                node = node.toBuilder().clearVertices().addAllVertices(skin.getSlimVerticesList())
                        .clearTextureUv().addAllTextureUv(skin.getSlimTextureUvList()).build();
            }
            effectiveVisuals.add(node);
        }
        visible = List.copyOf(effectiveVisuals);
    }

    private Matrix4f modelTransform() {
        // 绘制与命中共享相同矩阵，防止缩放、旋转或聚焦后选错部位。
        float scale = camera.scale(bounds, width, height);
        Vec3 center = camera.center(bounds);
        return new Matrix4f().translation(width / 2f, height / 2f, 0)
                .scale(scale, -scale, scale * DEPTH_SCALE)
                .rotateX((float) Math.toRadians(camera.pitch())).rotateY((float) Math.toRadians(camera.yaw()))
                .translate((float) -center.x, (float) -center.y, (float) -center.z);
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 镜头和亮度按实际帧间隔插值；节点清单随快照更新，不逐帧运行身体计算。
        refreshAppearance();
        long now = System.nanoTime();
        float elapsedSeconds = 0;
        if (previousFrame != 0) {
            elapsedSeconds = Math.min(0.1f, (now - previousFrame) / 1_000_000_000f);
            camera.advance(elapsedSeconds);
        }
        previousFrame = now;
        // 模型画进画布自己的画中画纹理，独立深度；底色先填，纹理随后按 GUI 顺序叠上，文字和浮窗不受模型深度影响。
        context.fill(x, y, x + width, y + height,
                MeridianTheme.color(dev.kizuna.inventoryui.theme.ThemeTokens.PREVIEW_BACKGROUND));
        BodySceneRenderer.Scene scene;
        if (flow != null) {
            scene = openingScene(now, elapsedSeconds);
        } else {
            scene = inspectionScene(now);
        }
        if (width <= 0 || height <= 0) {
            // 布局尚未给出面积时没有可绘制的纹理。
            return;
        }
        if (renderer == null) {
            renderer = new BodySceneRenderer();
        }
        context.guiRenderState.addPicturesInPictureState(BodySceneRenderer.State.of(renderer, scene, false,
                x, y, x + width, y + height, new Matrix3x2f(context.pose()), context.scissorStack.peek()));
        drawCircuitImages(context, now);
    }

    private void drawCircuitImages(OwoUIGraphics context, long now) {
        // 透明气团随模型投影并受画布裁剪；静态模式关闭移动图像，保留路线与关口。
        if (circuitImage == null || flow == null || EffectSettings.reducedMotion()) {
            return;
        }
        Matrix4f model = modelTransform();
        context.enableScissor(x, y, x + width, y + height);
        for (MeridianFlow.Flight flight : flow.flights()) {
            if (!flight.controlled()) {
                continue;
            }
            Vector3f point = model.transformPosition(flight.point(now, 0));
            int left = x + Math.round(point.x) - CIRCUIT_IMAGE_SIZE / 2;
            int top = y + Math.round(point.y) - CIRCUIT_IMAGE_SIZE / 2;
            context.blit(RenderPipelines.GUI_TEXTURED, circuitImage, left, top, 0, 0,
                    CIRCUIT_IMAGE_SIZE, CIRCUIT_IMAGE_SIZE, CIRCUIT_TEXTURE_SIZE, CIRCUIT_TEXTURE_SIZE,
                    CIRCUIT_TEXTURE_SIZE, CIRCUIT_TEXTURE_SIZE);
        }
        context.disableScissor();
    }

    private BodySceneRenderer.Scene inspectionScene(long now) {
        // 颜色、亮度和材质在绘制时定下，场景稍后提交时只把它们写入顶点。
        Matrix4f model = modelTransform();
        var tints = new ArrayList<Tint>();
        for (BodyVisual node : visible) {
            // 选中时降低其他对象亮度，密集交织的线状模型仍能看清完整主干与支路。
            int color = node.getColorRgb();
            float emphasis = 1;
            if (node.getId().equals(selectedId) && !node.hasPlayerSkin()) {
                color = SELECTED;
            } else if (!selectedId.isEmpty() && !node.getId().equals(selectedId)) {
                emphasis = 0.4f;
            }
            BodyMesh mesh = meshes.computeIfAbsent(node.getId(), id -> new BodyMesh(node, true));
            if (node.getTargetKind() == BodyTargetKind.MERIDIAN) {
                color = openingOverlay.meshColor(node.getTargetId(), color, now);
                float baseline = emphasis * openingOverlay.restingBrightness(node.getTargetId());
                emphasis = openingOverlay.meshBrightness(node.getTargetId(), baseline, now);
            }
            tints.add(new Tint(mesh, mesh.depthTested(), color, emphasis, 255));
        }
        var meridianIds = visible.stream().filter(node -> node.getTargetKind() == BodyTargetKind.MERIDIAN)
                .map(BodyVisual::getTargetId).collect(java.util.stream.Collectors.toSet());
        return (matrices, collector) -> {
            // 每个网格单独一层，按可见顺序逐个绘制；贴图与纯色网格交错时半透明边缘仍按原顺序混合。
            matrices.pushPose();
            matrices.mulPose(model);
            for (int index = 0; index < tints.size(); index++) {
                Tint tint = tints.get(index);
                collector.order(index).submitCustomGeometry(matrices, tint.type(), tint::emit);
            }
            matrices.popPose();
            // 路线叠层最后绘制且不测深度，始终压在身体表面之上。
            collector.order(tints.size()).submitCustomGeometry(matrices, BodyRenderTypes.QUADS_FLAT,
                    (pose, buffer) -> openingOverlay.draw(buffer, pose.pose(), model, null, meridianIds, now, .8f, false));
        };
    }

    private BodySceneRenderer.Scene openingScene(long now, float elapsedSeconds) {
        // 静态网格只算一次，逐帧只改变相机、颜色和少量光点；亮度平滑在绘制时推进，场景只读结果。
        Matrix4f model = modelTransform();
        var tints = new ArrayList<Tint>();
        // 黑色透明表层先画，经脉后画，内部路线始终可见且只有经脉参与选取。
        for (BodyVisual node : visible) {
            if (node.getTargetKind() != BodyTargetKind.MERIDIAN) {
                BodyMesh mesh = meshes.computeIfAbsent(node.getId(), id -> new BodyMesh(node, false));
                tints.add(new Tint(mesh, BodyRenderTypes.TRIANGLES_FLAT, 0x030609, 1, 150));
            }
        }
        // 约 0.3 秒接近新亮度，各种帧率下都只平滑显示权威进度。
        float brightnessBlend = (float) -Math.expm1(-10 * elapsedSeconds);
        for (BodyVisual node : visible) {
            if (node.getTargetKind() != BodyTargetKind.MERIDIAN) {
                continue;
            }
            // 储量产生很弱的底光，开拓仍决定主要亮度，未开满储量不会冒充已拓宽。
            float desired = (float) (.18 + .07 * storedFraction.getOrDefault(node.getTargetId(), 0.0));
            if (openingProgress.getOrDefault(node.getTargetId(), 0.0) >= 1) {
                desired += .55f;
            }
            float shown = shownBrightness.getOrDefault(node.getId(), desired);
            shown += (desired - shown) * brightnessBlend;
            shownBrightness.put(node.getId(), shown);
            if (node.getId().equals(selectedId)) {
                shown = Math.min(1, shown + .15f);
            }
            shown = openingOverlay.meshBrightness(node.getTargetId(), shown, now);
            int color = openingOverlay.meshColor(node.getTargetId(), 0xF4DC82, now);
            BodyMesh mesh = meshes.computeIfAbsent(node.getId(), id -> new BodyMesh(node, false));
            tints.add(new Tint(mesh, BodyRenderTypes.TRIANGLES_FLAT, color, shown, 230));
        }
        var meridianIds = visible.stream().filter(node -> node.getTargetKind() == BodyTargetKind.MERIDIAN)
                .map(BodyVisual::getTargetId).collect(java.util.stream.Collectors.toSet());
        return (matrices, collector) -> {
            // 表层与经脉同一材质，同层内按加入顺序叠加；路线、光点与标记在下一层。
            matrices.pushPose();
            matrices.mulPose(model);
            for (Tint tint : tints) {
                collector.order(0).submitCustomGeometry(matrices, tint.type(), tint::emit);
            }
            matrices.popPose();
            collector.order(1).submitCustomGeometry(matrices, BodyRenderTypes.QUADS_FLAT, (pose, buffer) -> {
                int openingParticles = openingOverlay.draw(buffer, pose.pose(), model, null, meridianIds, now, .85f, false);
                drawFlights(buffer, pose.pose(), model, now, EffectSettings.particleLimit() - openingParticles);
                drawMarkers(buffer, pose.pose(), model);
            });
        };
    }

    private void drawFlights(VertexConsumer buffer, Matrix4fc space, Matrix4f model, long now, int remainingParticles) {
        // 各类装饰共用画布上限，减少动态时撤掉流动光尾，保留静态路线与关口。
        int red = (transitColor >> 16) & 255;
        int green = (transitColor >> 8) & 255;
        int blue = transitColor & 255;
        for (MeridianFlow.Flight flight : flow.flights()) {
            if (EffectSettings.reducedMotion() || remainingParticles <= 0) {
                break;
            }
            for (int tail = 3; tail >= 0; tail--) {
                if (remainingParticles-- <= 0) {
                    break;
                }
                Vector3f point = model.transformPosition(flight.point(now, tail * .025));
                float radius = tail == 0 ? 1.5f : .9f;
                int alpha = 220 - tail * 48;
                buffer.addVertex(space, point.x - radius, point.y - radius, 0).setColor(red, green, blue, alpha);
                buffer.addVertex(space, point.x + radius, point.y - radius, 0).setColor(red, green, blue, alpha);
                buffer.addVertex(space, point.x + radius, point.y + radius, 0).setColor(red, green, blue, alpha);
                buffer.addVertex(space, point.x - radius, point.y + radius, 0).setColor(red, green, blue, alpha);
            }
        }
    }

    private void drawMarkers(VertexConsumer buffer, Matrix4fc space, Matrix4f model) {
        // 环形标记与模型进入同一张画中画纹理，不会盖住窗口文字或脱离旋转后的模型。
        // 以内外半径拼出连续实线环，点状环在小窗口里难以分辨颜色。
        for (Marker marker : markers) {
            Vector3f center = model.transformPosition(new Vector3f(marker.x(), marker.y(), marker.z()));
            float inner = Math.max(0, marker.radiusPixels() - marker.thicknessPixels() / 2);
            float outer = marker.radiusPixels() + marker.thicknessPixels() / 2;
            int color = marker.color();
            for (int step = 0; step < 48; step++) {
                double from = step * Math.PI * 2 / 48;
                double to = (step + 1) * Math.PI * 2 / 48;
                float fromCos = (float) Math.cos(from);
                float fromSin = (float) Math.sin(from);
                float toCos = (float) Math.cos(to);
                float toSin = (float) Math.sin(to);
                buffer.addVertex(space, center.x + fromCos * inner, center.y + fromSin * inner, 0).setColor(color);
                buffer.addVertex(space, center.x + fromCos * outer, center.y + fromSin * outer, 0).setColor(color);
                buffer.addVertex(space, center.x + toCos * outer, center.y + toSin * outer, 0).setColor(color);
                buffer.addVertex(space, center.x + toCos * inner, center.y + toSin * inner, 0).setColor(color);
            }
        }
    }

    /** 一次网格提交：材质、颜色与亮度在画布绘制时定下，场景提交时原样写入顶点。 */
    private record Tint(BodyMesh mesh, RenderType type, int color, float brightness, int alpha) {
        void emit(PoseStack.Pose pose, VertexConsumer buffer) {
            mesh.emit(buffer, pose.pose(), color, brightness, alpha);
        }
    }

    public BodyVisual pick(double mouseX, double mouseY) {
        // 对投影后的真实三角面求重心坐标，选择最靠近观察者的可见面。
        refreshAppearance();
        Matrix4f matrix = modelTransform();
        BodyVisual hit = null;
        float nearestDepth = Float.NEGATIVE_INFINITY;
        for (BodyVisual node : visible) {
            if (flow != null && node.getTargetKind() != BodyTargetKind.MERIDIAN) {
                continue;
            }
            List<Vector3f> vertices = node.getVerticesList().stream()
                    .map(point -> matrix.transformPosition(new Vector3f(point.getX(), point.getY(), point.getZ()))).toList();
            for (int index = 0; index < node.getTrianglesCount(); index += 3) {
                Vector3f a = vertices.get(node.getTriangles(index));
                Vector3f b = vertices.get(node.getTriangles(index + 1));
                Vector3f c = vertices.get(node.getTriangles(index + 2));
                double area = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
                if (Math.abs(area) < 0.000001) {
                    continue;
                }
                double wa = ((b.y - c.y) * (mouseX - c.x) + (c.x - b.x) * (mouseY - c.y)) / area;
                double wb = ((c.y - a.y) * (mouseX - c.x) + (a.x - c.x) * (mouseY - c.y)) / area;
                double wc = 1 - wa - wb;
                float depth = (float) (wa * a.z + wb * b.z + wc * c.z);
                if (wa >= 0 && wb >= 0 && wc >= 0 && depth > nearestDepth) {
                    hit = node;
                    nearestDepth = depth;
                }
            }
        }
        return hit;
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 左键点击与旋转共用入口，释放时按累计移动量区分操作。
        int button = click.button();
        if (button == 0) {
            dragging = true;
            draggedPixels = 0;
            return true;
        }
        if (button == 2) {
            showOverview.run();
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseDrag(MouseButtonEvent click, double dx, double dy) {
        // 超过点击阈值后仅旋转，不在释放时额外触发选中。
        if (dragging && click.button() == 0) {
            draggedPixels += Math.hypot(dx, dy);
            if (draggedPixels > CLICK_DISTANCE_PIXELS) {
                camera.drag(dx, dy);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseUp(MouseButtonEvent click) {
        // 命中失败保持原选择，不把背景点击映射为某个默认器官；坐标已相对画布。
        boolean handled = dragging && click.button() == 0;
        dragging = false;
        if (handled && draggedPixels <= CLICK_DISTANCE_PIXELS) {
            BodyVisual hit = pick(click.x(), click.y());
            if (hit != null) {
                selection.accept(hit);
            }
        }
        return handled;
    }

    @Override
    public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
        // 缩放限制沿用公共相机，避免极端输入破坏画布。
        camera.scroll(amount);
        return true;
    }

    @Override
    public boolean onKeyPress(KeyEvent event) {
        // F 只在画布获得焦点时生效，避免抢占聊天框等其他输入组件的快捷键。
        if (event.key() == GLFW.GLFW_KEY_F && event.modifiers() == 0) {
            return focusSelected();
        }
        return super.onKeyPress(event);
    }

    boolean focusSelected() {
        // 操作浮窗也聚焦同一画布；目标隐藏或删除后不再沿用旧几何。
        BodyVisual selected = visible.stream().filter(node -> node.getId().equals(selectedId)).findFirst().orElse(null);
        if (selected == null) {
            return false;
        }
        select(selected);
        return true;
    }

    @Override
    public boolean canFocus(FocusSource source) {
        // 画布可接收焦点，切换窗口时取消未完成拖动。
        return true;
    }

    @Override
    public void onFocusLost() {
        // 防止失焦后旧鼠标释放误选新窗口内的对象。
        dragging = false;
        super.onFocusLost();
    }
}
