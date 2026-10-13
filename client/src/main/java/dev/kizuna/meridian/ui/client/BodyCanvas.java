package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kizuna.inventoryui.client.component.ModelPreviewCamera;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.render.*;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
    private List<BodyVisual> visible = List.of();
    private final Map<String, List<LitFace>> faces = new HashMap<>();
    private final Map<String, Identifier> textures = new HashMap<>();
    private String selectedId = "";
    private long previousFrame;
    private double draggedPixels;
    private boolean dragging;

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
        cacheGeometry(scene);
    }

    private void cacheGeometry(BodyScene scene) {
        // 静态模型的法线亮度只算一次；相机逐帧插值无需反复创建向量和贴图标识。
        for (BodyVisual node : scene.presentation().getVisualsList()) {
            var litFaces = new ArrayList<LitFace>();
            for (int index = 0; index < node.getTrianglesCount(); index += 3) {
                int first = node.getTriangles(index);
                int second = node.getTriangles(index + 1);
                int third = node.getTriangles(index + 2);
                ModelPoint a = node.getVertices(first), b = node.getVertices(second), c = node.getVertices(third);
                Vector3f normal = new Vector3f(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ())
                        .cross(c.getX() - a.getX(), c.getY() - a.getY(), c.getZ() - a.getZ());
                float light = 0.65f;
                if (normal.lengthSquared() > 0.000000001f) {
                    normal.normalize();
                    light += 0.35f * Math.abs(normal.dot(0.25f, 0.65f, 0.7f));
                }
                litFaces.add(new LitFace(first, second, third, light));
            }
            faces.put(node.getId(), List.copyOf(litFaces));
            if (!node.getTextureResource().isEmpty()) {
                textures.put(node.getId(), new Identifier(node.getTextureResource()));
            }
        }
    }

    public void visible(List<BodyVisual> nodes) {
        // 更新几何时保留仍然存在的高亮，避免无关部位改造打断当前观察。
        visible = List.copyOf(nodes);
        if (visible.stream().noneMatch(node -> node.getId().equals(selectedId))) {
            selectedId = "";
        }
    }

    public void select(BodyVisual node) {
        // 从网格顶点计算焦点，UI 不需要任何人类部位坐标表。
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
        // 插值只控制镜头；节点清单在快照或切层时更新，不逐帧运行身体计算。
        long now = System.nanoTime();
        if (previousFrame != 0) {
            camera.advance(Math.min(0.1f, (now - previousFrame) / 1_000_000_000f));
        }
        previousFrame = now;
        context.fill(x, y, x + width, y + height, BACKGROUND);
        context.draw();
        context.enableScissor(x, y, x + width, y + height);
        Matrix4f matrix = new Matrix4f(context.getMatrices().peek().getPositionMatrix())
                .translate(x, y, 150).mul(modelTransform());
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        try {
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            for (BodyVisual node : visible) {
                // 每个对象选择其内容包材质；纯色对象继续使用原有无贴图绘制路径。
                Identifier texture = textures.get(node.getId());
                boolean textured = texture != null;
                if (textured) {
                    RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
                    RenderSystem.setShaderTexture(0, texture);
                    buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
                } else {
                    RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                    buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
                }
                // 选中时降低其他对象亮度，密集交织的线状模型仍能看清完整主干与支路。
                int color = node.getColorRgb();
                float emphasis = 1;
                if (node.getId().equals(selectedId)) {
                    color = SELECTED;
                } else if (!selectedId.isEmpty()) {
                    emphasis = 0.4f;
                }
                for (LitFace face : faces.get(node.getId())) {
                    float brightness = face.light() * emphasis;
                    drawVertex(buffer, matrix, node, face.first(), textured, color, brightness);
                    drawVertex(buffer, matrix, node, face.second(), textured, color, brightness);
                    drawVertex(buffer, matrix, node, face.third(), textured, color, brightness);
                }
                BufferRenderer.drawWithGlobalProgram(buffer.end());
            }
        } finally {
            // 画布退出后恢复 UI 所需渲染状态，避免影响旁边的文字和窗口。
            RenderSystem.enableCull();
            RenderSystem.disableDepthTest();
            context.disableScissor();
        }
    }

    private static void drawVertex(BufferBuilder buffer, Matrix4f matrix, BodyVisual node,
                                   int index, boolean textured, int color, float brightness) {
        // UV 和命中使用同一份顶点；高亮乘在原贴图上，保留肌束与器官表面细节。
        ModelPoint vertex = node.getVertices(index);
        buffer.vertex(matrix, vertex.getX(), vertex.getY(), vertex.getZ());
        if (textured) {
            ModelUv uv = node.getTextureUv(index);
            buffer.texture(uv.getU(), uv.getV());
        }
        buffer.color((int) (((color >> 16) & 255) * brightness),
                (int) (((color >> 8) & 255) * brightness), (int) ((color & 255) * brightness), 255).next();
    }

    private record LitFace(int first, int second, int third, float light) {}

    public BodyVisual pick(double mouseX, double mouseY) {
        // 对投影后的真实三角面求重心坐标，选择最靠近观察者的可见面。
        Matrix4f matrix = modelTransform();
        BodyVisual hit = null;
        float nearestDepth = Float.NEGATIVE_INFINITY;
        for (BodyVisual node : visible) {
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
