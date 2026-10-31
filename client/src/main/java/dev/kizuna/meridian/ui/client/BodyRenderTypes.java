package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * 身体画布在画中画纹理里使用的材质：顶点色或贴图乘顶点色，半透明混合，不剔除背面。
 *
 * <p>深度只在画布自己的画中画深度缓冲里比较。画中画把深度清为 0、越近的深度值越大，所以测试用
 * {@code GREATER_THAN_OR_EQUAL}；不测深度的材质按提交顺序叠加。
 */
final class BodyRenderTypes {
    private static final Optional<DepthStencilState> TEST_AND_WRITE =
            Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true));
    private static final Optional<DepthStencilState> TEST_ONLY =
            Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false));

    /** 测试并写入深度：检视模式的身体网格。 */
    static final RenderType TRIANGLES_DEPTH = colored("triangles_depth", PrimitiveTopology.TRIANGLES, TEST_AND_WRITE);
    /** 测试并写入深度：打坐人物表层。 */
    static final RenderType QUADS_DEPTH = colored("quads_depth", PrimitiveTopology.QUADS, TEST_AND_WRITE);
    /** 只测试不写入：吐纳粒子与气流，被身体遮挡，彼此不遮挡。 */
    static final RenderType TRIANGLES_TESTED = colored("triangles_tested", PrimitiveTopology.TRIANGLES, TEST_ONLY);
    /** 不测深度：经脉窗口的表层与经脉、吐纳底座与器官。 */
    static final RenderType TRIANGLES_FLAT = colored("triangles_flat", PrimitiveTopology.TRIANGLES, Optional.empty());
    /** 不测深度：路线叠层、流动光点与环形标记。 */
    static final RenderType QUADS_FLAT = colored("quads_flat", PrimitiveTopology.QUADS, Optional.empty());

    private static final RenderPipeline TEXTURED_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("textured_triangles_depth"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withDepthStencilState(TEST_AND_WRITE)
                    .withCull(false)
                    .build());
    private static final Map<Identifier, RenderType> TEXTURED = new HashMap<>();

    private BodyRenderTypes() {
        // 只放材质常量，不实例化。
    }

    static RenderType textured(Identifier texture) {
        // 每张贴图一个材质：解剖贴图来自内容包，皮肤来自当前玩家，种类有限，按需建一次后复用。
        return TEXTURED.computeIfAbsent(texture, key -> RenderType.create("meridian_ui_body_textured",
                RenderSetup.builder(TEXTURED_PIPELINE).withTexture("Sampler0", key).createRenderSetup()));
    }

    private static RenderType colored(String name, PrimitiveTopology topology, Optional<DepthStencilState> depth) {
        // 顶点色管线沿用原版调试填充片段（位置 + 颜色、半透明、不剔除），只换图元和深度设置。
        RenderPipeline pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(id(name))
                .withPrimitiveTopology(topology)
                .withDepthStencilState(depth)
                .build());
        return RenderType.create("meridian_ui_body_" + name, RenderSetup.builder(pipeline).createRenderSetup());
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("meridian_ui", "pipeline/body_" + name);
    }
}
