package dev.kizuna.meridian.ui.client;

import dev.kizuna.playeranimation.animation.AnimationPlayer;
import dev.kizuna.playeranimation.client.PlayerAnimationClient;
import dev.kizuna.playeranimation.client.PlayerAssets;
import dev.kizuna.playeranimation.model.BoneId;
import dev.kizuna.playeranimation.model.Rig;
import dev.kizuna.playeranimation.model.SkinnedMesh;
import net.minecraft.client.render.BufferBuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 可选动画模组的只读预览适配；独立播放器不会改变外世界玩家或触发玩法事件。 */
final class MeditationAvatar {
    private final PlayerAssets.Model model;
    private final AnimationPlayer player = new AnimationPlayer();
    private Matrix4f[] bones;
    private Matrix4f[] attachments;
    private final SkinnedMesh.Vertex[] vertices;
    private final float[] positions;
    private final int[] shades;
    private double sampledPhase = Double.NaN;
    private float minimumY;
    private final Vector3f point = new Vector3f();
    private final Vector3f normal = new Vector3f();

    private MeditationAvatar(PlayerAssets.Model model, String animation) {
        // 复用动画侧的已加载索引与网格，姿态和权重不复制到经脉仓库。
        this.model = model;
        vertices = model.mesh().surfaces().stream().filter(surface -> !surface.outer())
                .flatMap(surface -> surface.vertices().stream()).toArray(SkinnedMesh.Vertex[]::new);
        positions = new float[vertices.length * 3];
        shades = new int[vertices.length];
        player.play(PlayerAnimationClient.ASSETS.animations().get(animation), 0, false);
        sample(0, false);
    }

    static MeditationAvatar create(String animation) {
        // 资源可能正在重载或没有指定动作；调用方保留可用的静态内容模型。
        PlayerAssets.Model model = PlayerAnimationClient.ASSETS.model(false);
        if (model == null || !PlayerAnimationClient.ASSETS.animations().containsKey(animation)) {
            return null;
        }
        return new MeditationAvatar(model, animation);
    }

    void sample(double airRatio, boolean exhaling) {
        // 动画呼吸相位跟随实际空气条，不能一边显示满肺、一边继续自动吸气。
        double phase = exhaling ? 6 - airRatio * 3.6 : airRatio * 2.4;
        if (Math.abs(phase - sampledPhase) < .002) {
            return;
        }
        sampledPhase = phase;
        bones = Rig.matrices(model.skeleton(), player.sample(Rig.zeroPose(), phase + 6, false));
        attachments = new Matrix4f[bones.length];
        for (int index = 0; index < bones.length; index++) {
            // 动画的正面是 -Z，内容模型是 +Z；共轭变换同时对齐器官挂点与入口。
            attachments[index] = new Matrix4f().rotateY((float) Math.PI).scale(1f / 16)
                    .mul(bones[index]).scale(16).rotateY((float) -Math.PI);
        }

        // 一次蒙皮同时准备绘制顶点与地面高度，静止姿态直接复用结果。
        minimumY = Float.POSITIVE_INFINITY;
        for (int index = 0; index < vertices.length; index++) {
            SkinnedMesh.Vertex vertex = vertices[index];
            SkinnedMesh.deform(vertex.position(), vertex.weights(), bones, false, point);
            SkinnedMesh.deform(vertex.normal(), vertex.weights(), bones, true, normal);
            positions[index * 3] = -point.x / 16;
            positions[index * 3 + 1] = point.y / 16;
            positions[index * 3 + 2] = -point.z / 16;
            shades[index] = (int) (19 + 25 * Math.abs(-normal.x * .4 + normal.y * .3 - normal.z * .8));
            minimumY = Math.min(minimumY, point.y / 16);
        }
    }

    Matrix4f attachment(String bone) {
        // 内容模型用米，动画骨矩阵用像素；明确换算后让内脏随胸廓一起移动。
        BoneId attachment = java.util.Arrays.stream(BoneId.ALL).filter(value -> value.key.equals(bone))
                .findFirst().orElseThrow(() -> UiErrors.invalid(UiErrors.INVALID_ANIMATION_BONE + bone));
        return attachments[attachment.ordinal()];
    }

    float minimumY() {
        // 地面跟随实际盘坐姿态的最低点，不能用站姿包围盒定位底座。
        return minimumY;
    }

    void draw(BufferBuilder buffer, Matrix4f matrix) {
        // 灰黑半透明材质只改变显示；法线明暗留下关节和盘腿轮廓。
        for (int index = 0; index < vertices.length; index++) {
            matrix.transformPosition(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2], point);
            int shade = shades[index];
            buffer.vertex(point.x, point.y, point.z).color(shade, shade + 4, shade + 8, 185).next();
        }
    }
}
