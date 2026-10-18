package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.inventoryui.client.component.ModelPreviewCamera;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.render.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import io.wispforest.owo.ui.util.ScissorStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 通用三角网格画布，复用 Inventory UI 的相机，实现身体对象的精确面选取。 */
public final class BodyCanvas extends BaseComponent {
    private static final int BACKGROUND = 0xFF131F25;
    private static final int SELECTED = 0xF6D888;
    private static final float DEPTH_SCALE = 0.1f;
    private static final double CLICK_DISTANCE_PIXELS = 4;
    private final Consumer<BodyVisual> selection;
    private final Runnable showOverview;
    private final ModelPreviewCamera camera = new ModelPreviewCamera();
    private final Box bounds;
    private List<BodyVisual> sourceVisible = List.of();
    private List<BodyVisual> visible = List.of();
    private boolean slimSkin;
    private final Map<String, BodyMesh> meshes = new HashMap<>();
    private String selectedId = "";
    private long previousFrame;
    private double draggedPixels;
    private boolean dragging;
    private MeridianFlow flow;
    private final Map<String, Double> openingProgress = new HashMap<>();
    private final Map<String, Double> storedFraction = new HashMap<>();
    private final Map<String, Float> shownBrightness = new HashMap<>();
    private SimpleFramebuffer openingFramebuffer;

    public BodyCanvas(BodyScene scene, Consumer<BodyVisual> selection, Runnable showOverview) {
        // 包围盒由生物提供；旋转、缩放和聚焦均使用同一个模型坐标系。
        this.selection = selection;
        this.showOverview = showOverview;
        ModelPoint minimum = scene.presentation().getMinimum();
        ModelPoint maximum = scene.presentation().getMaximum();
        bounds = new Box(minimum.getX(), minimum.getY(), minimum.getZ(), maximum.getX(), maximum.getY(), maximum.getZ());
        sizing(Sizing.fill(100), Sizing.fill(100));
        camera.focus(new Vec3d(0.5, 0.5, 0.5), 1, 0, 0, false);
        camera.settle();
    }

    public void dispose() {
        // 只有实际看过的对象持有 GPU 缓冲，窗口关闭时统一释放。
        meshes.values().forEach(BodyMesh::close);
        meshes.clear();
        if (openingFramebuffer != null) {
            openingFramebuffer.delete();
            openingFramebuffer = null;
        }
    }

    public void opening(BodyScene scene, BodySnapshot body) {
        // 经脉窗口复用站姿几何；颜色只读取服务端开拓进度，不按本地时间判成功。
        if (flow == null) {
            flow = new MeridianFlow(scene);
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
                    .filter(item -> item.getId().equals(state.getId())).mapToDouble(MeridianDefinition::getLumenCapacity)
                    .findFirst().orElse(0);
            storedFraction.put(state.getId(), QiDisplay.ratio(QiDisplay.amount(state.getLumenQi()), capacity));
        }
        flow.update(body);
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
        float zoom = (float) Math.min(4, Math.max(1.2, bounds.getYLength() * 0.5 / Math.max(0.05, extent)));
        Vec3d normalized = new Vec3d((center.x - bounds.minX) / bounds.getXLength(),
                (center.y - bounds.minY) / bounds.getYLength(), (center.z - bounds.minZ) / bounds.getZLength());
        // 自动转向模型所在的前后侧，避免选中背部肌肉后仍被正面几何遮住。
        float yaw = center.z < -0.02f ? 180 : 0;
        camera.focus(normalized, zoom, yaw, 0, false);
    }

    public void overview() {
        // 返回全身只改变相机与高亮，身体状态不受影响。
        selectedId = "";
        camera.focus(new Vec3d(0.5, 0.5, 0.5), 1, 0, 0, false);
    }

    public ModelPreviewCamera camera() {
        // 暴露已有相机供无动画偏好及交互验收使用。
        return camera;
    }

    private void refreshAppearance() {
        // Minecraft 自身管理皮肤加载；臂型变化时才重建几何，普通帧复用原有 GPU 缓冲。
        MinecraftClient client = MinecraftClient.getInstance();
        boolean currentSlim = client.player != null && "slim".equals(client.player.getModel());
        if (currentSlim != slimSkin) {
            slimSkin = currentSlim;
            meshes.values().forEach(BodyMesh::close);
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
        Vec3d center = camera.center(bounds);
        return new Matrix4f().translation(width / 2f, height / 2f, 0)
                .scale(scale, -scale, scale * DEPTH_SCALE)
                .rotateX((float) Math.toRadians(camera.pitch())).rotateY((float) Math.toRadians(camera.yaw()))
                .translate((float) -center.x, (float) -center.y, (float) -center.z);
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 镜头和亮度按实际帧间隔插值；节点清单随快照更新，不逐帧运行身体计算。
        refreshAppearance();
        long now = System.nanoTime();
        float elapsedSeconds = 0;
        if (previousFrame != 0) {
            elapsedSeconds = Math.min(0.1f, (now - previousFrame) / 1_000_000_000f);
            camera.advance(elapsedSeconds);
        }
        previousFrame = now;
        if (flow != null) {
            drawOpening(context, now, elapsedSeconds);
            return;
        }
        context.fill(x, y, x + width, y + height, BACKGROUND);
        context.draw();
        context.enableScissor(x, y, x + width, y + height);
        Matrix4f matrix = new Matrix4f(context.getMatrices().peek().getPositionMatrix())
                .translate(x, y, 150).mul(modelTransform());
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        try {
            for (BodyVisual node : visible) {
                // 选中时降低其他对象亮度，密集交织的线状模型仍能看清完整主干与支路。
                int color = node.getColorRgb();
                float emphasis = 1;
                if (node.getId().equals(selectedId) && !node.hasPlayerSkin()) {
                    color = SELECTED;
                } else if (!selectedId.isEmpty() && !node.getId().equals(selectedId)) {
                    emphasis = 0.4f;
                }
                // 相机矩阵直接交给 GPU，静态顶点不再逐帧复制或创建转换向量。
                BodyMesh mesh = meshes.computeIfAbsent(node.getId(), id -> new BodyMesh(node, true));
                mesh.draw(matrix, color, emphasis, 255);
            }
        } finally {
            // 画布退出后恢复 UI 所需渲染状态，避免影响旁边的文字和窗口。
            RenderSystem.enableCull();
            RenderSystem.disableDepthTest();
            context.disableScissor();
        }
    }

    private void drawOpening(OwoUIDrawContext context, long now, float elapsedSeconds) {
        // 模型使用独立深度缓冲；合成后正常绘制说明和浮窗，不让三维深度压住文字。
        context.draw();
        ScissorStack.drawUnclipped(() -> renderOpening(now, elapsedSeconds));
        context.enableScissor(x, y, x + width, y + height);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, openingFramebuffer.getColorAttachment());
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(matrix, x, y + height, 0).texture(0, 0).next();
        buffer.vertex(matrix, x + width, y + height, 0).texture(1, 0).next();
        buffer.vertex(matrix, x + width, y, 0).texture(1, 1).next();
        buffer.vertex(matrix, x, y, 0).texture(0, 1).next();
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        context.disableScissor();
    }

    private void renderOpening(long now, float elapsedSeconds) {
        // 离屏分辨率跟随窗口；静态网格上传一次，逐帧只改变相机、颜色和少量光点。
        MinecraftClient client = MinecraftClient.getInstance();
        double scale = client.getWindow().getScaleFactor();
        int pixelsWide = Math.max(1, (int) Math.ceil(width * scale));
        int pixelsHigh = Math.max(1, (int) Math.ceil(height * scale));
        if (openingFramebuffer == null) {
            openingFramebuffer = new SimpleFramebuffer(pixelsWide, pixelsHigh, true, MinecraftClient.IS_SYSTEM_MAC);
            openingFramebuffer.setClearColor(.055f, .07f, .085f, 1);
        } else if (openingFramebuffer.textureWidth != pixelsWide || openingFramebuffer.textureHeight != pixelsHigh) {
            openingFramebuffer.resize(pixelsWide, pixelsHigh, MinecraftClient.IS_SYSTEM_MAC);
        }
        openingFramebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
        openingFramebuffer.beginWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, Math.max(1, width), Math.max(1, height), 0, -1000, 1000),
                VertexSorter.BY_DISTANCE);
        var view = RenderSystem.getModelViewStack();
        view.push();
        view.loadIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            Matrix4f matrix = modelTransform();
            // 黑色透明表层先画，经脉后画，内部路线始终可见且只有经脉参与选取。
            for (BodyVisual node : visible) {
                if (node.getTargetKind() != BodyTargetKind.MERIDIAN) {
                    meshes.computeIfAbsent(node.getId(), id -> new BodyMesh(node, false)).draw(matrix, 0x030609, 1, 150);
                }
            }
            // 约 0.3 秒接近新亮度，各种帧率下都只平滑显示权威进度。
            float brightnessBlend = (float) -Math.expm1(-10 * elapsedSeconds);
            for (BodyVisual node : visible) {
                if (node.getTargetKind() != BodyTargetKind.MERIDIAN) {
                    continue;
                }
                // 储量产生很弱的底光，开拓仍决定主要亮度，未开满储量不会冒充已拓宽。
                float desired = (float) (.18 + .55 * openingProgress.getOrDefault(node.getTargetId(), 0.0)
                        + .07 * storedFraction.getOrDefault(node.getTargetId(), 0.0));
                float shown = shownBrightness.getOrDefault(node.getId(), desired);
                shown += (desired - shown) * brightnessBlend;
                shownBrightness.put(node.getId(), shown);
                if (node.getId().equals(selectedId)) {
                    shown = Math.min(1, shown + .15f);
                }
                meshes.computeIfAbsent(node.getId(), id -> new BodyMesh(node, false)).draw(matrix, 0xF4DC82, shown, 230);
            }
            // 每个在途只呈现一段短光尾；屏幕朝向的微粒仍沿三维经脉轨迹运动。
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            for (MeridianFlow.Flight flight : flow.flights()) {
                for (int tail = 3; tail >= 0; tail--) {
                    Vector3f point = matrix.transformPosition(flight.point(now, tail * .025));
                    float radius = tail == 0 ? 1.5f : .9f;
                    int alpha = 220 - tail * 48;
                    buffer.vertex(point.x - radius, point.y - radius, 0).color(255, 228, 138, alpha).next();
                    buffer.vertex(point.x + radius, point.y - radius, 0).color(255, 228, 138, alpha).next();
                    buffer.vertex(point.x + radius, point.y + radius, 0).color(255, 228, 138, alpha).next();
                    buffer.vertex(point.x - radius, point.y + radius, 0).color(255, 228, 138, alpha).next();
                }
            }
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        } finally {
            // 恢复工作台状态，后续文字与其他窗口不会继承此模型的矩阵和深度。
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            view.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            client.getFramebuffer().beginWrite(true);
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
    public boolean onMouseDown(double mouseX, double mouseY, int button) {
        // 左键点击与旋转共用入口，释放时按累计移动量区分操作。
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
    public boolean onMouseDrag(double mouseX, double mouseY, double dx, double dy, int button) {
        // 超过点击阈值后仅旋转，不在释放时额外触发选中。
        if (dragging && button == 0) {
            draggedPixels += Math.hypot(dx, dy);
            if (draggedPixels > CLICK_DISTANCE_PIXELS) {
                camera.drag(dx, dy);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseUp(double mouseX, double mouseY, int button) {
        // 命中失败保持原选择，不把背景点击映射为某个默认器官。
        boolean handled = dragging && button == 0;
        dragging = false;
        if (handled && draggedPixels <= CLICK_DISTANCE_PIXELS) {
            BodyVisual hit = pick(mouseX, mouseY);
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
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        // F 只在画布获得焦点时生效，避免抢占聊天框等其他输入组件的快捷键。
        if (keyCode == GLFW.GLFW_KEY_F && modifiers == 0) {
            return focusSelected();
        }
        return super.onKeyPress(keyCode, scanCode, modifiers);
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
