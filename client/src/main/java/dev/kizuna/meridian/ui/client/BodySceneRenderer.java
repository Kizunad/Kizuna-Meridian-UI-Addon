package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fc;

/**
 * 身体画布的画中画渲染器：画布把三维场景提交到自己的纹理，再由 GUI 渲染器贴回窗口。
 *
 * <p>原版每种状态类只登记一个渲染器、只有一张纹理，同一帧打开的多个身体窗口会互相覆盖。所以每个画布持有自己的
 * 实例，入口登记的那一份只把状态转交给提交它的画布。
 */
final class BodySceneRenderer extends PictureInPictureRenderer<BodySceneRenderer.State> {
    private final Projection perspective = new Projection();
    private final ProjectionMatrixBuffer perspectiveBuffer = new ProjectionMatrixBuffer("meridian_ui body scene");

    @Override
    public void prepare(State state, GuiRenderState guiRenderState, FeatureRenderDispatcher dispatcher, int guiScale) {
        // 登记的实例不画任何东西，纹理和深度缓冲都属于提交状态的画布。
        if (state.target() != this) {
            state.target().prepare(state, guiRenderState, dispatcher, guiScale);
            return;
        }
        super.prepare(state, guiRenderState, dispatcher, guiScale);
    }

    @Override
    protected void renderToTexture(State state, PoseStack matrices, SubmitNodeCollector collector) {
        int width = state.x1() - state.x0();
        int height = state.y1() - state.y0();
        if (!state.perspective()) {
            // 基础矩阵已把原点移到纹理中心、按 GUI 缩放放大并翻转 Z；这里翻回 Z 并移回左上角，
            // 场景就按画布 GUI 坐标提交：x 向右、y 向下、z 越大越近，与旧的离屏正交投影一致。
            matrices.scale(1, 1, -1);
            matrices.translate(-width / 2f, -height / 2f, 0);
            state.scene().submit(matrices, collector);
            return;
        }
        // 透视场景直接提交相机空间顶点。换成本画布的透视投影后，随后绘制提交内容时沿用它；
        // Projection 按画中画的深度方向反转远近平面，GUI 渲染器之后会恢复自己的投影。
        perspective.setupPerspective(BreathingCamera.NEAR, BreathingCamera.FAR, BreathingCamera.FOV_DEGREES, width, height);
        RenderSystem.setProjectionMatrix(perspectiveBuffer.getBuffer(perspective), ProjectionType.PERSPECTIVE);
        state.scene().submit(new PoseStack(), collector);
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        // 默认原点在纹理底边；与 X 一样放在中心，renderToTexture 再移回左上角。
        return height / 2f;
    }

    @Override
    protected String getTextureLabel() {
        return "meridian_ui body scene";
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    public void close() {
        // 纹理、深度缓冲与透视投影缓冲随所属画布一起释放。
        super.close();
        perspectiveBuffer.close();
    }

    /** 画布的一帧场景：在画布 GUI 坐标（透视时为相机空间）里提交几何，可用提交顺序分出先后绘制的层。 */
    @FunctionalInterface
    interface Scene {
        void submit(PoseStack pose, SubmitNodeCollector collector);
    }

    /** 一帧提交：画布 GUI 坐标 (x0, y0)–(x1, y1)，target 为画布自己的渲染器，perspective 选择吐纳相机的透视投影。 */
    record State(
            BodySceneRenderer target,
            Scene scene,
            boolean perspective,
            int x0,
            int y0,
            int x1,
            int y1,
            Matrix3x2fc pose,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds)
            implements PictureInPictureRenderState {
        static State of(BodySceneRenderer target, Scene scene, boolean perspective, int x0, int y0, int x1, int y1,
                Matrix3x2fc pose, ScreenRectangle scissorArea) {
            // 外框按提交时的 GUI 矩阵变换再与裁剪求交；GUI 渲染器据此决定层叠，无外框时不绘制。
            var bounds = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
            if (scissorArea != null) {
                bounds = scissorArea.intersection(bounds);
            }
            return new State(target, scene, perspective, x0, y0, x1, y1, pose, scissorArea, bounds);
        }

        @Override
        public float scale() {
            // 场景自己决定模型缩放，纹理按 GUI 像素一比一铺满画布。
            return 1;
        }
    }
}
