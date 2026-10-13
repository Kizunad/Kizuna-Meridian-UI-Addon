package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** XML 持有静态布局，Java 只绑定生物资源和选择状态。 */
public final class BodyPanel {
    private final BodyScene scene;
    private BodySnapshot body;
    private final FlowLayout root;
    private final LabelComponent information;
    private final BodyCanvas canvas;
    private final Set<String> visibleLayers = new LinkedHashSet<>();
    private BodyVisual selected;
    private List<BodyVisual> visible = List.of();

    public BodyPanel(BodyScene scene, BodySnapshot body) {
        // 模板校验先于窗口创建；加载唯一 XML 模板，不在代码中另建平行布局。
        scene.requireTemplate(body);
        this.scene = scene;
        this.body = body;
        root = UIModelLoader.get(new Identifier("meridian_ui", "body"))
                .expandTemplate(FlowLayout.class, "body-panel", Map.of());
        information = root.childById(LabelComponent.class, "information");
        canvas = new BodyCanvas(scene, this::select, this::overview);
        root.childById(FlowLayout.class, "canvas").child(canvas);
        FlowLayout layers = root.childById(FlowLayout.class, "layers");
        for (PresentationLayer layer : scene.presentation().getLayersList()) {
            var checkbox = new LayerToggle(layer.getDisplayName(), () -> visibleLayers.contains(layer.getId()),
                    () -> setLayerVisible(layer.getId(), !visibleLayers.contains(layer.getId())));
            checkbox.id("layer-toggle-" + layer.getId());
            layers.child(checkbox);
        }
        layer(scene.presentation().getLayers(0).getId());
    }

    public FlowLayout root() {
        // 由 Inventory UI 窗口持有组件生命周期。
        return root;
    }

    public BodyCanvas canvas() {
        // 交互验收使用与玩家操作相同的画布。
        return canvas;
    }

    public String selectedId() {
        // 选择仅存在于当前窗口，不写回身体快照。
        return selected == null ? "" : selected.getTargetId();
    }

    public void layer(String id) {
        // 宿主可以指定单层初始视图；玩家的勾选操作使用独立增删入口。
        visibleLayers.clear();
        visibleLayers.add(id);
        refreshObjects();
        overview();
    }

    public void setLayerVisible(String id, boolean enabled) {
        // 勾选只影响渲染集合；相机、各层坐标和其他开关不会随之改变。
        if (scene.presentation().getLayersList().stream().noneMatch(layer -> layer.getId().equals(id))) {
            return;
        }
        if (enabled) {
            visibleLayers.add(id);
        } else {
            visibleLayers.remove(id);
        }
        refreshObjects();
        if (selected != null && !visibleLayers.contains(selected.getLayerId())) {
            selected = null;
        }
        showInformation();
    }

    public Set<String> visibleLayers() {
        // 外部只能读取副本，渲染集合只能通过受控入口改变。
        return Set.copyOf(visibleLayers);
    }

    private void refreshObjects() {
        // 多层合并后使用真实深度遮挡；已移除对象不再参与显示和命中。
        visible = scene.presentation().getVisualsList().stream()
                .filter(node -> visibleLayers.contains(node.getLayerId()))
                .filter(node -> BodyScene.exists(body, node)).toList();
        canvas.visible(visible);
    }

    public void overview() {
        // 中键复位同时清除相机焦点、高亮和旧对象信息，保留当前勾选组合。
        selected = null;
        canvas.overview();
        showInformation();
    }

    private void showInformation() {
        // 普通状态更新只替换文字，让玩家已调整的镜头与选择保持稳定。
        if (selected == null) {
            information.text(Text.literal("身体信息\n\n点击模型查看部位介绍、血量与能力。\n\n左侧可同时勾选多个层。\n外层会自然遮挡内部，取消勾选即可逐层查看。\n\n可见对象：" + visible.size()));
            return;
        }
        information.text(Text.literal(String.join("\n", BodyInformation.lines(body, selected))));
    }

    public void select(BodyVisual node) {
        // 同一个选择同时驱动高亮、相机与信息，避免三个状态分别漂移。
        if (!visibleLayers.contains(node.getLayerId()) || !BodyScene.exists(body, node)) {
            return;
        }
        selected = node;
        canvas.select(node);
        showInformation();
    }

    public void update(BodySnapshot snapshot) {
        // 同一身体按版本递增替换显示；结构变化时重建可选节点并重新检查选择。
        scene.requireTemplate(snapshot);
        if (!body.getId().equals(snapshot.getId()) || snapshot.getRevision() < body.getRevision()) {
            throw UiErrors.invalid(UiErrors.STALE_BODY);
        }
        boolean structureChanged = !body.getStructure().equals(snapshot.getStructure());
        body = snapshot;
        if (structureChanged) {
            refreshObjects();
        }
        if (selected != null && !BodyScene.exists(body, selected)) {
            overview();
            return;
        }
        showInformation();
    }
}
