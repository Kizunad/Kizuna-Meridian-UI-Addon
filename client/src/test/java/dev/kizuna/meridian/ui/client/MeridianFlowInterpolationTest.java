package dev.kizuna.meridian.ui.client;

import java.util.List;
import org.joml.Vector3f;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 闭环光迹只能追上权威进度，重复快照和下一圈不能造成倒放或提前到关。 */
public final class MeridianFlowInterpolationTest {
    private static final long HALF_FRAME = 125_000_000;
    private static final long FULL_FRAME = 250_000_000;
    private static final long LATER = 1_000_000_000;
    private static final double FIRST_TARGET = 0.8;
    private static final double NEXT_LAP_TARGET = 0.1;
    private static final float EPSILON = 0.00001f;

    @Test
    public void repeatedSnapshotDoesNotRestartMotionOrExtrapolate() {
        // 追上最新位置后即使长时间没有推送，也不能继续向前生成不存在的运输。
        var flight = flight();
        flight.retarget(FIRST_TARGET, 0);
        float halfway = flight.point(HALF_FRAME, 0).x;
        assertTrue(halfway > 0 && halfway < FIRST_TARGET);
        flight.retarget(FIRST_TARGET, HALF_FRAME);
        assertEquals(FIRST_TARGET, flight.point(FULL_FRAME, 0).x, EPSILON);
        assertEquals(FIRST_TARGET, flight.point(LATER, 0).x, EPSILON);
    }

    @Test
    public void newLapStartsAtOriginInsteadOfTraversingBackwards() {
        // 权威工作进度归零后从新圈起点展示，不把旧圈末端反向扫回去。
        var flight = flight();
        flight.retarget(FIRST_TARGET, 0);
        flight.retarget(NEXT_LAP_TARGET, FULL_FRAME);
        float position = flight.point(FULL_FRAME + HALF_FRAME, 0).x;
        assertTrue(position >= 0 && position < NEXT_LAP_TARGET);
        assertEquals(NEXT_LAP_TARGET, flight.point(LATER, 0).x, EPSILON);
    }

    private static MeridianFlow.Flight flight() {
        // 单位长度直线便于直接检查图像位置，仍使用正式光迹插值实现。
        var path = List.of(new Vector3f(0, 0, 0), new Vector3f(1, 0, 0));
        return new MeridianFlow.Flight(path, 0, 1, 0);
    }
}
