package dev.kizuna.meridian.ui.client;

/** 展示层错误集中定义，身体请求仍使用框架 Protobuf 失败码。 */
public final class UiErrors {
    public static final String INVALID_PRESENTATION = "身体模型资源无效：";
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
    public static final String WINDOW_OPEN = "更换模型前请关闭已有身体窗口";
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
