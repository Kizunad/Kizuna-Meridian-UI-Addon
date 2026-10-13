package dev.kizuna.meridian.ui.client;

/** 展示层错误集中定义，身体请求仍使用框架 Protobuf 失败码。 */
public final class UiErrors {
    public static final String TUTORIAL_SAVE = "无法保存吐纳指引已读偏好";
    public static final String GATHERING_UNAVAILABLE = "聚气连接不可用，请重新连接或刷新。";

    public static String gatheringFailure(dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode code) {
        // 失败原因同时指向调整入口，不通过服务端异常文案猜测业务状态。
        return switch (code) {
            case CAPACITY_EXCEEDED -> "器官或目标经脉空间不足，请切换目标。";
            case INSUFFICIENT_QI -> "所选原料不足，请切换配方或更换修炼地点。";
            case ENVIRONMENT_BUDGET_EXCEEDED -> "区域供给已被占用，请稍后重试。";
            case ENVIRONMENT_UNAVAILABLE, ENVIRONMENT_INTERVAL_INVALID -> "当前环境无法供给本轮灵气，请刷新后重试。";
            case MODULE_UNAVAILABLE, ROUTE_UNAVAILABLE -> "器官或通路失能，请查看对应部位状态。";
            case MERIDIAN_UNOPENED -> "源经脉尚未开拓，不能主动抽调真元。";
            case REVISION_CONFLICT -> "身体状态已变化，请刷新并重新选择。";
            case ORGAN_BUSY, GATHERING_BUSY -> "已有任务正在执行，请等待完成。";
            case THROUGHPUT_EXCEEDED -> "通路吞吐已被占用，请稍后重试。";
            case PENDING_TIME_EVENT, TIME_REQUIRES_SETTLEMENT -> "已有身体事件待结算，请稍后刷新。";
            case ACCESS_DENIED -> "当前没有操作这具身体的权限。";
            case BODY_DEAD -> "身体已经死亡，无法继续聚气。";
            case RECIPE_INCOMPATIBLE, RECIPE_NOT_FOUND -> "配方已不可用，请刷新选项。";
            default -> "聚气未完成：" + code.name();
        };
    }
    public static final String INVALID_PRESENTATION = "身体模型资源无效：";
    public static final String INVALID_BREATHING_PRESENTATION = "吐纳展示的表层或气流入口缺失";
    public static final String INVALID_ANIMATION_BONE = "动画挂点不存在：";
    public static final String MISSING_BODY = "等待宿主提供身体快照与模型资源";
    public static final String MISSING_TARGET = "此对象已不在当前身体中";
    public static final String RESOURCE_SIZE = "资源超过 2 MiB 或为空";
    public static final String DECODE_FAILED = "Protobuf 解码失败";
    public static final String MISSING_HEADER = "版本、模板或包围盒缺失";
    public static final String INVALID_BOUNDS = "包围盒范围必须为正";
    public static final String INVALID_LAYERS = "展示层缺失或重复";
    public static final String INVALID_COUNT = "展示层或节点数量不合法";
    public static final String INVALID_NODE = "节点身份、身体引用或颜色不合法：";
    public static final String INVALID_MESH = "三角网格为空或不完整：";
    public static final String INVALID_TEXTURE = "贴图资源身份或 UV 无效：";
    public static final String OUTSIDE_BOUNDS = "顶点超出整体包围盒：";
    public static final String INVALID_INDEX = "三角面引用越界：";
    public static final String INVALID_COORDINATE = "坐标非有限或超出模型范围";
    public static final String TEMPLATE_MISMATCH = "模型与身体模板版本不匹配";
    public static final String STALE_BODY = "身体身份变更或快照版本倒退";
    public static final String WRONG_THREAD = "身体展示必须在客户端线程更新";
    public static final String WINDOW_OPEN = "更换身体或模型前请关闭身体与修炼窗口";
    public static final String RESOURCE_UNREADABLE = "无法读取模型资源 ";
    public static final String INVALID_UPDATE = "身体更新上下文不匹配";

    private UiErrors() {
        // 只存放展示错误，不另建服务端失败码。
    }

    public static IllegalArgumentException invalid(String detail) {
        // 保留资源定位信息，调用方显示后仍可重新加载修正后的资源。
        return new IllegalArgumentException(INVALID_PRESENTATION + detail);
    }
}
