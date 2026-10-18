package dev.kizuna.meridian.ui.server;

import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Capability;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Direction;
import dev.kizuna.inventoryui.server.ServerConnection;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.ServerModuleSession;
import dev.kizuna.inventoryui.server.ServerRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.server.GatheringSession;
import dev.kizuna.meridian.server.OpeningSession;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.MeridianOpeningRequest;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;

import java.util.Map;
import java.util.function.Function;

/** 将经脉聚气协议接到 Inventory UI 公共路由；宿主负责身份绑定与事件调度。 */
public final class GatheringBridge<C> implements ServerModuleSession<C> {
    public static final String MODULE = "meridian_ui:body";
    public static final String REQUEST = "meridian_ui:gathering_request";
    public static final String RESPONSE = "meridian_ui:gathering_response";
    public static final String OPENING_REQUEST = "meridian_ui:opening_request";
    public static final String OPENING_RESPONSE = "meridian_ui:opening_response";
    private final ServerConnection<C> connection;
    private final GatheringSession<C> session;
    private final OpeningSession<C> opening;

    public GatheringBridge(ServerConnection<C> connection, GatheringSession<C> session) {
        // 会话由宿主认证后创建，不从请求中的 body_id 推导权限。
        this(connection, session, null);
    }

    public GatheringBridge(ServerConnection<C> connection, GatheringSession<C> session, OpeningSession<C> opening) {
        // 两种操作使用宿主提供的同一身体核心，桥接只路由消息，不计算资源。
        this.connection = connection;
        this.session = session;
        this.opening = opening;
    }

    public static <C> void register(ServerModuleRegistry<C> registry,
                                   Function<ServerConnection<C>, GatheringBridge<C>> factory) {
        // 直接使用生成消息的描述符，两个框架仍各自持有唯一 schema。
        registry.register(MODULE, Map.of(
                REQUEST, capability(Direction.CLIENT_TO_SERVER, GatheringRequest.getDescriptor().getFullName()),
                RESPONSE, capability(Direction.SERVER_TO_CLIENT, GatheringResponse.getDescriptor().getFullName()),
                OPENING_REQUEST, capability(Direction.CLIENT_TO_SERVER, MeridianOpeningRequest.getDescriptor().getFullName()),
                OPENING_RESPONSE, capability(Direction.SERVER_TO_CLIENT, ResponseEnvelope.getDescriptor().getFullName())), factory::apply);
    }

    private static Capability capability(Direction direction, String type) {
        // 聚气是窗口的可选能力，缺少宿主接入时仍可使用只读身体查看器。
        return Capability.newBuilder().setModule(MODULE).setVersion(1).setDirection(direction)
                .setRequired(false).setPayloadType(type).build();
    }

    @Override
    public boolean handle(ServerRequest<C> request) {
        // 公共路由已验证消息方向；聚气核心继续校验字段、身份、revision 和业务权限。
        if (request.message().equals(OPENING_REQUEST)) {
            MeridianOpeningRequest intent = request.payload(MeridianOpeningRequest.getDefaultInstance());
            if (opening == null) {
                connection.send(OPENING_RESPONSE, ResponseEnvelope.newBuilder().setProtocolVersion(1)
                        .setRequestId(intent.getRequestId()).setFailure(RequestFailure.newBuilder()
                                .setCode(FailureCode.OPENING_RULE_NOT_FOUND)).build());
            } else {
                connection.send(OPENING_RESPONSE, opening.handle(intent));
            }
            return true;
        }
        GatheringRequest intent = request.payload(GatheringRequest.getDefaultInstance());
        try {
            GatheringResponse response = GatheringResponse.parseFrom(session.handle(intent.toByteArray()));
            connection.send(RESPONSE, response);
            return true;
        } catch (com.google.protobuf.InvalidProtocolBufferException impossible) {
            throw UiServerErrors.invalidResponse(impossible);
        }
    }

    public void advance() {
        // 宿主到事件边界时调用，阶段未变化就不发送数据，避免逐帧快照与算账。
        if (session.advance()) {
            var response = GatheringResponse.newBuilder().setProtocolVersion(1).setRequestId("state");
            try {
                response.setView(session.view());
            } catch (MeridianErrors.GatheringRejected rejection) {
                // 撤权后仍能通知操作不可用，但不能把旧快照当作新的授权数据发送。
                response.setFailure(RequestFailure.newBuilder().setCode(rejection.code()));
            }
            connection.send(RESPONSE, response.build());
        }
    }

    public long nextBoundaryMillis() {
        // 返回模拟时钟边界，具体 timer、tick 或事件队列由宿主选择。
        return session.nextBoundaryMillis();
    }

    @Override
    public void close() {
        // 断线只结束自动编排；身体中已存在的加工与在途由宿主继续正常结算。
        session.close();
    }
}
