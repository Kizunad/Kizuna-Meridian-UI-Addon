package dev.kizuna.meridian.ui.server;

/** 桥接异常集中定义，业务拒绝复用经脉 Protobuf 失败码。 */
final class UiServerErrors {
    private UiServerErrors() {
        // 无状态错误工厂不需要实例。
    }

    static IllegalStateException invalidResponse(Throwable cause) {
        // 核心输出损坏属于宿主故障，不伪装成玩家参数错误。
        return new IllegalStateException("经脉核心返回了无效的聚气响应", cause);
    }
}
