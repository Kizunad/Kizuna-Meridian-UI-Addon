package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 整体保护：资源边界与非人体内容映射，防止 UI 偷偷依赖人体命名。 */
public class BodyPresentationTest {
    @Test
    public void meridianSummaryUsesSnapshotWithoutInterpretingAddonConditions() {
        // 非人构型只按权威库存、容量和开拓进度展示，条件由内容包发布卡片。
        var body = BodySnapshot.newBuilder().setStructure(BodyStructure.newBuilder()
                        .addMeridians(MeridianDefinition.newBuilder().setId("tip").setLumenCapacity(8)))
                .addMeridianStates(MeridianState.newBuilder().setId("tip").setCondition(MeridianCondition.INTACT)
                        .setLumenQi(QiInventory.newBuilder().addEntries(Qi.newBuilder().setAmount(4)))
                        .setOpening(MeridianOpening.newBuilder().setStage(MeridianOpeningStage.IN_PROGRESS).setProgress(.25))).build();
        var summary = OpeningCards.summary(body, "tip");
        assertEquals(.5, meter(summary, "storage").getFraction(), 0);
        assertEquals(.25, meter(summary, "opening").getFraction(), 0);
        assertEquals(OperationMeterState.METER_READY, meter(summary, "condition").getState());
    }

    private static MeridianOperationMeter meter(MeridianOperationView view, String id) {
        // 回归断言针对公开卡片含义，不依赖绘制实现或具体文字数值格式。
        return view.getMetersList().stream().filter(item -> item.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void addonOperationsKeepNamespacesRevisionsAndUnloadSeparate() {
        // 两个附属共用同一目标；重复注册、过期数据和卸载不得影响另一方。
        var registry = new MeridianOperations();
        var requests = new java.util.ArrayList<MeridianOperationRequest>();
        Runnable remove = registry.register("first", requests::add);
        registry.register("second", requests::add);
        assertThrows(IllegalArgumentException.class, () -> registry.register("first", requests::add));
        var body = BodySnapshot.newBuilder().setId("body").setRevision(4)
                .addMeridianStates(MeridianState.newBuilder().setId("tendril")).build();
        for (String namespace : java.util.List.of("first", "second")) {
            registry.update(body, MeridianOperationUpdate.newBuilder().setProtocolVersion(1).setNamespace(namespace)
                    .setBodyId("body").setBodyRevision(4).addOperations(MeridianOperationView.newBuilder()
                            .setId(namespace + ":guide").setVersion(1).setMeridianId("tendril")
                            .setTitle("引导").setActionId("start").setEnabled(true)).build());
        }
        assertEquals(2, registry.views(body, "tendril").size());
        assertTrue(registry.views(body, "tendril").stream().allMatch(MeridianOperationView::getEnabled));
        assertTrue(registry.views(body.toBuilder().setRevision(5).build(), "tendril").stream().noneMatch(MeridianOperationView::getEnabled));
        assertThrows(IllegalArgumentException.class, () -> registry.update(body,
                MeridianOperationUpdate.newBuilder().setProtocolVersion(1).setNamespace("first").setBodyId("body").setBodyRevision(3).build()));
        registry.send(MeridianOperationRequest.newBuilder().setOperationId("second:guide").build());
        assertEquals(1, requests.size());
        remove.run();
        assertEquals("second:guide", registry.views(body, "tendril").get(0).getId());
        registry.clear();
        assertTrue(registry.views(body, "tendril").isEmpty());
    }

    @Test
    public void sharedQiProjectionDoesNotCountReservationsTwice() {
        // 到账、外壁、器官输入、缓存和在途各有唯一账户，原料与真元不会偷换统计口径。
        var inventory = QiInventory.newBuilder().addEntries(Qi.newBuilder().setAmount(2)).build();
        var body = BodySnapshot.newBuilder().setStructure(BodyStructure.newBuilder()
                        .addMeridians(MeridianDefinition.newBuilder().setId("channel").addPorts(Port.newBuilder().setId("in")))
                        .addConnections(Connection.newBuilder().setId("route").setTo("in")))
                .addMeridianStates(MeridianState.newBuilder().setId("channel").setLumenQi(inventory).setWallQi(inventory))
                .addOrganStates(OrganState.newBuilder().setBuffer(inventory).setProcess(OrganProcess.newBuilder()
                        .setRecipe(OrganRecipe.newBuilder().setInput(inventory))))
                .addTransits(QiTransit.newBuilder().setConnectionId("route").setCargo(inventory)).build();
        assertEquals(2, QiDisplay.lumen(body), 0);
        assertEquals(2, QiDisplay.incoming(body, "channel"), 0);
        assertEquals(10, QiDisplay.total(body), 0);
    }

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

        // 生物内容声明自己的展示层级；缺省保持可见，未解锁节点不能进入选择列表。
        assertTrue(BodyScene.realmAllows(body, node));
        BodyVisual gated = node.toBuilder().setMinimumRealm(Realm.MERIDIAN_CONDENSATION).build();
        assertFalse(BodyScene.realmAllows(body.toBuilder().setRealm(Realm.AWAKENING).build(), gated));
        assertTrue(BodyScene.realmAllows(body.toBuilder().setRealm(Realm.MERIDIAN_CONDENSATION).build(), gated));
        assertTrue(BodyScene.realmAllows(body.toBuilder().setRealm(Realm.VOID_TRANSFORMATION).build(), gated));

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
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, valid.getVisuals(0).toBuilder().setMinimumRealmValue(999)).build().toByteArray()));

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

        // 默认层必须真实存在；未声明的旧资源仍从首层进入。
        assertEquals("crystals", new BodyScene(valid.toByteArray()).defaultLayerId());
        var layered = valid.toBuilder().addLayers(PresentationLayer.newBuilder().setId("outside").setDisplayName("外层"))
                .setDefaultLayerId("outside");
        assertEquals("outside", new BodyScene(layered.build().toByteArray()).defaultLayerId());
        assertThrows(IllegalArgumentException.class,
                () -> new BodyScene(layered.clone().setDefaultLayerId("missing").build().toByteArray()));

        // 皮肤变体共享三角索引，缺失、越界或非有限坐标不能等到切换臂型时才暴露。
        var skin = PlayerSkinAppearance.newBuilder().addAllSlimVertices(textured.getVerticesList())
                .addAllSlimTextureUv(textured.getTextureUvList());
        new BodyScene(valid.toBuilder().setVisuals(0, textured.clone().setPlayerSkin(skin)).build().toByteArray());
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, textured.clone().setPlayerSkin(skin.clone().clearSlimVertices())).build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, textured.clone().setPlayerSkin(skin.clone()
                        .setSlimVertices(0, ModelPoint.newBuilder().setX(2)))).build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> new BodyScene(valid.toBuilder()
                .setVisuals(0, textured.clone().setPlayerSkin(skin.clone()
                        .setSlimTextureUv(0, ModelUv.newBuilder().setU(Float.NaN)))).build().toByteArray()));
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
