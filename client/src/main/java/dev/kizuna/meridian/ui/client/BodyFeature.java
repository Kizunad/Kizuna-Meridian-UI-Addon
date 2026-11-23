package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import java.util.List;
import java.util.Set;

/** 新版界面里身体相关的功能和窗口；ID 和旧版相同，旧版的锁按窗口类型认领。尺寸是 GUI 逻辑像素。 */
enum BodyFeature {
    SURFACE("body", "body", "检视 · 体表", Frame.SURFACE),
    MUSCLE("muscles", "muscles", "检视 · 肌肉", Frame.INSPECTION),
    ORGAN("organs", "organs", "检视 · 器官", Frame.INSPECTION),
    OVERVIEW("overview", "overview", "概况", Frame.OVERVIEW),
    BREATH("cultivation", "cultivation", "吐纳", Frame.SCENE),
    AURA("aura", "aura", "此地灵气", Frame.AURA),
    MERIDIAN("meridians", "meridian", "经脉", Frame.SCENE),
    PRACTICE("practice", "practice", "", Frame.PRACTICE),
    OPERATION("meridian_operation", "meridian_operation", "经脉操作", Frame.OPERATION);

    /** 体表窗口换成立体模型时至少要这么大，和肌肉、器官窗口的首选尺寸相同。 */
    static final int MODEL_WIDTH = Frame.INSPECTION.width();
    static final int MODEL_HEIGHT = Frame.INSPECTION.height();

    /** 最小和首选外框。检视拆出的窗口用新的小尺寸，其余窗口的内容还是旧版排法，尺寸也和旧版一致。 */
    private record Frame(int minWidth, int minHeight, int width, int height) {
        static final Frame SURFACE = new Frame(150, 60, 150, 104);
        static final Frame INSPECTION = new Frame(150, 120, 194, 174);
        static final Frame OVERVIEW = new Frame(150, 60, 190, 60);
        static final Frame SCENE = new Frame(280, 180, 560, 450);
        static final Frame AURA = new Frame(170, 150, 210, 180);
        static final Frame PRACTICE = new Frame(360, 280, 600, 480);
        static final Frame OPERATION = new Frame(220, 230, 290, 365);
    }

    final String id;
    final String type;
    final String title;
    private final Frame frame;

    BodyFeature(String feature, String window, String title, Frame frame) {
        this.id = "meridian_ui:" + feature;
        this.type = "meridian_ui:" + window + "_window";
        this.title = title;
        this.frame = frame;
    }

    void declare(List<FrameworkCatalog.Entry> entries, String practiceTitle) {
        // 两个详情入口与概况由父页打开。
        var window = new UiWindowDefinition(type, "meridian_ui:body", frame.minWidth(), frame.minHeight(),
                frame.width(), frame.height(), Set.of(UiWindowDefinition.Capability.WINDOW));
        String name = this == PRACTICE ? practiceTitle : title;
        String category = switch (this) {
            case SURFACE, MUSCLE, ORGAN, OVERVIEW -> "身体";
            default -> "修炼";
        };
        String icon = "meridian_ui:body_icon";
        if (this == BREATH || this == AURA) {
            icon = "meridian_ui:cultivation_icon";
        }
        boolean visible = this != OVERVIEW && this != AURA && this != OPERATION;
        entries.add(new FrameworkCatalog.Window(window));
        entries.add(new FrameworkCatalog.Feature(id, name, category, icon, type, visible));
    }
}
