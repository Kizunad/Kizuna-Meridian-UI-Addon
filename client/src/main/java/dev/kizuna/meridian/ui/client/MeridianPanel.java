package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.inventoryui.client.WindowInputHandler;
import dev.kizuna.meridian.ui.api.MeridianAction;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 站姿经脉场景；点击选中高亮，快捷键负责聚焦与权威操作。 */
public final class MeridianPanel {
    private static final int SCENE_INSET = 6;
    private static final int SCENE_MARGIN = 12;
    private static final int FULL = 100;
    private static final int TITLE_TOP = 6;
    private static final int TITLE_LEFT = 8;
    private static final int TITLE_HEIGHT = 18;
    private static final int TITLE_MARGIN = 44;
    private static final int INFO_RIGHT = 28;
    private static final int INFO_TOP = 4;
    private static final int SCENE_TOP = 30;
    enum Mode { MERIDIANS, PRACTICE }
    private final BodyScene scene;
    private final FlowLayout content;
    private final FlowLayout root;
    private final BodyCanvas canvas;
    private final Consumer<BodyVisual> selection;
    private final MeridianActionQueue actions = new MeridianActionQueue();
    private final dev.kizuna.meridian.ui.api.MeridianSceneExtension.Session extension;

    MeridianPanel(BodyScene scene, BodySnapshot body, Consumer<BodyVisual> selection, Runnable operate) {
        // 普通经脉窗口只处理选脉和开脉，专属练习使用独立窗口。
        this(scene, body, selection, operate, Mode.MERIDIANS);
    }

    MeridianPanel(BodyScene scene, BodySnapshot body, Consumer<BodyVisual> selection, Runnable operate, Mode mode) {
        // 练习场景由生物附属挂载同一画布与控件，普通经脉窗口保留选脉布局。
        this.scene = scene;
        this.selection = selection;
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "opening"))
                .expandTemplate(FlowLayout.class, "meridian-panel", Map.of());
        canvas = new BodyCanvas(scene, this::clicked, () -> {
            // 中键只重置镜头和当前高亮，不改变身体状态。
            canvas().overview();
        });
        var provider = dev.kizuna.meridian.ui.api.MeridianSceneExtension.required();
        if (mode == Mode.PRACTICE) {
            extension = provider.create(scene, canvas);
        } else {
            extension = null;
        }
        mountScene();
        bindKeys(operate);
        root = new SceneRoot();
        update(body);
    }

    private void mountScene() {
        // 练习扩展挂载完整场景，普通经脉保留自身标题和说明入口。
        if (extension != null) {
            content.removeChild(content.childById(UIComponent.class, "title"));
            content.removeChild(content.childById(UIComponent.class, "info"));
            content.childById(FlowLayout.class, "scene").child(extension.component());
            canvas.onDispose(extension::close);
        } else {
            content.childById(FlowLayout.class, "info").child(new MeridianInfo());
            content.childById(FlowLayout.class, "scene").child(canvas);
            content.child(actions);
        }
    }

    void actions(List<MeridianAction> available) {
        // 操作目录由窗口宿主和生物附属提供，场景只负责排列。
        actions.update(available);
        arrange(root.width(), root.height());
    }

    private void bindKeys(Runnable operate) {
        // 专属练习先处理自身按键，其余按键继续由普通经脉操作处理。
        canvas.keyPress().subscribe(event -> {
            // 凝聚和暂停共用权威操作入口，按键不在客户端变更资源。
            if (extension != null) {
                return extension.keyPress(event.key(), event.modifiers());
            }
            if (event.key() == GLFW.GLFW_KEY_G && event.modifiers() == 0) {
                operate.run();
                return true;
            }
            return false;
        });
    }

    private void arrange(int width, int height) {
        // 练习场景使用完整内容区，边缘控件与模型由扩展自行排布。
        MeridianTheme.apply(content);
        if (extension != null) {
            place(content.childById(UIComponent.class, "scene"), SCENE_INSET, SCENE_INSET,
                    width - SCENE_MARGIN, height - SCENE_MARGIN);
            return;
        }
        place(content.childById(UIComponent.class, "title"), TITLE_LEFT, TITLE_TOP,
                width - TITLE_MARGIN, TITLE_HEIGHT);
        place(content.childById(UIComponent.class, "info"), width - INFO_RIGHT, INFO_TOP,
                TITLE_HEIGHT, TITLE_HEIGHT);
        int actionHeight = actions.arrange(width - SCENE_MARGIN);
        int actionTop = height - actionHeight - SCENE_INSET;
        place(content.childById(UIComponent.class, "scene"), SCENE_INSET, SCENE_TOP,
                width - SCENE_MARGIN, actionTop - SCENE_TOP);
        int actionWidth = actions.contentWidth(width - SCENE_MARGIN);
        place(actions, (width - actionWidth) / 2, actionTop, actionWidth, actionHeight);
    }

    static void place(UIComponent component, int x, int y, int width, int height) {
        // 内容区缩小时仍保留正尺寸，文字溢出由各自滚动区处理。
        component.positioning(Positioning.absolute(Math.max(0, x), Math.max(0, y)));
        if (component instanceof LabelComponent label) {
            // owo 0.13 每次排布只 inflate 子组件一次，固定宽度的标签会按 inflate 前的旧宽度（首次为 0）折行成逐字竖排；
            // 改为内容宽度并以 maxWidth 限定折行宽度，首次排布即按给定宽度折行。
            label.maxWidth(Math.max(1, width));
            label.sizing(Sizing.content(), Sizing.fixed(Math.max(1, height)));
        } else {
            component.sizing(Sizing.fixed(Math.max(1, width)), Sizing.fixed(Math.max(1, height)));
        }
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

    private final class SceneRoot extends FlowLayout implements WindowInputHandler {
        SceneRoot() {
            // 独立窗口使用框架的失焦与最小化通知释放手动输入。
            super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
            child(new ResponsiveLayout(content, MeridianPanel.this::arrange));
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            // 普通控件优先消费空格，剩余键才交给当前周天窗口。
            return extension != null && extension.keyPress(event.key(), event.modifiers());
        }

        @Override
        public void keyReleased(KeyEvent event) {
            // 按键释放跨窗口广播，不能留下失焦前的推力。
            if (extension != null) {
                extension.keyRelease(event.key());
            }
        }

        @Override
        public void cancelInput() {
            // 切窗取消手动控制，自动周天继续由服务器运行。
            if (extension != null) {
                extension.cancelInput();
            }
        }
    }
}
