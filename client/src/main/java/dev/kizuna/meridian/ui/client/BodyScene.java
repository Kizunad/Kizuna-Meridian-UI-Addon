package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 已校验的只读展示资源；数值始终来自宿主身体快照。 */
public final class BodyScene {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final BodyPresentation presentation;

    public BodyScene(byte[] payload) {
        // 先限制输入大小，再检查几何与引用身份，防止坏资源进入渲染矩阵。
        if (payload == null || payload.length > MAX_BYTES) {
            throw UiErrors.invalid(UiErrors.RESOURCE_SIZE);
        }
        try {
            presentation = BodyPresentation.parseFrom(payload);
        } catch (InvalidProtocolBufferException failure) {
            throw UiErrors.invalid(UiErrors.DECODE_FAILED);
        }
        if (presentation.getSchemaVersion() != 1 || presentation.getTemplate().getId().isBlank()
                || presentation.getTemplate().getVersion() <= 0 || !presentation.hasMinimum() || !presentation.hasMaximum()) {
            throw UiErrors.invalid(UiErrors.MISSING_HEADER);
        }
        ModelPoint minimum = presentation.getMinimum();
        ModelPoint maximum = presentation.getMaximum();
        finite(minimum);
        finite(maximum);
        if (minimum.getX() >= maximum.getX() || minimum.getY() >= maximum.getY() || minimum.getZ() >= maximum.getZ()) {
            throw UiErrors.invalid(UiErrors.INVALID_BOUNDS);
        }
        Set<String> layers = new HashSet<>();
        for (PresentationLayer layer : presentation.getLayersList()) {
            if (layer.getId().isBlank() || layer.getDisplayName().isBlank() || !layers.add(layer.getId())) {
                throw UiErrors.invalid(UiErrors.INVALID_LAYERS);
            }
        }
        if (layers.isEmpty() || presentation.getVisualsCount() == 0 || presentation.getVisualsCount() > 2048) {
            throw UiErrors.invalid(UiErrors.INVALID_COUNT);
        }
        Set<String> identities = new HashSet<>();
        for (BodyVisual node : presentation.getVisualsList()) {
            if (node.getId().isBlank() || !identities.add(node.getId()) || node.getDisplayName().isBlank()
                    || !layers.contains(node.getLayerId()) || node.getTargetId().isBlank()
                    || node.getTargetKind() == BodyTargetKind.BODY_TARGET_KIND_UNSPECIFIED
                    || node.getTargetKind() == BodyTargetKind.UNRECOGNIZED || (node.getColorRgb() & 0xff000000) != 0) {
                throw UiErrors.invalid(UiErrors.INVALID_NODE + node.getId());
            }
            if (node.getVerticesCount() < 3 || node.getTrianglesCount() == 0 || node.getTrianglesCount() % 3 != 0) {
                throw UiErrors.invalid(UiErrors.INVALID_MESH + node.getId());
            }
            for (ModelPoint vertex : node.getVerticesList()) {
                finite(vertex);
                if (vertex.getX() < minimum.getX() || vertex.getX() > maximum.getX()
                        || vertex.getY() < minimum.getY() || vertex.getY() > maximum.getY()
                        || vertex.getZ() < minimum.getZ() || vertex.getZ() > maximum.getZ()) {
                    throw UiErrors.invalid(UiErrors.OUTSIDE_BOUNDS + node.getId());
                }
            }
            for (int index : node.getTrianglesList()) {
                if (index < 0 || index >= node.getVerticesCount()) {
                    throw UiErrors.invalid(UiErrors.INVALID_INDEX + node.getId());
                }
            }
            validateTexture(node);
        }
        if (presentation.hasBreathing()) {
            // 入口属于内容模型，先校验所在层与坐标；动画挂点交给对应动画适配器确认。
            BreathingPresentation breathing = presentation.getBreathing();
            if (!layers.contains(breathing.getSurfaceLayer()) || !breathing.hasIntakePoint()) {
                throw UiErrors.invalid(UiErrors.INVALID_BREATHING_PRESENTATION);
            }
            finite(breathing.getIntakePoint());
        }
    }

    private static void validateTexture(BodyVisual node) {
        // 只允许资源包内的 PNG 和逐顶点 UV，禁止远程 URL、路径跳转与不完整贴图坐标。
        if (node.getTextureResource().isEmpty()) {
            if (node.getTextureUvCount() != 0) {
                throw UiErrors.invalid(UiErrors.INVALID_TEXTURE + node.getId());
            }
            return;
        }
        String resource = node.getTextureResource();
        if (!resource.matches("[a-z0-9_.-]+:textures/[a-z0-9_./-]+\\.png") || resource.contains("..")
                || node.getTextureUvCount() != node.getVerticesCount()) {
            throw UiErrors.invalid(UiErrors.INVALID_TEXTURE + node.getId());
        }
        for (ModelUv uv : node.getTextureUvList()) {
            if (!Float.isFinite(uv.getU()) || !Float.isFinite(uv.getV())
                    || uv.getU() < 0 || uv.getU() > 1 || uv.getV() < 0 || uv.getV() > 1) {
                throw UiErrors.invalid(UiErrors.INVALID_TEXTURE + node.getId());
            }
        }
    }

    private static void finite(ModelPoint point) {
        // 模型坐标有限且有界，避免极端缩放让命中和渲染失去精度。
        if (!Float.isFinite(point.getX()) || !Float.isFinite(point.getY()) || !Float.isFinite(point.getZ())
                || Math.abs(point.getX()) > 1000 || Math.abs(point.getY()) > 1000 || Math.abs(point.getZ()) > 1000) {
            throw UiErrors.invalid(UiErrors.INVALID_COORDINATE);
        }
    }

    public BodyPresentation presentation() {
        // Protobuf 消息不可变，调用方不能直接修改已校验内容。
        return presentation;
    }

    public List<BodyVisual> layer(String layerId) {
        // 层身份由内容包提供，不通过名称猜测人体组织。
        return presentation.getVisualsList().stream().filter(node -> node.getLayerId().equals(layerId)).toList();
    }

    public void requireTemplate(BodySnapshot body) {
        // 精确模板修订匹配后才允许把模型 ID 用于快照查询。
        if (!body.getTemplate().equals(presentation.getTemplate())) {
            throw UiErrors.invalid(UiErrors.TEMPLATE_MISMATCH);
        }
    }

    public static boolean exists(BodySnapshot body, BodyVisual node) {
        // 改造后的身体可能删除对象；失去绑定的模型不可显示为仍然健康的部位。
        String id = node.getTargetId();
        return switch (node.getTargetKind()) {
            case PART -> body.getStructure().getPartsList().stream().anyMatch(value -> value.getId().equals(id));
            case TISSUE -> body.getStructure().getTissuesList().stream().anyMatch(value -> value.getId().equals(id));
            case ORGAN -> body.getStructure().getOrgansList().stream().anyMatch(value -> value.getId().equals(id));
            case MERIDIAN -> body.getStructure().getMeridiansList().stream().anyMatch(value -> value.getId().equals(id));
            default -> false;
        };
    }
}
