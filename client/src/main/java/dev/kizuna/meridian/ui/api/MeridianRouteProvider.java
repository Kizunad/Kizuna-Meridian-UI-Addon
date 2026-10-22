package dev.kizuna.meridian.ui.api;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Connection;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyPresentation;
import java.util.List;

/** 客户端光迹扩展；通过同名 Fabric entrypoint 装载，不参与网络通信或身体结算。 */
@FunctionalInterface
public interface MeridianRouteProvider {
    String ENTRYPOINT = "meridian_ui_routes";

    /**
     * 返回沿实际连接 from → to 排列的中间点，UI 衔接两端网格。
     * 必须返回 2～256 个有限且在模型包围盒内的点；无法提供路线时抛出异常。
     * 客户端必须装配唯一提供者；实现只读取传入数据，不保存身体状态。
     */
    List<Point> waypoints(BodyPresentation model, BodyStructure structure, Connection connection);

    /** 模型局部坐标；使用不可变值，避免扩展修改已缓存的光迹。 */
    record Point(float x, float y, float z) {}
}
