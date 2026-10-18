package dev.kizuna.meridian.ui.demo;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.server.*;
import dev.kizuna.meridian.ui.client.BodyUiModule;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** 开发演示的可信内存宿主；不打入生产客户端，不绑定或改写玩家存档。 */
final class GatheringPreviewHost {
    private static final long WINDOW_MILLIS = 30_000;
    private final EnvironmentQiRules rules = new EnvironmentQiRules(EnvironmentConfigLoader.defaults());
    private final InMemoryBodyStore store;
    private final GatheringSession<String> session;
    private final BodyUiModule module;
    private final BodyMessages.TimeCursor origin;
    private final BodyCommandHandler<String> handler;
    private final OpeningSession<String> opening;
    private final BodyId bodyId;
    private long now;

    GatheringPreviewHost(BodyMessages.BodySnapshot initial, BodyUiModule module) {
        // 示例区域只初始化一次；每次点按钮都从同一真实库存扣账。
        this.module = module;
        bodyId = new BodyId(initial.getId());
        origin = initial.getSettledAt();
        var qi = BodyMessages.QiInventory.newBuilder();
        for (String attribute : HumanContent.INPUT_ATTRIBUTES) {
            qi.addEntries(BodyMessages.Qi.newBuilder().setPhase(BodyMessages.QiPhase.FREE)
                    .setAttribute(attribute).setAmount(attribute.equals("meridian:wood") ? 60 : 10));
        }
        EnvironmentSpace space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId("preview:region")
                .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(100).build();
        EnvironmentQiSource source = EnvironmentAbsorption.initialize(space, BodyProtobuf.fromInventory(qi.build()),
                time(0), time(WINDOW_MILLIS), rules);
        store = new InMemoryBodyStore(List.of(BodyProtobuf.fromMessage(initial)), List.of(), QiInventory.empty(), List.of(source));
        OrganRecipeCatalog recipes = new OrganRecipeCatalog(HumanContent.bundle().getOrganRecipesList());
        BodyTimeSource<String> clock = (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(time(now))
                .setBiologicalSecondsPerSimulationSecond(1).setMode(TimeAdvanceMode.ACTIVE).build();
        handler = new BodyCommandHandler<>((caller, command) -> {
            // 演示只接受绑定身份与身体，不能由 UI 自选其他玩家。
            if (caller.equals("preview-owner") && command.getBodyId().equals(initial.getId())) {
                return Optional.of(caller);
            }
            return Optional.empty();
        }, (caller, command, body) -> true, clock, store,
                (caller, receipt, before, after) -> { /* 状态与资源均在提交中处理。 */ }, recipes,
                (caller, body, plan) -> Optional.of("preview:region"),
                new ActionCatalog(HumanContent.bundle().getActionsList()),
                new MeridianOpeningCatalog(HumanContent.bundle().getOpeningRulesList()));
        session = new GatheringSession<>("preview-owner", new BodyId(initial.getId()), handler, store,
                (caller, id) -> caller.equals("preview-owner") && id.value().equals(initial.getId()), clock,
                body -> store.environmentSource("preview:region"), recipes);
        session.enableBreathing(HumanContent.breathing());
        opening = new OpeningSession<>("preview-owner", new BodyId(initial.getId()), handler, store,
                (caller, id) -> caller.equals("preview-owner") && id.value().equals(initial.getId()));
        module.bindGathering(this::request);
        module.bindOpening(HumanContent.bundle().getOpeningRulesList(), request -> {
            // 序列化后的操作走框架编排；成功仍需把权威快照推回界面。
            module.receiveOpening(opening.handle(dev.kizuna.meridian.protocol.OpeningProtobuf.decodeRequest(request.toByteArray())));
            publishBody();
        });
        publishBody();
    }

    private BodyMessages.TimeCursor time(long millis) {
        // 模拟游标由演示宿主统一控制，UI 请求中不携带时间。
        return origin.toBuilder().setElapsedMillis(millis).build();
    }

    private void request(GatheringRequest request) {
        // 本地也序列化经过公开 Protobuf 入口，不能用演示绕开核心校验。
        try {
            module.receiveGathering(GatheringResponse.parseFrom(session.handle(request.toByteArray())));
            publishBody();
        } catch (IOException failure) {
            throw new IllegalStateException("演示宿主无法解码聚气响应", failure);
        }
    }

    void tick(long millis) {
        // 每 tick 只比较唤醒时间；真正计算仅发生在事件边界。
        now = millis;
        if (session.nextBoundaryMillis() != 0 && now >= session.nextBoundaryMillis()) {
            if (session.advance()) {
                module.receiveGathering(GatheringResponse.newBuilder().setProtocolVersion(1)
                        .setRequestId("state").setView(session.view()).build());
                publishBody();
            }
        }
        var body = store.find(bodyId).orElseThrow();
        long boundary = BodyTimeAdvancement.nextEventBoundary(body, 1, BodyTimeAdvancement.Mode.ACTIVE)
                .map(cursor -> cursor.elapsedMillis()).orElse(Long.MAX_VALUE);
        if (now >= boundary) {
            // 开脉在未吐纳时仍按权威事件边界推进，不以 UI 渲染帧驱动身体。
            var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(java.util.UUID.randomUUID().toString())
                    .setBodyCommand(BodyCommand.newBuilder().setBodyId(body.id().value()).setExpectedRevision(body.revision())
                            .setAdvanceTime(AdvanceTime.getDefaultInstance())).build();
            var response = dev.kizuna.meridian.protocol.MeridianProtocol.decodeResponse(
                    handler.handle("preview-owner", request.toByteArray()));
            if (response.hasFailure()) {
                throw new IllegalStateException("演示推进失败：" + response.getFailure().getCode());
            }
            publishBody();
        }
        EnvironmentQiSource source = store.environmentSource("preview:region").orElseThrow();
        if (now >= source.getValidUntil().getElapsedMillis()) {
            // 单身体演示没有其他旧窗消费者；续窗按剩余浓度计算，不补满环境库存。
            store.renewEnvironmentWindow("preview:region", source.getRevision(), time(now), time(now + WINDOW_MILLIS), rules);
        }
    }

    GatheringView view() {
        // 游戏内验收读权威结果，不能靠动画结束判断是否已经入账。
        return session.view();
    }

    void publishBody() {
        // 演示也先同步权威快照，再同步内容包的进度卡，不在客户端复制人体判断。
        var body = view().getBody();
        module.update(body);
        module.updateMeridianOperations(dev.kizuna.meridian.human.server.HumanOpeningViews.update(
                body, HumanContent.bundle().getOpeningRulesList()));
    }

    double conservedQi() {
        // 默认释放账独立统计，避免把同一份损耗同时算进区域和释放账。
        return BodyProtobuf.fromMessage(session.view().getBody()).totalQi() + store.environmentQi().totalQi()
                + BodyProtobuf.fromInventory(store.environmentSource("preview:region").orElseThrow().getInventory()).totalQi();
    }
}
