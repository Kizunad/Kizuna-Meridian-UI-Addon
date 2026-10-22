package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import net.minecraft.util.Identifier;
import java.util.Map;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/** 站姿经脉场景；点击选中高亮，快捷键负责聚焦与权威操作。 */
public final class MeridianPanel {
    private final BodyScene scene;
    private final FlowLayout content;
    private final FlowLayout root;
    private final BodyCanvas canvas;
    private final Consumer<BodyVisual> selection;
    private final dev.kizuna.meridian.ui.api.MeridianSceneExtension.Session extension;

    MeridianPanel(BodyScene scene, BodySnapshot body, Consumer<BodyVisual> selection, Runnable operate) {
        // 普通经脉窗口只处理选脉和开脉，专属练习使用独立窗口。
        this(scene, body, selection, operate, false);
    }

    MeridianPanel(BodyScene scene, BodySnapshot body, Consumer<BodyVisual> selection, Runnable operate, boolean practice) {
        // 使用生物包的表层和经脉模型，保持站姿，不借用吐纳盘坐动画。
        this.scene = scene;
        this.selection = selection;
        content = UIModelLoader.get(new Identifier("meridian_ui", "opening"))
                .expandTemplate(FlowLayout.class, "meridian-panel", Map.of());
        content.childById(FlowLayout.class, "info").child(new MeridianInfo());
        canvas = new BodyCanvas(scene, this::clicked, () -> {
            // 中键只重置镜头和当前高亮，不改变身体状态。
            canvas().overview();
        });
        var provider = dev.kizuna.meridian.ui.api.MeridianSceneExtension.required();
        extension = practice ? provider.create(scene, canvas) : null;
        if (practice) {
            content.childById(io.wispforest.owo.ui.component.LabelComponent.class, "title")
                    .text(net.minecraft.text.Text.literal(provider.title()));
            content.child(extension.component());
            canvas.onDispose(extension::close);
        }
        canvas.keyPress().subscribe((key, scan, modifiers) -> {
            // 凝聚和暂停共用权威操作入口，按键不在客户端变更资源。
            if (extension != null && extension.keyPress(key, modifiers)) {
                return true;
            }
            if (key == GLFW.GLFW_KEY_G && modifiers == 0) {
                operate.run();
                return true;
            }
            return false;
        });
        content.childById(FlowLayout.class, "scene").child(canvas);
        root = new ResponsiveLayout(content, this::arrange);
        update(body);
    }

    private void arrange(int width, int height) {
        // 顶栏仅留下标题和圆形信息入口，快捷键收在悬浮说明中。
        place(content.childById(Component.class, "title"), 8, 6, width - 44, 18);
        place(content.childById(Component.class, "info"), width - 28, 4, 18, 18);
        place(content.childById(Component.class, "scene"), 6, 30, width - 12, height - 36);
        if (extension != null) {
            // 练习控件留出三行说明和右侧关口盘的高度，模型上半身仍保持可见。
            place(extension.component(), 14, height - 156, width - 28, 144);
        }
    }

    static void place(Component component, int x, int y, int width, int height) {
        // 内容区缩小时仍保留正尺寸，文字溢出由各自滚动区处理。
        component.positioning(Positioning.absolute(Math.max(0, x), Math.max(0, y)));
        component.sizing(Sizing.fixed(Math.max(1, width)), Sizing.fixed(Math.max(1, height)));
    }

    public void update(BodySnapshot body) {
        // 只在新快照到达时筛选现存经脉和当前境界已解锁的节点，保留用户镜头。
        String surface = scene.presentation().getBreathing().getSurfaceLayer();
        if (surface.isBlank()) {
            surface = scene.presentation().getLayers(0).getId();
        }
        String surfaceLayer = surface;
        canvas.visible(scene.presentation().getVisualsList().stream()
                .filter(node -> node.getTargetKind() == BodyTargetKind.MERIDIAN || node.getLayerId().equals(surfaceLayer))
                .filter(node -> BodyScene.realmAllows(body, node))
                .filter(node -> BodyScene.exists(body, node)).toList());
        canvas.opening(scene, body);
        if (extension != null) {
            extension.update(body);
        }
    }

    private void clicked(BodyVisual node) {
        // 点击只高亮并同步快捷键目标，镜头和窗口布局保持当前状态。
        canvas.highlight(node);
        selection.accept(node);
    }

    public BodyCanvas canvas() {
        // 场景仍可旋转、缩放，并在关窗时释放 GPU 缓冲。
        return canvas;
    }

    public FlowLayout root() {
        // 宿主挂载同一 XML 组件树。
        return root;
    }
}
