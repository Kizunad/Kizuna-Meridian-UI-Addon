package dev.kizuna.meridian.ui.api;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import java.util.List;

/** 生物附属提供开拓方向、蒙皮挂点及预警余量，UI 不解释人体策略。 */
public interface OpeningEffectProvider {
    String ENTRYPOINT = "meridian_ui_opening_effects";

    List<Node> route(BodyVisual visual);

    /** 距维持阈值的最小占比余量；0 表示正好位于阈值。 */
    double holdMargin(BodySnapshot body);

    /** 原模型坐标及两骨线性混合，坐姿关节附近的光迹随同一骨架变形。 */
    record Node(float x, float y, float z, String bone, String adjacentBone, float blend) { }
}
