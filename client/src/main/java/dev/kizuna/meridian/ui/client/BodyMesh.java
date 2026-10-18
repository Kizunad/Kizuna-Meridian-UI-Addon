package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 窗口持有的静态网格；顶点只上传一次，相机和高亮通过着色器更新。 */
final class BodyMesh implements AutoCloseable {
    private final VertexBuffer vertices = new VertexBuffer(VertexBuffer.Usage.STATIC);
    private final Identifier texture;
    private final boolean playerSkin;

    BodyMesh(BodyVisual visual, boolean useTexture) {
        // 几何和法线来自已校验内容；静态模型不在每帧创建矩阵转换临时对象。
        texture = useTexture && !visual.getTextureResource().isEmpty() ? new Identifier(visual.getTextureResource()) : null;
        playerSkin = useTexture && visual.hasPlayerSkin();
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        VertexFormat format = texture == null ? VertexFormats.POSITION_COLOR : VertexFormats.POSITION_TEXTURE_COLOR;
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, format);
        Vector3f normal = new Vector3f();
        for (int index = 0; index < visual.getTrianglesCount(); index += 3) {
            ModelPoint a = visual.getVertices(visual.getTriangles(index));
            ModelPoint b = visual.getVertices(visual.getTriangles(index + 1));
            ModelPoint c = visual.getVertices(visual.getTriangles(index + 2));
            normal.set(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ())
                    .cross(c.getX() - a.getX(), c.getY() - a.getY(), c.getZ() - a.getZ());
            float light = .65f;
            if (normal.lengthSquared() > .000000001f) {
                light += .35f * Math.abs(normal.normalize().dot(.25f, .65f, .7f));
            }
            int shade = (int) (255 * light);
            int vertexColor = playerSkin ? 255 : shade;
            for (int corner = 0; corner < 3; corner++) {
                int vertexIndex = visual.getTriangles(index + corner);
                ModelPoint point = visual.getVertices(vertexIndex);
                buffer.vertex(point.getX(), point.getY(), point.getZ());
                if (texture != null) {
                    ModelUv uv = visual.getTextureUv(vertexIndex);
                    buffer.texture(uv.getU(), uv.getV());
                }
                // 玩家皮肤已经包含完整颜色；不再把模型法线的灰度光照乘到皮肤贴图上。
                // 解剖贴图仍使用几何明暗，让不同表面保持立体层次。
                buffer.color(vertexColor, vertexColor, vertexColor, 255).next();
            }
        }
        vertices.bind();
        vertices.upload(buffer.end());
        VertexBuffer.unbind();
    }

    void draw(Matrix4f matrix, int color, float brightness, int alpha) {
        // 颜色和透明度属于本次绘制，改变选择或肺部大小无需重新上传顶点。
        if (texture == null) {
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            // 皮肤身份始终来自游戏当前玩家，不读取内容包上传的 URL 或另建网络下载器。
            MinecraftClient client = MinecraftClient.getInstance();
            Identifier currentTexture = texture;
            if (playerSkin && client.player != null) {
                currentTexture = client.player.getSkinTexture();
            }
            RenderSystem.setShaderTexture(0, currentTexture);
        }
        RenderSystem.setShaderColor((color >> 16 & 255) / 255f * brightness,
                (color >> 8 & 255) / 255f * brightness, (color & 255) / 255f * brightness, alpha / 255f);
        vertices.bind();
        // GUI 的全局视图含深度平移，静态缓冲需像普通顶点管线一样叠加它，避免落到裁剪面外。
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix);
        vertices.draw(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    @Override
    public void close() {
        // 显存随所属窗口释放，重开窗口不会积累静态缓冲。
        vertices.close();
    }
}
