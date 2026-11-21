package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import dev.kizuna.meridian.ui.api.InspectionSection;
import java.util.function.BooleanSupplier;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.PresentationLayer;
import dev.kizuna.meridian.progression.client.ClientProgression;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** XML 持有组件树，Java 按内容尺寸排布并绑定生物资源与选择状态。 */
public final class ModernBodyPanel {
    private static final int FULL = 100;
    private static final int PADDING = 3;
    private static final int GAP = 2;
    private static final int TITLE_HEIGHT = 12;
    private static final int HINT_HEIGHT = 9;
    private static final int LAYER_ENTRY_WIDTH = 28;
    private static final int LAYER_BAR_HEIGHT = 12;
    private static final int INFORMATION_RIGHT_PADDING = 5;
    private final BodyScene scene;
    private final InspectionSection section;
    private final List<String> allowedLayers;
    private final MeridianSceneExtension extension = MeridianSceneExtension.required();
    private BodySnapshot body;
    private final FlowLayout root;
    private final FlowLayout content;
    private final LabelComponent information;
    private final BodyCanvas canvas;
    private final Set<String> visibleLayers = new LinkedHashSet<>();
    private BodyVisual selected;
    private List<BodyVisual> visible = List.of();
    private List<net.minecraft.network.chat.Component> summary = List.of();
    private int informationWidth;

    ModernBodyPanel(BodyScene scene, BodySnapshot body, InspectionSection section,
              BooleanSupplier overviewOpen, Runnable toggleOverview) {
        // 模板校验先于窗口创建；加载唯一 XML 模板，不在代码中另建平行布局。
        scene.requireTemplate(body);
        this.scene = scene;
        this.section = section;
        allowedLayers = extension.inspectionLayers(section);
        this.body = body;
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "body_modern"))
                .expandTemplate(FlowLayout.class, "body-panel", Map.of());
        information = content.childById(LabelComponent.class, "information");
        canvas = new BodyCanvas(scene, this::select, this::overview);
        canvas.bodyEffects(body);
        content.childById(FlowLayout.class, "canvas").child(canvas);
        FlowLayout layers = content.childById(FlowLayout.class, "layers");
        for (PresentationLayer layer : scene.presentation().getLayersList()) {
            if (!allowedLayers.contains(layer.getId())) {
                continue;
            }
            var checkbox = new ModernLayerToggle(layer.getDisplayName(), () -> visibleLayers.contains(layer.getId()),
                    () -> setLayerVisible(layer.getId(), !visibleLayers.contains(layer.getId()))).compact();
            checkbox.id("layer-toggle-" + layer.getId());
            layers.child(checkbox);
        }
        if (section == InspectionSection.SURFACE) {
            var overview = new ModernLayerToggle("概况", overviewOpen, toggleOverview).compact();
            overview.id("overview-toggle");
            layers.child(overview);
        }
        root = new ResponsiveLayout(content, this::arrange);
        layer(allowedLayers.getFirst());
    }

    private void arrange(int width, int height) {
        // 先给标题与说明留位，再从实际余下宽度分配区域，避免百分比列加上侧栏后溢出。
        MeridianTheme.apply(content);
        int innerWidth = Math.max(1, width - PADDING * 2);
        int top = PADDING + TITLE_HEIGHT;
        int bottom = Math.max(top + 1, height - PADDING - HINT_HEIGHT - GAP);
        int layerEntryWidth = Math.min(LAYER_ENTRY_WIDTH, Math.max(1, innerWidth));
        MeridianPanel.place(content.childById(UIComponent.class, "body-title"), PADDING, PADDING,
                Math.max(1, innerWidth - layerEntryWidth - GAP), TITLE_HEIGHT);
        MeridianPanel.place(content.childById(UIComponent.class, "body-hint"), PADDING,
                Math.max(PADDING, height - PADDING - HINT_HEIGHT), innerWidth, HINT_HEIGHT);

        FlowLayout layers = content.childById(FlowLayout.class, "layers");
        layers.sizing(Sizing.fill(FULL), Sizing.fixed(LAYER_BAR_HEIGHT));
        MeridianPanel.place(content.childById(UIComponent.class, "layers-scroll"),
                PADDING + innerWidth - layerEntryWidth, PADDING, layerEntryWidth, LAYER_BAR_HEIGHT);
        for (UIComponent toggle : layers.children()) {
            toggle.sizing(Sizing.fixed(28), Sizing.fixed(LAYER_BAR_HEIGHT));
        }

        int detailWidth = Math.max(1, (innerWidth - GAP) * 58 / 100);
        int modelWidth = Math.max(1, innerWidth - GAP - detailWidth);
        int areaHeight = Math.max(1, bottom - top);
        MeridianPanel.place(content.childById(UIComponent.class, "canvas"), PADDING, top, modelWidth, areaHeight);
        MeridianPanel.place(content.childById(UIComponent.class, "information-scroll"), PADDING + modelWidth + GAP,
                top, detailWidth, areaHeight);
        informationWidth = Math.max(1, detailWidth - INFORMATION_RIGHT_PADDING);
        information.maxWidth(informationWidth);
        information.sizing(Sizing.fixed(informationWidth), Sizing.content());
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

    public boolean vitalitySummaryFitsInformationWidth() {
        // 年龄和寿元各占一行，必须在详情标签的实际宽度内，避免渲染器自动拆开数值。
        int availableWidth = informationWidth;
        return availableWidth > 0 && summary.stream()
                .limit(3)
                .allMatch(line -> Minecraft.getInstance().font.width(line) <= availableWidth);
    }

    public String summaryText() {
        // 客户端验收读取实际标签内容，确保年龄和寿元的数值没有被裁掉。
        var text = new StringBuilder();
        for (int index = 0; index < summary.size(); index++) {
            if (index > 0) {
                text.append('\n');
            }
            text.append(summary.get(index).getString());
        }
        return text.toString();
    }

    public void layer(String id) {
        // 宿主可以指定单层初始视图；玩家的勾选操作使用独立增删入口。
        if (!allowedLayers.contains(id) || !id.equals(scene.defaultLayerId()) && !ClientProgression.canInspect()) {
            return;
        }
        visibleLayers.clear();
        visibleLayers.add(id);
        refreshObjects();
        overview();
    }

    public void setLayerVisible(String id, boolean enabled) {
        // 勾选只影响渲染集合；相机、各层坐标和其他开关不会随之改变。
        if (enabled && !id.equals(scene.defaultLayerId()) && !ClientProgression.canInspect()) {
            return;
        }
        if (!allowedLayers.contains(id)) {
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
        // 多层合并后使用真实深度遮挡；移除或尚未解锁的对象不参与显示和命中。
        visible = scene.presentation().getVisualsList().stream()
                .filter(node -> visibleLayers.contains(node.getLayerId()))
                .filter(node -> BodyScene.realmAllows(body, node))
                .filter(node -> BodyScene.exists(body, node)).toList();
        canvas.visible(visible);
    }

    public void overview() {
        // 中键复位同时清除相机焦点、高亮和旧对象信息，保留当前勾选组合。
        selected = null;
        canvas.overview();
        showInformation();
    }

    /** 摘要里有不跟身体快照走的行（负重读数），每个客户端 tick 看一次，变了才重排文字。 */
    public void tick() {
        if (section == InspectionSection.SURFACE && !extension.surfaceSummaryLines(body).equals(summary)) {
            showInformation();
        }
    }

    private void showInformation() {
        // 普通状态更新只替换文字，让玩家已调整的镜头与选择保持稳定。
        var text = net.minecraft.network.chat.Component.empty();
        if (section == InspectionSection.SURFACE) {
            summary = extension.surfaceSummaryLines(body);
        }
        content.childById(LabelComponent.class, "body-title")
                .text(net.minecraft.network.chat.Component.literal(title()));
        for (var line : summary) {
            text.append(line).append("\n");
        }
        text.append("\n");
        if (selected == null) {
            var rows = extension.inspectionRows(section, body);
            information.text(text.append(String.join("\n", rows)));
            return;
        }
        // 每行各带自己的悬浮说明，拼成一段文字交给同一个标签。
        var lines = BodyInformation.describe(body, selected);
        for (int index = 0; index < lines.size(); index++) {
            text.append(lines.get(index));
            if (index < lines.size() - 1) {
                text.append("\n");
            }
        }
        information.text(text);
    }

    private String title() {
        return switch (section) {
            case SURFACE -> "检视 · 体表";
            case MUSCLES -> "检视 · 肌肉";
            case ORGANS -> "检视 · 器官";
        };
    }

    public void select(BodyVisual node) {
        // 同一个选择同时驱动高亮、相机与信息，避免三个状态分别漂移。
        if (!visibleLayers.contains(node.getLayerId()) || !BodyScene.exists(body, node)
                || !BodyScene.realmAllows(body, node)) {
            return;
        }
        selected = node;
        canvas.select(node);
        showInformation();
    }

    public void update(BodySnapshot snapshot) {
        // 开脉叠层共用权威状态，是否显示仍由玩家的经脉层勾选决定。
        canvas.bodyEffects(snapshot);
        // 同一身体按版本递增替换显示；结构或境界变化都可能改变可选节点。
        scene.requireTemplate(snapshot);
        if (!body.getId().equals(snapshot.getId()) || snapshot.getRevision() < body.getRevision()) {
            throw UiErrors.invalid(UiErrors.STALE_BODY);
        }
        boolean structureChanged = !body.getStructure().equals(snapshot.getStructure());
        boolean realmChanged = body.getRealm() != snapshot.getRealm();
        body = snapshot;
        if (structureChanged || realmChanged) {
            refreshObjects();
        }
        if (selected != null && (!BodyScene.exists(body, selected) || !BodyScene.realmAllows(body, selected))) {
            overview();
            return;
        }
        showInformation();
    }
}
