package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 整体保护：资源边界与非人体内容映射，防止 UI 偷偷依赖人体命名。 */
public class BodyPresentationTest {
    @Test
    public void tissueDetailsShowAuthoritativeActionOccupancyAndRecovery() {
        // 用非人体组织验证通用窗口不硬编码人体动作，也不在客户端推算资源恢复。
        BodySnapshot body = BodySnapshot.newBuilder().setStructure(BodyStructure.newBuilder()
                        .addTissues(TissueDefinition.newBuilder().setId("tendril").setDisplayName("卷须")
                                .setRecoveryHalfLifeSeconds(12)))
                .addActions(ActiveAction.newBuilder().setInstanceId("grip")
                        .setDefinition(ActionDefinition.newBuilder().setDisplayName("缠绕").addTissueIds("tendril")))
                .build();
        BodyVisual node = BodyVisual.newBuilder().setTargetKind(BodyTargetKind.TISSUE).setTargetId("tendril").build();
        assertTrue(BodyInformation.lines(body, node).contains("占用：缠绕"));
        assertTrue(BodyInformation.lines(body, node).contains("空闲恢复半衰期：12.00 秒"));
        assertTrue(BodyInformation.lines(body.toBuilder().clearActions().build(), node).contains("占用：空闲"));
    }

    @Test
    public void arbitraryCreatureUsesTheSameResourceAndStateBinding() {
        // 用非人体模板和器官身份走公开契约，状态展示不依赖 Human JAR。
        BodyScene scene = new BodyScene(presentation().toByteArray());
        BodySnapshot body = BodySnapshot.newBuilder().setId("creature-instance").setTemplate(scene.presentation().getTemplate())
                .setStructure(BodyStructure.newBuilder().addOrgans(OrganDefinition.newBuilder().setId("crystal")
                        .setDisplayName("晶囊").setMaxHp(9).setQiCapacity(4).setThroughputPerSecond(2)))
                .addOrganStates(OrganState.newBuilder().setId("crystal").setHp(3)
                        .setBuffer(QiInventory.newBuilder().addEntries(Qi.newBuilder().setAmount(0.25))))
                .build();
        scene.requireTemplate(body);
        BodyVisual node = scene.layer("crystals").get(0);
        assertTrue(BodyScene.exists(body, node));
        assertTrue(BodyInformation.lines(body, node).contains("HP：3.00 / 9.00"));
        assertTrue(BodyInformation.lines(body, node).contains("缓存：0.25 / 4.00 元"));

        // 身体移除器官后停止关联，旧模型不能捏造状态；模板变更必须明确拒绝。
        assertFalse(BodyScene.exists(body.toBuilder().clearStructure().build(), node));
        assertThrows(IllegalArgumentException.class, () -> scene.requireTemplate(body.toBuilder()
                .setTemplate(TemplateReference.newBuilder().setId("other:body").setVersion(1)).build()));
    }

    @Test
    public void malformedResourcesAreRejectedBeforeRendering() {
        // 有界解码、非有限坐标、坏索引与重复身份共同保护外部模型资源入口。
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(new byte[BodyScene.MAX_BYTES + 1]));
        BodyPresentation valid = presentation();
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, valid.getVisuals(0).toBuilder().setTriangles(0, 100)).build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, valid.getVisuals(0).toBuilder().setVertices(0, ModelPoint.newBuilder().setX(Float.NaN)))
                .build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .addVisuals(valid.getVisuals(0)).build().toByteArray()));

        // 贴图是公开内容资源：完整 UV 可读取，外部 URL、缺失坐标和非有限值必须拒绝。
        var textured = valid.getVisuals(0).toBuilder().setTextureResource("crystal_beast:textures/crystal.png")
                .addTextureUv(ModelUv.newBuilder()).addTextureUv(ModelUv.newBuilder().setU(1))
                .addTextureUv(ModelUv.newBuilder().setV(1));
        new BodyScene(valid.toBuilder().setVisuals(0, textured).build().toByteArray());
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, textured.clone().clearTextureUv()).build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, textured.clone().setTextureResource("https://example.com/crystal.png")).build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, textured.clone().setTextureUv(0, ModelUv.newBuilder().setU(Float.NaN))).build().toByteArray()));
    }

    private static BodyPresentation presentation() {
        // 最小可选择三角面足以验证内容契约，不复制人体几何实现。
        return BodyPresentation.newBuilder().setSchemaVersion(1)
                .setTemplate(TemplateReference.newBuilder().setId("crystal_beast:adult").setVersion(1))
                .setMinimum(ModelPoint.newBuilder().setX(-1).setY(-1).setZ(-1))
                .setMaximum(ModelPoint.newBuilder().setX(1).setY(1).setZ(1))
                .addLayers(PresentationLayer.newBuilder().setId("crystals").setDisplayName("晶囊层"))
                .addVisuals(BodyVisual.newBuilder().setId("pouch").setDisplayName("晶囊").setLayerId("crystals")
                        .setTargetKind(BodyTargetKind.ORGAN).setTargetId("crystal").setColorRgb(0x65AABB)
                        .addVertices(ModelPoint.newBuilder().setX(-0.5f))
                        .addVertices(ModelPoint.newBuilder().setX(0.5f))
                        .addVertices(ModelPoint.newBuilder().setY(0.5f))
                        .addTriangles(0).addTriangles(1).addTriangles(2))
                .build();
    }
}
