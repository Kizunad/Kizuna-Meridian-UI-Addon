package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import java.util.*;
import java.util.function.Consumer;

/** 连接内操作目录；每个 Addon 独立发布卡片，UI 仅发送有版本的操作意图。 */
final class MeridianOperations {
    private final Map<String, Consumer<MeridianOperationRequest>> senders = new LinkedHashMap<>();
    private final Map<String, MeridianOperationUpdate> updates = new LinkedHashMap<>();

    Runnable register(String namespace, Consumer<MeridianOperationRequest> sender) {
        // 命名空间不可覆盖，卸载句柄只能撤销自己登记的实例。
        if (namespace == null || !namespace.matches("[a-z0-9_.-]+") || sender == null || senders.containsKey(namespace)) {
            throw UiErrors.invalid(UiErrors.INVALID_OPERATION);
        }
        senders.put(namespace, sender);
        return () -> {
            // 连接重建后旧句柄不能误删新注册。
            if (senders.get(namespace) == sender) {
                senders.remove(namespace);
                updates.remove(namespace);
            }
        };
    }

    void update(BodySnapshot body, MeridianOperationUpdate update) {
        // 先验证整包再替换；错误包不能清空其他附属，也不能接收过期身体的操作。
        String namespace = update.getNamespace();
        if (body == null || update.getProtocolVersion() != 1 || !senders.containsKey(namespace)
                || !update.getBodyId().equals(body.getId()) || !update.hasBodyRevision()
                || update.getBodyRevision() != body.getRevision() || update.getOperationsCount() > 128
                || update.getSerializedSize() > 262144) {
            throw UiErrors.invalid(UiErrors.INVALID_OPERATION);
        }
        Set<String> ids = new HashSet<>();
        for (MeridianOperationView operation : update.getOperationsList()) {
            if (!operation.getId().startsWith(namespace + ":") || !ids.add(operation.getId())
                    || operation.getVersion() <= 0 || operation.getTitle().isBlank() || operation.getTitle().length() > 128
                    || operation.getMetersCount() > 32 || operation.getActionId().isBlank()
                    || body.getMeridianStatesList().stream().noneMatch(state -> state.getId().equals(operation.getMeridianId()))) {
                throw UiErrors.invalid(UiErrors.INVALID_OPERATION);
            }
            Set<String> meterIds = new HashSet<>();
            for (MeridianOperationMeter meter : operation.getMetersList()) {
                if (meter.getId().isBlank() || !meterIds.add(meter.getId()) || meter.getLabel().isBlank()
                        || !fraction(meter.getFraction()) || !fraction(meter.getIncomingFraction())
                        || meter.getState() == OperationMeterState.UNRECOGNIZED) {
                    throw UiErrors.invalid(UiErrors.INVALID_OPERATION);
                }
            }
        }
        updates.put(namespace, update);
    }

    private static boolean fraction(double value) {
        // 非有限值不得进入绘制坐标。
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    List<MeridianOperationView> views(BodySnapshot body, String meridian) {
        // 旧卡片可保持位置，但不能以旧条件提交新版本请求。
        var result = new ArrayList<MeridianOperationView>();
        for (String namespace : senders.keySet()) {
            MeridianOperationUpdate update = updates.get(namespace);
            if (update == null || !update.getBodyId().equals(body.getId())) {
                continue;
            }
            for (MeridianOperationView operation : update.getOperationsList()) {
                if (operation.getMeridianId().equals(meridian)) {
                    if (update.getBodyRevision() != body.getRevision()) {
                        operation = operation.toBuilder().setEnabled(false).setStatus("等待附属刷新")
                                .setHint("身体状态已更新，等待此操作提供最新条件").build();
                    }
                    result.add(operation);
                }
            }
        }
        return List.copyOf(result);
    }

    void send(MeridianOperationRequest request) {
        // 请求只走所属附属，最终权限与条件仍由它的服务端重新核验。
        String namespace = request.getOperationId().split(":", 2)[0];
        Consumer<MeridianOperationRequest> sender = senders.get(namespace);
        if (sender == null) {
            throw UiErrors.invalid(UiErrors.INVALID_OPERATION);
        }
        sender.accept(request);
    }

    void clear() {
        // 连接退出后丢弃闭包和状态，下一连接必须重新装配能力。
        updates.clear();
        senders.clear();
    }
}
