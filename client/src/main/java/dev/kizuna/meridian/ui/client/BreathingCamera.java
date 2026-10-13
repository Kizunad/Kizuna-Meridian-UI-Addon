package dev.kizuna.meridian.ui.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 三维吐纳相机；投影、鼠标反投影与粒子共用同一坐标变换。 */
final class BreathingCamera {
    private double yaw = -.2;
    private double pitch = .10;
    private double distance = 3.6;

    Matrix4f projection(float aspect) {
        // 远近大小由真实深度产生，旋转后仍保持一致。
        return new Matrix4f().perspective((float) Math.toRadians(45), aspect, .05f, 30);
    }

    Matrix4f view() {
        // 焦点略低于口鼻，让盘坐身体与上方气流同时可见。
        Vector3f focus = new Vector3f(0, -.35f, 0);
        Vector3f eye = new Vector3f((float) (Math.sin(yaw) * Math.cos(pitch) * distance),
                (float) (Math.sin(pitch) * distance), (float) (Math.cos(yaw) * Math.cos(pitch) * distance));
        eye.add(focus);
        return new Matrix4f().lookAt(eye, focus, new Vector3f(0, 1, 0));
    }

    Vector3f project(Vector3f world, float aspect) {
        // 归一化画布位置和裁剪深度也供真实鼠标验收使用。
        Vector3f clip = projection(aspect).mul(view()).transformProject(new Vector3f(world));
        return new Vector3f((clip.x + 1) * .5f, (1 - clip.y) * .5f, clip.z);
    }

    Vector3f target(double mouseX, double mouseY, float aspect) {
        // 射线与经过口鼻的视平面相交，再按可见粒子的真实深度细化瞄准。
        Matrix4f inverse = projection(aspect).mul(view()).invert();
        float clipX = (float) (mouseX * 2 - 1);
        float clipY = (float) (1 - mouseY * 2);
        Vector3f near = inverse.transformProject(new Vector3f(clipX, clipY, -1));
        Vector3f far = inverse.transformProject(new Vector3f(clipX, clipY, 1));
        Vector3f ray = far.sub(near, new Vector3f()).normalize();
        Vector3f normal = view().invert().transformDirection(new Vector3f(0, 0, 1));
        float denominator = ray.dot(normal);
        if (Math.abs(denominator) < .0001f) {
            return new Vector3f(0, 0, 1);
        }
        float along = -near.dot(normal) / denominator;
        return near.add(ray.mul(along));
    }

    void orbit(double dx, double dy) {
        // 拖动只改变观察角度，俯仰限制防止越过极点翻转。
        yaw += dx * .008;
        pitch = Math.max(-.8, Math.min(.95, pitch + dy * .008));
    }

    void zoom(double amount) {
        // 最近距离限制避免镜头钻入身体或跨过近裁剪面。
        distance = Math.max(2.2, Math.min(6, distance * Math.exp(-amount * .09)));
    }

    static Vector3f world(double x, double y, double z) {
        // 口鼻是原点；协议 Y 向下而模型 Y 向上，三轴保持相同尺度。
        return new Vector3f((float) ((x - .5) * 4), (float) ((.4 - y) * 4), (float) ((z - .5) * 4));
    }

    static Vector3f local(Vector3f world) {
        // 目标限制在观察区内；上传方向，不报告命中或摄入量。
        return new Vector3f(clamp(.5f + world.x / 4), clamp(.4f - world.y / 4), clamp(.5f + world.z / 4));
    }

    private static float clamp(float coordinate) {
        // 反投影可能落到视窗外，协议坐标保持合法。
        return Math.max(0, Math.min(1, coordinate));
    }
}
