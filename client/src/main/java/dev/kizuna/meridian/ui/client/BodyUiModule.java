package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.RestoreStatus;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewRequest;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningRule;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.MeridianOpeningRequest;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.meridian.fabric.MeridianChannels;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.core.UIComponent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 通用窗口入口；宿主提供 Protobuf 身体及生物包的资源标识。 */
public final class BodyUiModule implements ClientModule {
    private ClientRuntime runtime;
    private WindowKey bodyKey;
    private boolean bodyLocked;
    private WindowKey cultivationKey;
    private boolean cultivationLocked;
    private BodyScene scene;
    private BodySnapshot body;
    /** 这条连接上最近收到的构型；宿主省略构型时接回这一份。 */
    private BodyStructure structure;
    /** 最近一次向宿主要的是不是内观视图。 */
    private boolean introspectionRequested;
    /** Fabric 宿主经身体视图通道交付的身体 ID；内观只替这具身体要，本地展示的身体不订阅。 */
    private String hostBodyId = "";
    private BodyPanel panel;
    private CultivationPanel cultivationPanel;
    private EnvironmentDetailPanel auraPanel;
    private GatheringView gathering;
    private Consumer<GatheringRequest> gatheringSender;
    private String pendingRequest = "";
    private String gatheringError = "";
    private long breathSequence;
    private BreathControl queuedBreath;
    private final MeridianWindows meridians = new MeridianWindows(this::syncIntrospection);
    private final ModernBodyWindows modern =
            new ModernBodyWindows(this, this::requestIntrospection, this::syncIntrospection);

    @Override
    public FrameworkCatalog.Module definition() {
        // 查看与操作分别注册入口，同一模块共享权威快照及聚气消息通道。
        return FrameworkCatalog.Module.of("meridian_ui:body", Set.of("kiui:core"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:body_window", "meridian_ui:body",
                        280, 180, 580, 400, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:body", "检视", "身体", "meridian_ui:body_icon", "meridian_ui:body_window"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:cultivation_window", "meridian_ui:body",
                        280, 180, 560, 450, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:cultivation", "吐纳", "修炼",
                        "meridian_ui:cultivation_icon", "meridian_ui:cultivation_window"),
                // 「此地灵气」小窗只从吐纳窗口的徽记打开，不进菜单。
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:aura_window", "meridian_ui:body",
                        170, 150, 210, 180, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature(EnvironmentEmblem.DETAIL_FEATURE, "此地灵气", "修炼",
                        "meridian_ui:cultivation_icon", "meridian_ui:aura_window", false),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:meridian_window", "meridian_ui:body",
                        280, 180, 560, 450, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:meridians", "经脉", "修炼",
                        "meridian_ui:body_icon", "meridian_ui:meridian_window"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:practice_window", "meridian_ui:body",
                        360, 280, 600, 480, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:practice",
                        dev.kizuna.meridian.ui.api.MeridianSceneExtension.required().title(), "修炼",
                        "meridian_ui:body_icon", "meridian_ui:practice_window"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:meridian_operation_window", "meridian_ui:body",
                        220, 230, 290, 365, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:meridian_operation", "经脉操作", "修炼",
                        "meridian_ui:body_icon", "meridian_ui:meridian_operation_window", false));
    }

    @Override
    public Optional<FrameworkCatalog.Module> modernDefinition() {
        return Optional.of(modern.definition());
    }

    @Override
    public void registerModern(ClientBindings bindings) {
        // 此地灵气、经脉、中焦和经脉操作在新版里沿用 register 绑的窗口；这里绑检视拆出的窗口、新版排法的吐纳窗口和各窗口的摘要。
        modern.register(bindings);
    }

    @Override
    public void register(ClientBindings bindings) {
        // 同一生成消息同时支持本地宿主和已协商的网络路由，客户端不依赖权威实现。
        bindings.featureAccess("meridian_ui:meridians", ClientProgression::canCultivate);
        bindings.featureAccess("meridian_ui:meridian_operation", meridians::canOpenOperation);
        // 菜单与直接打开共用各自残卷权限；具体练习门槛由必装生物扩展解释。
        bindings.featureAccess("meridian_ui:cultivation", ClientProgression::canGather);
        bindings.featureAccess(EnvironmentEmblem.DETAIL_FEATURE, ClientProgression::canGatherDetails);
        bindings.featureAccess("meridian_ui:practice", () -> ClientProgression.canCultivate()
                && dev.kizuna.meridian.ui.api.MeridianSceneExtension.required().lockedReason().isEmpty());
        ClientProgression.onChanged(this::progressChanged);
        meridians.register(bindings);
        // GUI 渲染器初始化时冻结画中画注册表；模块在客户端入口登记，此时登记身体画布的转交渲染器。
        PictureInPictureRendererRegistry.register(context -> new BodySceneRenderer());
        ClientPlayNetworking.registerGlobalReceiver(MeridianChannels.BODY_VIEW.type(), (packet, context) -> {
            // Fabric 在当前连接的客户端线程回调；空包与超长包直接丢弃，只读快照交给界面。
            if (MeridianChannels.BODY_VIEW.accepts(packet)) {
                receiveBodyView(packet.bytes());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            // 断线可从网络线程通知，旧连接的迟到释放也不能清掉新身体。
            if (client.getConnection() == null || client.getConnection() == handler) {
                clearBody();
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(MeridianChannels.GATHERING_RESPONSE.type(), (packet, context) -> {
            // Fabric 宿主与公共路由使用同一生成消息；回调已在当前连接的客户端线程，空包与超长包直接丢弃。
            if (!MeridianChannels.GATHERING_RESPONSE.accepts(packet)) {
                return;
            }
            try {
                receiveGathering(GatheringResponse.parseFrom(packet.bytes()));
            } catch (IOException invalid) {
                org.slf4j.LoggerFactory.getLogger("meridian_ui").warn(UiErrors.INVALID_UPDATE, invalid);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 输入租约按客户端 tick 续期，渲染帧率不会改变控制消息的频率。
            meridians.tick();
            if (cultivationPanel != null) {
                cultivationPanel.tick();
            }
            // 检视摘要里的负重读数不随身体快照来，开着窗口时逐 tick 核对。
            if (panel != null) {
                panel.tick();
            }
            modern.tick();
        });
        bindings.message(new UiWire.Contract<>(new UiMessageRouter.Contract<>("meridian_ui:body",
                "meridian_ui:gathering_request", 1, UiMessageRouter.Direction.CLIENT_TO_SERVER,
                GatheringRequest.class, null), UiWire.Codec.protobuf(GatheringRequest.getDefaultInstance()), false));
        bindings.message(new UiWire.Contract<>(new UiMessageRouter.Contract<>("meridian_ui:body",
                "meridian_ui:gathering_response", 1, UiMessageRouter.Direction.SERVER_TO_CLIENT,
                GatheringResponse.class, this::receiveGathering), UiWire.Codec.protobuf(GatheringResponse.getDefaultInstance()), false));
        // 没有宿主数据时显示等待状态，不偷偷生成默认人体。
        bindings.window("meridian_ui:body_window", (runtime, window) -> createBodyWindow(window));
        bindings.window("meridian_ui:aura_window", (runtime, window) -> {
            // 小窗只显示吐纳视图里已有的当地环境，关窗就丢掉引用。
            var opened = new EnvironmentDetailPanel();
            auraPanel = opened;
            window.scope().addCleanup(() -> {
                if (auraPanel == opened) {
                    auraPanel = null;
                }
            });
            showGathering();
            return opened;
        });
        bindings.window("meridian_ui:cultivation_window", (runtime, window) ->
                breathingWindow(window, ClassicCultivationPanel::new));
    }

    private UIComponent createBodyWindow(WindowState window) {
        // 整个检视窗口受内观卷授权；锁定通知也记录窗口，进度变化后及时关闭。
        WindowKey openedKey = window.key();
        bodyKey = openedKey;
        bodyLocked = !ClientProgression.canInspect();
        window.scope().addCleanup(() -> {
            if (bodyKey == openedKey) {
                bodyKey = null;
            }
        });
        if (bodyLocked) {
            return new LockedNotice("内观", UiErrors.INNER_VISION_LOCKED);
        }
        if (scene == null || body == null) {
            return UIComponents.label(net.minecraft.network.chat.Component.literal(UiErrors.MISSING_BODY));
        }
        panel = new BodyPanel(scene, body);
        BodyPanel opened = panel;
        window.scope().addCleanup(() -> {
            opened.canvas().dispose();
            if (panel == opened) {
                panel = null;
            }
        });
        return panel.root();
    }

    private void receiveBodyView(byte[] payload) {
        // 该通道只能由当前连接的服务器发送；客户端不把快照当作可写身体或计算依据。
        try {
            BodyViewUpdate update = BodyViewUpdate.parseFrom(payload);
            if (!update.hasBody()) {
                // 独立订阅回执不替换身体；拒绝后撤下内部窗口。
                if (update.hasFailure()) {
                    modern.introspectionReceipt(update.getRequestId(), RestoreStatus.DENIED);
                    revokeCultivation();
                } else {
                    modern.introspectionReceipt(update.getRequestId(), RestoreStatus.LIVE);
                }
                return;
            }
            BodySnapshot snapshot = withStructure(update);
            if (update.getProtocolVersion() != 1 || snapshot.getId().isBlank()) {
                throw UiErrors.invalid(UiErrors.INVALID_UPDATE);
            }
            Identifier resource = Identifier.tryParse(update.getPresentationResource());
            if (resource == null) {
                throw UiErrors.invalid(UiErrors.INVALID_UPDATE);
            }
            if (body == null) {
                present(snapshot, resource);
                hostBodyId = snapshot.getId();
                bindFabricGathering();
            } else if (body.getId().equals(snapshot.getId())
                    && snapshot.getRevision() >= body.getRevision()) {
                update(snapshot);
            } else {
                // 被拒绝的身体快照也不能替换当前身体的操作规则。
                return;
            }
            if (update.getIntrospection() == introspectionRequested) {
                modern.introspectionSnapshot();
            }
            if (update.hasOpening()) {
                meridians.options(update.getOpening().getRulesList());
            }
            for (var operations : update.getOperationsList()) {
                // 卡片与已接收快照逐版本对应，旧条件不会沿用到新身体状态。
                meridians.updateOperations(operations);
            }
        } catch (IOException | IllegalArgumentException failure) {
            // 异常包保留旧展示并记录原因，避免损坏模型资源让客户端退出游戏。
            org.slf4j.LoggerFactory.getLogger("meridian_ui").warn(UiErrors.INVALID_UPDATE, failure);
        }
    }

    private BodySnapshot withStructure(BodyViewUpdate update) {
        // 宿主发出带构型的身体后，下一份就可能省略：所以带了就换下手里那份，不论这份身体是否采用；
        // 省略时接回手里那份，手里没有就是坏包。
        if (!update.getStructureOmitted()) {
            structure = update.getBody().getStructure();
            return update.getBody();
        }
        if (structure == null) {
            throw UiErrors.invalid(UiErrors.INVALID_UPDATE);
        }
        return update.getBody().toBuilder()
                .setStructure(structure)
                .build();
    }

    public void present(BodySnapshot snapshot, Identifier resource) {
        // 只接受客户端线程上的宿主交付；资源按 Minecraft 资源管理器查找，支持内容包覆盖。
        if (!Minecraft.getInstance().isSameThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
        try (var stream = Minecraft.getInstance().getResourceManager().open(resource)) {
            BodyScene candidate = new BodyScene(stream.readNBytes(BodyScene.MAX_BYTES + 1));
            candidate.requireTemplate(snapshot);
            if (panel != null || modern.open() || meridians.open() || (body != null && cultivationPanel != null)) {
                throw UiErrors.invalid(UiErrors.WINDOW_OPEN);
            }
            scene = candidate;
            body = snapshot;
            OpeningOverlay.clear();
            OpeningOverlay.receive(snapshot);
            meridians.present(scene, snapshot);
            gathering = null;
            pendingRequest = "";
            gatheringError = "";
        } catch (IOException failure) {
            throw UiErrors.invalid(UiErrors.RESOURCE_UNREADABLE + resource);
        }
        refreshGathering();
    }

    public void update(BodySnapshot snapshot) {
        // 后续快照更新已有窗口；网络认证及快照完整性由宿主接入层负责。
        if (!Minecraft.getInstance().isSameThread() || body == null || scene == null
                || !body.getId().equals(snapshot.getId()) || snapshot.getRevision() < body.getRevision()) {
            throw UiErrors.invalid(UiErrors.INVALID_UPDATE);
        }
        scene.requireTemplate(snapshot);
        OpeningOverlay.receive(snapshot);
        if (panel != null) {
            panel.update(snapshot);
        }
        modern.update(snapshot);
        body = snapshot;
        meridians.update(snapshot);
        if (gathering != null) {
            // 身体推送和吐纳回执共用最新账本，避免另一个窗口仍显示上次吐纳的旧余额。
            gathering = gathering.toBuilder().setBody(snapshot).build();
            showGathering();
        }
    }

    public MeridianPanel meridianPanel() {
        // 宿主可检查当前经脉窗口，未打开时返回空。
        return meridians.panel();
    }

    public MeridianPanel practicePanel() {
        // 暴露当前练习组件供宿主检查实际布局，关闭后返回空。
        return meridians.practicePanel();
    }

    public io.wispforest.owo.ui.core.UIComponent openingOperationRoot() {
        // 宿主可检查操作浮窗的布局；组件仍由窗口 scope 持有和清理。
        return meridians.operationRoot();
    }

    public void bindOpening(List<MeridianOpeningRule> rules, Consumer<MeridianOpeningRequest> sender) {
        // 通用宿主提供规则和生成消息入口，UI 不需要知道规则来自哪一个生物包。
        meridians.bind(rules, sender);
    }

    public void receiveOpening(ResponseEnvelope response) {
        // 开脉响应沿用框架唯一响应契约。
        meridians.receive(response);
    }

    public Runnable registerMeridianOperations(String namespace, Consumer<MeridianOperationRequest> sender) {
        // 附属注册连接内的 Protobuf 入口；返回的撤销句柄在客户端线程调用。
        requireClientThread();
        Runnable remove = meridians.registerOperations(namespace, sender);
        return () -> {
            // 卸载和渲染串行，避免删除正在读取的目录。
            requireClientThread();
            remove.run();
        };
    }

    public void updateMeridianOperations(MeridianOperationUpdate update) {
        // 更新必须对应当前身体版本；客户端不从声明卡片推断执行权限。
        requireClientThread();
        meridians.updateOperations(update);
    }

    public void receiveMeridianOperation(ResponseEnvelope response) {
        // 附属回执只确认请求，实际资源和进度等候后续权威投影。
        meridians.receive(response);
    }

    private static void requireClientThread() {
        // 所有 Addon 与窗口一样在客户端线程交接展示状态。
        if (!Minecraft.getInstance().isSameThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
    }

    public BodyPanel panel() {
        // 宿主可检查当前窗口，未打开时返回空。
        return panel;
    }

    /** 新版界面的体表窗口；未打开时返回空。 */
    public ModernBodyPanel surfacePanel() {
        return modern.panel(InspectionSection.SURFACE);
    }

    /** 新版体表窗口现在的正文（平面人形或立体模型）；窗口没开或还锁着时为空。 */
    Optional<ModernSurfacePanel> surfaceView() {
        return modern.surface();
    }

    BodyScene scene() {
        return scene;
    }

    ClientRuntime runtime() {
        return runtime;
    }

    /** 协议已就绪，手里的身体就是宿主在这条连接上交付的那一具。 */
    boolean hostBodyReady() {
        return runtime != null && body != null && body.getId().equals(hostBodyId);
    }

    /** 替小窗发一次吐纳只读查询；回执按这个请求号交还，不占操作请求的回执位。 */
    void inspectGathering(String requestId) {
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setBodyId(body.getId())
                .setRequestId(requestId)
                .setInspect(InspectGathering.getDefaultInstance())
                .build();
        gatheringSender.accept(request);
    }

    public BodySnapshot snapshot() {
        // 附属与交互验收读取已经通过连接身份和版本检查的同一不可变投影。
        return body;
    }

    static int realm() {
        // 功法谱读这具身体的境界：入口实例由 Fabric 建一次；身体快照还没到时按凡人算。
        return FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst()
                .map(BodyUiModule::snapshot).map(BodySnapshot::getRealmValue).orElse(0);
    }

    public GatheringView gatheringView() {
        // 交互验收读取宿主发来的最新吐纳视图，只读。
        return gathering;
    }

    /** 吐纳窗口的组件：没读过残卷给锁定提示，身体没到给缺身体提示，否则用 maker 造正文并接上快照和关窗清理。 */
    UIComponent breathingWindow(WindowState window,
            BiFunction<BodyScene, Consumer<BreathControl>, CultivationPanel> maker) {
        // 出身授权可以直接打开吐纳；普通入口仍由下面的锁定态提示缺少阅读事实。
        WindowKey openedKey = window.key();
        if (!ClientProgression.canGather()) {
            // 吐纳入口仍在修炼页，窗口只给锁和找卷提示；记下锁定态，读完后关闭以便重开出真实场景。
            cultivationKey = openedKey;
            cultivationLocked = true;
            window.scope().addCleanup(() -> {
                if (cultivationKey == openedKey) {
                    cultivationKey = null;
                }
            });
            String title = WindowText.get("action.breathing", "吐纳");
            String hint = WindowText.get("notice.breathing_locked", UiErrors.GATHERING_LOCKED);
            return new LockedNotice(title, hint);
        }
        if (scene == null || body == null) {
            return UIComponents.label(net.minecraft.network.chat.Component.literal(UiErrors.missingBody()));
        }
        cultivationPanel = maker.apply(scene, this::controlBreathing);
        cultivationKey = openedKey;
        cultivationLocked = false;
        showGathering();
        CultivationPanel opened = cultivationPanel;
        window.scope().addCleanup(() -> {
            if (cultivationPanel == opened) {
                opened.close();
                cultivationPanel = null;
            }
            if (cultivationKey == openedKey) {
                cultivationKey = null;
            }
        });
        refreshGathering();
        return cultivationPanel.root();
    }

    public CultivationPanel cultivationPanel() {
        // 修炼窗口具有独立生命周期，未打开时返回空。
        return cultivationPanel;
    }

    public void bindGathering(Consumer<GatheringRequest> sender) {
        // 单机或内存宿主也交接 protoc 消息，UI 从不接收可写 Store。
        gatheringSender = sender;
        refreshGathering();
    }

    public void refreshGathering() {
        // 查询只读取选项和状态，不在窗口刷新时领取灵气。
        if (ClientProgression.canGather()
                && body != null && gatheringSender != null && pendingRequest.isEmpty()) {
            sendGathering(GatheringRequest.newBuilder().setInspect(InspectGathering.getDefaultInstance()));
        }
    }

    private void controlBreathing(BreathControl control) {
        // 停止意图优先于旧快照产生的方向续期，关窗后立即重开也不能遗留旧会话。
        if (!ClientProgression.canGather()
                || body == null || gatheringSender == null) {
            return;
        }
        if (queuedBreath != null && queuedBreath.getAction() == BreathAction.END_BREATHING
                && continuesBreathing(control.getAction())) {
            return;
        }
        queuedBreath = control;
        if (pendingRequest.isEmpty()) {
            queuedBreath = null;
            var sequenced = control.toBuilder()
                    .setSequence(++breathSequence)
                    .build();
            var request = GatheringRequest.newBuilder()
                    .setBreath(sequenced);
            sendGathering(request);
        }
    }

    private static boolean continuesBreathing(BreathAction action) {
        // 方向和心跳可合并，明确开始或结束必须保留自身语义。
        return action == BreathAction.INHALE || action == BreathAction.EXHALE || action == BreathAction.HOLD;
    }

    private void sendGathering(GatheringRequest.Builder request) {
        // 请求发出后等业务回执，重复按钮不会排出多次摄入。
        pendingRequest = UUID.randomUUID().toString();
        gatheringError = "";
        showGathering();
        var message = request.setProtocolVersion(1)
                .setRequestId(pendingRequest)
                .setBodyId(body.getId())
                .build();
        gatheringSender.accept(message);
    }

    public void receiveGathering(GatheringResponse response) {
        // 所有响应在客户端线程交付，迟到的旧身体或旧版本不会覆盖当前窗口。
        if (!Minecraft.getInstance().isSameThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
        if (!ClientProgression.canGather()
                || response.getProtocolVersion() != 1 || body == null) {
            return;
        }
        if (modern.observingBreath(response.getRequestId())) {
            receiveObservation(response);
            return;
        }
        boolean reply = response.getRequestId().equals(pendingRequest);
        if (reply) {
            pendingRequest = "";
        }
        if (response.hasFailure() && (reply || response.getRequestId().equals("state"))) {
            gatheringError = UiErrors.gatheringFailure(response.getFailure().getCode());
            if (response.getFailure().getCode()
                    == dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.ACCESS_DENIED) {
                gathering = null;
            }
        } else if (response.hasView() && newerGathering(response.getView())) {
            GatheringView view = response.getView();
            breathSequence = Math.max(breathSequence, view.getBreathing().getSequence());
            if (view.hasBody()) {
                gathering = view;
                update(view.getBody());
            } else {
                // Fabric 宿主的回执不带身体，身体走身体视图通道，这里接上手里最新的那份。
                gathering = view.toBuilder()
                        .setBody(body)
                        .build();
            }
        }
        showGathering();
        if (pendingRequest.isEmpty() && queuedBreath != null) {
            controlBreathing(queuedBreath);
        }
    }

    private void receiveObservation(GatheringResponse response) {
        // 小窗的只读查询：结果照常进入吐纳投影，但不清操作请求的等待，也不推动排队的吐纳输入。
        if (response.hasFailure()) {
            modern.breathReceipt(RestoreStatus.DENIED);
            gatheringError = UiErrors.gatheringFailure(response.getFailure().getCode());
            if (response.getFailure().getCode()
                    == dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.ACCESS_DENIED) {
                gathering = null;
            }
        } else if (response.hasView() && newerGathering(response.getView())) {
            GatheringView view = response.getView();
            breathSequence = Math.max(breathSequence, view.getBreathing().getSequence());
            if (view.hasBody()) {
                gathering = view;
                update(view.getBody());
            } else {
                gathering = view.toBuilder()
                        .setBody(body)
                        .build();
            }
            if (view.hasBreathing()) {
                modern.breathReceipt(RestoreStatus.LIVE);
            }
        }
        showGathering();
    }

    private boolean newerGathering(GatheringView view) {
        // 带身体的回执要对上当前身体且版本不旧；不带身体的回执来自 Fabric 宿主，当前显示的得是宿主交付的身体。
        // 吐纳序号也不能倒退。
        boolean bodyFits;
        if (view.hasBody()) {
            bodyFits = view.getBody().getId().equals(body.getId())
                    && view.getBody().getRevision() >= body.getRevision();
        } else {
            bodyFits = body.getId().equals(hostBodyId);
        }
        return bodyFits && (gathering == null || newerBreathing(view.getBreathing(), gathering.getBreathing()));
    }

    private static boolean newerBreathing(BreathingView candidate, BreathingView current) {
        // 序号只在同一吐纳会话内比较；宿主换了会话（登录、换维度、重生）就从新会话的序号重新算。
        boolean sameSession = candidate.getSessionId().equals(current.getSessionId());
        return !sameSession || candidate.getSequence() >= current.getSequence();
    }

    private void showGathering() {
        // 窗口关闭期间只保留最后的只读投影，再打开时恢复当前阶段。
        if (cultivationPanel != null) {
            cultivationPanel.update(gathering, !pendingRequest.isEmpty(), gatheringError);
        }
        if (auraPanel != null) {
            auraPanel.update(gathering != null && gathering.hasEnvironment() ? gathering.getEnvironment() : null);
        }
    }

    @Override
    public void protocolReady(ClientRuntime runtime) {
        // 必装 Fabric 宿主提供操作通道；Inventory UI 仍管理窗口生命周期。
        this.runtime = runtime;
        meridians.protocolReady(runtime);
        bindFabricGathering();
    }

    private void progressChanged() {
        // 进度是连接权威投影；重置通知立即清窗口与尚未发出的控制意图。
        if (runtime != null) {
            runtime.recheckRestores();
            modern.progressChanged();
        }
        if (runtime != null && bodyKey != null && ClientProgression.canInspect() == bodyLocked) {
            runtime.workspace().windows().close(bodyKey);
        }
        if (!ClientProgression.canCultivate()) {
            revokeCultivation();
        }
        // 聚气卷锁定态变化时关闭吐纳窗：撤权立即停止，解锁后重开就是真实吐纳场景。
        if (runtime != null && cultivationKey != null && ClientProgression.canGather() == cultivationLocked) {
            runtime.workspace().windows().close(cultivationKey);
        }
        meridians.progressChanged();
        // 撤权时宿主会丢掉内观订阅再重发进度，所以每次进度到达都按窗口现状重新要一次，恢复权限时就重新订上。
        requestIntrospection();
        refreshGathering();
    }

    private void revokeCultivation() {
        // 先清背压队列再关窗，关闭回调不能重新发出旧吸气意图。
        queuedBreath = null;
        pendingRequest = "";
        gathering = null;
        if (runtime != null && cultivationKey != null) {
            runtime.workspace().windows().close(cultivationKey);
        }
        meridians.revoke();
        if (panel != null) {
            panel.layer(scene.defaultLayerId());
        }
        if (scene != null) {
            modern.resetSurfaceLayer(scene.defaultLayerId());
        }
    }

    private boolean wantsIntrospection() {
        // 内观视图带开脉规则和操作卡，只在经脉窗或操作窗开着、进度也允许内观时要。
        return (modern.wantsIntrospection() || meridians.needsIntrospection()) && ClientProgression.canInspect();
    }

    private void syncIntrospection() {
        // 经脉窗、操作窗开关后，要的和上次不一样才发请求：开窗订阅，都关了就退订。
        if (wantsIntrospection() != introspectionRequested) {
            requestIntrospection();
        }
    }

    private void requestIntrospection() {
        // 按窗口现状订阅或退订；真实服务端单独授权内部视图，未同步不冒充未读发起请求。
        // 正在显示的不是宿主交付的身体（比如本地展示样例）时不发：宿主会拒绝，拒绝又会撤下经脉窗。
        if (body == null || !body.getId().equals(hostBodyId) || Minecraft.getInstance().getConnection() == null
                || !ClientProgression.synchronizedWithServer()) {
            return;
        }
        if (!ClientPlayNetworking.canSend(MeridianChannels.BODY_VIEW_REQUEST.type())) {
            throw new IllegalStateException(UiErrors.REQUIRED_HOST_CHANNEL + MeridianChannels.BODY_VIEW_REQUEST.type().id());
        }
        boolean introspection = wantsIntrospection();
        var request = BodyViewRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(body.getId())
                .setIntrospection(introspection)
                .build();
        modern.introspectionSent(request.getRequestId());
        ClientPlayNetworking.send(MeridianChannels.BODY_VIEW_REQUEST.payload(request.toByteArray()));
        introspectionRequested = introspection;
    }

    private void bindFabricGathering() {
        // 不把缺少必装宿主误表示为只读或等待能力。
        if (!ClientPlayNetworking.canSend(MeridianChannels.GATHERING_REQUEST.type())) {
            throw new IllegalStateException(UiErrors.REQUIRED_HOST_CHANNEL + MeridianChannels.GATHERING_REQUEST.type().id());
        }
        bindGathering(request -> {
            // 操作参数来自 UI，身份、时间、摄入量和配方能力仍由逻辑服务器决定。
            ClientPlayNetworking.send(MeridianChannels.GATHERING_REQUEST.payload(request.toByteArray()));
        });
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 断线清除身体引用，不能把旧服务器的数据带到下一次连接。
        clearBody();
    }

    private void clearBody() {
        // 未建立 Inventory UI 握手的连接也必须在 Fabric 断线时清除只读投影。
        // 订阅和构型随连接作废；先清掉身体再关窗，关窗回调见没有身体就不往断开的连接发订阅请求。
        if (runtime != null) {
            // 下面关窗是连接断开，不是玩家撤销；先告诉框架，新版锁着的小窗才会留到下次登录。
            runtime.connectionClosing();
        }
        modern.clear();
        introspectionRequested = false;
        hostBodyId = "";
        structure = null;
        body = null;
        meridians.clear();
        OpeningOverlay.clear();
        scene = null;
        panel = null;
        bodyKey = null;
        cultivationPanel = null;
        gathering = null;
        gatheringSender = null;
        pendingRequest = "";
        gatheringError = "";
        queuedBreath = null;
        breathSequence = 0;
    }
}
