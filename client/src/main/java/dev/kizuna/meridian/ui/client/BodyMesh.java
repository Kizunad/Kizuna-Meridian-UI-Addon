package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** 窗口持有的静态网格；顶点、贴图坐标和几何明暗只算一次，每帧按相机矩阵与本次颜色写入提交缓冲。 */
final class BodyMesh {
    private final float[] positions;
    private final float[] uvs;
    private final int[] shades;
    private final Identifier texture;
    private final boolean playerSkin;

    BodyMesh(BodyVisual visual, boolean useTexture) {
        // 几何和法线来自已校验内容；三角形逐角展开，绘制时不再查索引或创建临时向量。
        texture = useTexture && !visual.getTextureResource().isEmpty() ? Identifier.parse(visual.getTextureResource()) : null;
        playerSkin = useTexture && visual.hasPlayerSkin();
        int corners = visual.getTrianglesCount();
        positions = new float[corners * 3];
        uvs = texture == null ? null : new float[corners * 2];
        shades = new int[corners];
        Vector3f normal = new Vector3f();
        for (int index = 0; index < corners; index += 3) {
            ModelPoint a = visual.getVertices(visual.getTriangles(index));
            ModelPoint b = visual.getVertices(visual.getTriangles(index + 1));
            ModelPoint c = visual.getVertices(visual.getTriangles(index + 2));
            normal.set(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ())
                    .cross(c.getX() - a.getX(), c.getY() - a.getY(), c.getZ() - a.getZ());
            float light = .65f;
            if (normal.lengthSquared() > .000000001f) {
                light += .35f * Math.abs(normal.normalize().dot(.25f, .65f, .7f));
            }
            // 玩家皮肤已经包含完整颜色；不再把模型法线的灰度光照乘到皮肤贴图上。
            // 解剖贴图仍使用几何明暗，让不同表面保持立体层次。
            int shade = playerSkin ? 255 : (int) (255 * light);
            for (int corner = index; corner < index + 3; corner++) {
                int vertexIndex = visual.getTriangles(corner);
                ModelPoint point = visual.getVertices(vertexIndex);
                positions[corner * 3] = point.getX();
                positions[corner * 3 + 1] = point.getY();
                positions[corner * 3 + 2] = point.getZ();
                if (uvs != null) {
                    ModelUv uv = visual.getTextureUv(vertexIndex);
                    uvs[corner * 2] = uv.getU();
                    uvs[corner * 2 + 1] = uv.getV();
                }
                shades[corner] = shade;
            }
        }
    }

    RenderType depthTested() {
        // 检视模式测试深度；贴图网格每帧取当前贴图，换皮肤后下一帧即生效。
        if (texture == null) {
            return BodyRenderTypes.TRIANGLES_DEPTH;
        }
        // 皮肤身份始终来自游戏当前玩家，不读取内容包上传的 URL 或另建网络下载器。
        Minecraft client = Minecraft.getInstance();
        Identifier currentTexture = texture;
        if (playerSkin && client.player != null) {
            currentTexture = client.player.getSkin().body().texturePath();
        }
        return BodyRenderTypes.textured(currentTexture);
    }

    void emit(VertexConsumer buffer, Matrix4fc matrix, int color, float brightness, int alpha) {
        // 颜色、亮度和透明度属于本次绘制，与几何明暗一起乘进顶点颜色；超过满亮的部分截到满值。
        float red = (color >> 16 & 255) / 255f * brightness;
        float green = (color >> 8 & 255) / 255f * brightness;
        float blue = (color & 255) / 255f * brightness;
        for (int corner = 0; corner < shades.length; corner++) {
            int shade = shades[corner];
            buffer.addVertex(matrix, positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2]);
            if (uvs != null) {
                buffer.setUv(uvs[corner * 2], uvs[corner * 2 + 1]);
            }
            buffer.setColor(channel(shade, red), channel(shade, green), channel(shade, blue), alpha);
        }
    }

    private static int channel(int shade, float factor) {
        return Math.min(255, Math.round(shade * factor));
    }
}
