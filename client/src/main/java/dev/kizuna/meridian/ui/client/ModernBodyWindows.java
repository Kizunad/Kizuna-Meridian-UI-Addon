package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactSlot;
import dev.kizuna.inventoryui.client.RestoreBinding;
import dev.kizuna.inventoryui.client.RestoreReadiness;
import dev.kizuna.inventoryui.client.RestoreStatus;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/**
 * 新版界面里身体相关的窗口：检视拆成体表、肌肉、器官三个窗口和一行概况，七个可收小的窗口各带一份摘要。
 * 身体快照、吐纳回执和内观订阅仍只有 BodyUiModule 那一份，这里只在它给的几个时机上更新自己的窗口。
 */
final class ModernBodyWindows {
    private static final List<BodyFeature> SUMMARY_MAIN_COLUMN = List.of(BodyFeature.BREATH, BodyFeature.PRACTICE,
            BodyFeature.SURFACE, BodyFeature.MERIDIAN, BodyFeature.OVERVIEW);
    private static final List<BodyFeature> SUMMARY_SIDE_COLUMN = List.of(BodyFeature.MUSCLE, BodyFeature.ORGAN);
    private final BodyUiModule module;
    private final Runnable requestIntrospection;
    private final Runnable syncIntrospection;
    private final EnumMap<InspectionSection, ModernBodyPanel> inspection = new EnumMap<>(InspectionSection.class);
    private final BodyObservers observers = new BodyObservers();
    private Consumer<RestoreStatus> breathReceipt;
    private String inspectId = "";
    private WindowKey surfaceKey;
    private boolean surfaceLocked;

    ModernBodyWindows(BodyUiModule module, Runnable requestIntrospection, Runnable syncIntrospection) {
        this.module = module;
        this.requestIntrospection = requestIntrospection;
        this.syncIntrospection = syncIntrospection;
    }

    FrameworkCatalog.Module definition() {
        var entries = new ArrayList<FrameworkCatalog.Entry>();
        String practice = MeridianSceneExtension.required().title();
        for (var feature : BodyFeature.values()) {
            feature.declare(entries, practice);
        }
        return new FrameworkCatalog.Module("meridian_ui:body", Set.of("kiui:core"), entries);
    }

    void register(ClientBindings bindings) {
        registerInspections(bindings);
        registerSummaries(bindings);
    }

    private void registerInspections(ClientBindings bindings) {
        // 体表沿用旧版检视的窗口类型和未读残卷时的锁定通知；另外三个入口沿用内观权限。
        bindings.window(BodyFeature.SURFACE.type, (owner, w) -> createBodyWindow(w, InspectionSection.SURFACE));
        bindings.featureAccess(BodyFeature.MUSCLE.id, ClientProgression::canInspect);
        bindings.window(BodyFeature.MUSCLE.type, (owner, w) -> createBodyWindow(w, InspectionSection.MUSCLES));
        bindings.featureAccess(BodyFeature.ORGAN.id, ClientProgression::canInspect);
        bindings.window(BodyFeature.ORGAN.type, (owner, w) -> createBodyWindow(w, InspectionSection.ORGANS));
        bindings.featureAccess(BodyFeature.OVERVIEW.id, ClientProgression::canInspect);
        bindings.window(BodyFeature.OVERVIEW.type, (owner, w) ->
                UIComponents.label(Component.literal(MeridianSceneExtension.required()
                        .overviewLine(module.snapshot()))));
    }

    private void registerSummaries(ClientBindings bindings) {
        // 七个单例分别拥有自己的固定意图、订阅和位置。
        var extension = MeridianSceneExtension.required();
        bindSummary(bindings, BodyFeature.SURFACE, extension.compactBody(InspectionSection.SURFACE, module::snapshot));
        bindSummary(bindings, BodyFeature.MUSCLE, extension.compactBody(InspectionSection.MUSCLES, module::snapshot));
        bindSummary(bindings, BodyFeature.ORGAN, extension.compactBody(InspectionSection.ORGANS, module::snapshot));
        bindSummary(bindings, BodyFeature.MERIDIAN, MeridianSummaries.channels(module));
        bindSummary(bindings, BodyFeature.OVERVIEW, MeridianSummaries.overview(module, extension));
        bindSummary(bindings, BodyFeature.BREATH, MeridianSummaries.breath(module));
        bindSummary(bindings, BodyFeature.PRACTICE, extension.compactPractice(module::snapshot));
    }

    private static CompactSlot summarySlot(BodyFeature feature) {
        // 没被玩家拖过的小窗：主列贴左边自上而下，肌肉和器官在它右边另起一列。
        int main = SUMMARY_MAIN_COLUMN.indexOf(feature);
        if (main >= 0) {
            return new CompactSlot(0, main);
        }
        return new CompactSlot(1, SUMMARY_SIDE_COLUMN.indexOf(feature));
    }

    private void bindSummary(ClientBindings bindings, BodyFeature feature, CompactProvider provider) {
        // 仅显式窗口工厂持有输入会话。后台 scope 只发只读查询。
        bindings.compact(feature.type, summarySlot(feature), provider);
        bindings.restore(feature.id, new RestoreBinding() {
            @Override
            public RestoreReadiness readiness() {
                return summaryReadiness(feature);
            }

            @Override
            public void observe(WindowState window, Consumer<RestoreStatus> status) {
                if (feature == BodyFeature.BREATH) {
                    observeBreath(window, status);
                } else {
                    observers.add(window, status, requestIntrospection, syncIntrospection);
                }
            }
        });
    }

    private RestoreReadiness summaryReadiness(BodyFeature feature) {
        // 权限和身体都来自当前登录；同步之前不能把默认字段当成撤权或满状态。
        if (!module.hostBodyReady() || !ClientProgression.synchronizedWithServer()) {
            return RestoreReadiness.WAITING;
        }
        boolean allowed = switch (feature) {
            case BREATH -> ClientProgression.canGather();
            case PRACTICE -> ClientProgression.canUseMiddleJiao();
            case MERIDIAN -> ClientProgression.canCultivate();
            default -> ClientProgression.canInspect();
        };
        return allowed ? RestoreReadiness.READY : RestoreReadiness.DENIED;
    }

    private void observeBreath(WindowState window, Consumer<RestoreStatus> status) {
        // 独立请求号不占用方向输入队列，恢复期间不会推动排队的输入。
        String id = UUID.randomUUID().toString();
        inspectId = id;
        breathReceipt = status;
        window.scope().addCleanup(() -> {
            if (inspectId.equals(id)) {
                inspectId = "";
                breathReceipt = null;
            }
        });
        module.inspectGathering(id);
    }

    /** 这个请求号是不是小窗发出的吐纳只读查询。 */
    boolean observingBreath(String requestId) {
        return !inspectId.isEmpty() && inspectId.equals(requestId);
    }

    void breathReceipt(RestoreStatus status) {
        if (breathReceipt != null) {
            breathReceipt.accept(status);
        }
    }

    private Optional<WindowState> overviewWindow() {
        // 以窗口管理器为准，外部关闭或断线会同步取消勾选。
        return module.runtime().workspace().windowBar().stream()
                .filter(w -> w.key().windowType().equals(BodyFeature.OVERVIEW.type)).findFirst();
    }

    private void toggleOverview() {
        // 概况使用独立的 scope 和固定意图。
        var runtime = module.runtime();
        var existing = overviewWindow();
        if (existing.isPresent()) {
            runtime.workspace().windows().close(existing.get().key());
        } else {
            var window = runtime.open(BodyFeature.OVERVIEW.id, null);
            runtime.workspace().windows().minimize(window.key());
        }
    }

    private UIComponent createBodyWindow(WindowState window, InspectionSection section) {
        // 整个检视窗口受内观卷授权；锁定通知也记录窗口，进度变化后及时关闭。
        WindowKey openedKey = window.key();
        if (section == InspectionSection.SURFACE) {
            surfaceKey = openedKey;
            surfaceLocked = !ClientProgression.canInspect();
        }
        window.scope().addCleanup(() -> {
            if (surfaceKey == openedKey) {
                surfaceKey = null;
            }
        });
        if (!ClientProgression.canInspect()) {
            return new LockedNotice("内观", UiErrors.INNER_VISION_LOCKED);
        }
        var scene = module.scene();
        var body = module.snapshot();
        if (scene == null || body == null) {
            return UIComponents.label(Component.literal(UiErrors.MISSING_BODY));
        }
        ModernBodyPanel opened = new ModernBodyPanel(scene, body, section,
                () -> overviewWindow().isPresent(), this::toggleOverview);
        inspection.put(section, opened);
        window.scope().addCleanup(() -> {
            opened.canvas().dispose();
            inspection.remove(section, opened);
        });
        return opened.root();
    }

    ModernBodyPanel panel(InspectionSection section) {
        return inspection.get(section);
    }

    /** 还有检视窗口开着，身体模型不能整个换掉。 */
    boolean open() {
        return !inspection.isEmpty();
    }

    void tick() {
        // 检视摘要里的负重读数不随身体快照来，开着窗口时逐 tick 核对。
        inspection.values().forEach(ModernBodyPanel::tick);
    }

    void update(BodySnapshot snapshot) {
        inspection.values().forEach(opened -> opened.update(snapshot));
    }

    void progressChanged() {
        if (surfaceKey != null && ClientProgression.canInspect() == surfaceLocked) {
            module.runtime().workspace().windows().close(surfaceKey);
        }
    }

    void resetSurfaceLayer(String layerId) {
        var surface = inspection.get(InspectionSection.SURFACE);
        if (surface != null) {
            surface.layer(layerId);
        }
    }

    boolean wantsIntrospection() {
        return observers.active();
    }

    void introspectionSent(String requestId) {
        if (observers.active()) {
            observers.sent(requestId);
        }
    }

    void introspectionReceipt(String requestId, RestoreStatus status) {
        observers.receipt(requestId, status);
    }

    void introspectionSnapshot() {
        observers.snapshot();
    }

    void clear() {
        observers.disconnect();
        breathReceipt = null;
        inspectId = "";
        inspection.clear();
        surfaceKey = null;
    }
}
