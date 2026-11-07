package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyTargetKind;
import dev.kizuna.meridian.ui.api.MeridianAction;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import net.minecraft.resources.Identifier;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import io.wispforest.owo.ui.component.UIComponents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 经脉窗口与操作意图编排；只读快照、网络回执和镜头各自独立。 */
final class MeridianWindows {
    private static final int MAX_REVISION_RETRIES = 2;
    private static final long REQUEST_TIMEOUT_NANOS = 5_000_000_000L;
    private BodyScene scene;
    private BodySnapshot body;
    private List<MeridianOpeningRule> rules = List.of();
    private Consumer<MeridianOpeningRequest> sender;
    private ClientRuntime runtime;
    private MeridianPanel panel;
    private MeridianPanel practice;
    private WindowKey practiceKey;
    private boolean practiceLocked;
    private OpeningPanel operation;
    private WindowKey panelKey;
    private WindowKey operationKey;
    private BodyVisual selected;
    private String pending = "";
    private long sentAt;
    private String error = "";
    private final MeridianOperations operations = new MeridianOperations();
    private String chosenOperation = "";
    private String pendingOperation = "";
    private String pendingAction = "";
    private long pendingRevision;
    private int revisionRetries;
    private boolean awaitingOperationRefresh;

    MeridianWindows() {
        // 内置开脉和外部功法走同一个目录，避免维护两套窗口行为。
        registerOpening();
    }

    private void registerOpening() {
        // 通用卡片意图转换为框架既有开脉消息，服务端仍只接受已实现的具体操作。
        operations.register("meridian", request -> {
            MeridianOpeningRule rule = rule();
            if (sender == null || rule == null) {
                return;
            }
            var opening = MeridianOpeningRequest.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId())
                    .setBodyId(request.getBodyId()).setExpectedRevision(request.getExpectedRevision());
            if (request.getActionId().equals("pause")) {
                opening.setPause(PauseMeridianOpening.newBuilder().setMeridianId(request.getMeridianId()));
            } else {
                opening.setStart(StartMeridianOpening.newBuilder().setRuleId(rule.getId()).setRuleVersion(rule.getVersion()));
            }
            sender.accept(opening.build());
        });
    }

    Runnable registerOperations(String namespace, Consumer<MeridianOperationRequest> sender) {
        // 撤销句柄同时刷新当前窗口，缺包后不能继续提交旧意图。
        Runnable remove = operations.register(namespace, sender);
        return () -> {
            // 宿主按客户端生命周期卸载自己的发布者。
            remove.run();
            show();
        };
    }

    void updateOperations(MeridianOperationUpdate update) {
        // 内容包发布当前身体版本的只读卡片；开脉卡也必须由权威宿主提供。
        operations.update(body, update);
        show();
    }

    void register(ClientBindings bindings) {
        // Fabric 与 Inventory 公共路由复用同一 Protobuf 意图，UI 不接触身体 Store。
        bindings.window("meridian_ui:practice_window", (owner, window) -> {
            // 生物附属决定练习权限和提示，通用窗口负责快照与释放。
            ClientProgression.requireCultivation();
            runtime = owner;
            var extension = dev.kizuna.meridian.ui.api.MeridianSceneExtension.required();
            WindowKey openedKey = window.key();
            if (!extension.lockedReason().isEmpty()) {
                // 入口仍在修炼页，但弹窗只显示锁和找卷提示；记下锁定态，读完后关闭以便重开出真实控件。
                practiceKey = openedKey;
                practiceLocked = true;
                window.scope().addCleanup(() -> {
                    if (practiceKey == openedKey) {
                        practiceKey = null;
                    }
                });
                return new LockedNotice(extension.title(), extension.lockedReason());
            }
            if (scene == null || body == null) {
                return UIComponents.label(Component.literal(UiErrors.MISSING_BODY));
            }
            practice = new MeridianPanel(scene, body, selected -> {}, () -> {}, MeridianPanel.Mode.PRACTICE);
            practiceKey = openedKey;
            practiceLocked = false;
            MeridianPanel opened = practice;
            window.scope().addCleanup(() -> {
                // 关闭练习必须先通知停止并释放场景，不影响另一个经脉窗口的选择。
                opened.canvas().dispose();
                if (practice == opened) {
                    practice = null;
                }
                if (practiceKey == openedKey) {
                    practiceKey = null;
                }
            });
            return opened.root();
        });
        ClientPlayNetworking.registerGlobalReceiver(MeridianChannels.OPENING_RESPONSE.type(), (packet, context) -> {
            // 回调已在当前连接的客户端线程，旧连接的回执不会到达这里；空包与超长包直接丢弃。
            if (!MeridianChannels.OPENING_RESPONSE.accepts(packet)) {
                return;
            }
            try {
                receive(ResponseEnvelope.parseFrom(packet.bytes()));
            } catch (IOException invalid) {
                org.slf4j.LoggerFactory.getLogger("meridian_ui").warn(UiErrors.INVALID_UPDATE, invalid);
            }
        });
        bindings.message(new UiWire.Contract<>(new UiMessageRouter.Contract<>("meridian_ui:body",
                "meridian_ui:opening_request", 1, UiMessageRouter.Direction.CLIENT_TO_SERVER,
                MeridianOpeningRequest.class, null), UiWire.Codec.protobuf(MeridianOpeningRequest.getDefaultInstance()), false));
        bindings.message(new UiWire.Contract<>(new UiMessageRouter.Contract<>("meridian_ui:body",
                "meridian_ui:opening_response", 1, UiMessageRouter.Direction.SERVER_TO_CLIENT,
                ResponseEnvelope.class, this::receive), UiWire.Codec.protobuf(ResponseEnvelope.getDefaultInstance()), false));
        bindings.window("meridian_ui:meridian_window", (owner, window) -> {
            // 主窗口展示站姿和经脉，选中目标后直接由画布接收操作快捷键。
            ClientProgression.requireCultivation();
            runtime = owner;
            if (scene == null || body == null) {
                return UIComponents.label(Component.literal(UiErrors.MISSING_BODY));
            }
            panel = new MeridianPanel(scene, body, this::select, this::operate);
            panel.actions(availableActions());
            panelKey = window.key();
            MeridianPanel opened = panel;
            window.scope().addCleanup(() -> {
                // 主窗口关闭也释放附属浮窗，防止脱离身体继续操作。
                opened.canvas().dispose();
                if (panel == opened) {
                    panel = null;
                    panelKey = null;
                    closeDetails();
                }
            });
            return panel.root();
        });
        bindings.window("meridian_ui:meridian_operation_window", (owner, window) -> {
            // 操作入口绑定当前选择，请求中始终使用最新权威 revision。
            ClientProgression.requireCultivation();
            runtime = owner;
            selectOpeningCandidate();
            operationKey = window.key();
            var controls = new OpeningPanel.Controls(this::caption, this::canOperate, this::operate,
                    this::choiceCaption, () -> choices().size() > 1, this::chooseNext, this::focusSelected);
            operation = new OpeningPanel(controls);
            OpeningPanel opened = operation;
            window.scope().addCleanup(() -> {
                // 关窗不会偷偷取消服务端任务，暂停有明确按钮。
                if (operation == opened) {
                    operation = null;
                    operationKey = null;
                    awaitingOperationRefresh = false;
                }
            });
            show();
            return opened.root();
        });
    }

    void present(BodyScene scene, BodySnapshot body) {
        // 模型仅在身体切换时替换，连接能力由宿主另行绑定。
        this.scene = scene;
        this.body = body;
        selected = null;
        error = "";
        pending = "";
        awaitingOperationRefresh = false;
    }

    void update(BodySnapshot body) {
        // 新状态更新所有已打开窗口，但不重新创建相机和组件树。
        this.body = body;
        if (panel != null) {
            panel.update(body);
        }
        if (practice != null) {
            practice.update(body);
        }
        if (selected != null && (!BodyScene.exists(body, selected) || !BodyScene.realmAllows(body, selected))) {
            closeDetails();
        }
        show();
    }

    void bind(List<MeridianOpeningRule> rules, Consumer<MeridianOpeningRequest> sender) {
        // 本地宿主和其他服务端同样通过生成消息提供操作能力。
        this.rules = List.copyOf(rules);
        this.sender = sender;
        show();
    }

    void options(List<MeridianOpeningRule> rules) {
        // 选项存在即替换；空表也能撤销原连接的能力。
        this.rules = List.copyOf(rules);
        bindFabric();
        show();
    }

    void protocolReady(ClientRuntime runtime) {
        // 生产连接固定使用必装宿主；本地样例通过 bind 显式提供发送器。
        this.runtime = runtime;
        bindFabric();
    }

    private void bindFabric() {
        // 缺少声明是整套装配错误，不自动切换另一条请求路径。
        if (!ClientPlayNetworking.canSend(MeridianChannels.OPENING_REQUEST.type())) {
            throw new IllegalStateException(UiErrors.REQUIRED_HOST_CHANNEL + MeridianChannels.OPENING_REQUEST.type().id());
        }
        sender = request -> {
            // 仅传意图与快照版本；时间、身份与资源均由服务器独立判定。
            ClientPlayNetworking.send(MeridianChannels.OPENING_REQUEST.payload(request.toByteArray()));
        };
    }

    private void select(BodyVisual node) {
        // 选择只更新快捷键目标；再次点击同一经脉保留所选操作，避免重复选中改变动作。
        if (selected == null || !selected.getTargetId().equals(node.getTargetId())) {
            chosenOperation = "";
        }
        selected = node;
        error = "";
        show();
    }

    private void focusSelected() {
        // 操作窗与主画布共享当前选择，不改变操作目标或请求状态。
        if (panel != null) {
            panel.canvas().focusSelected();
        }
    }

    private MeridianOpeningRule rule() {
        // 规则按目标身份匹配，不假定 Human 命名或数量。
        if (selected == null) {
            return null;
        }
        if (body != null && OpeningInformation.active(body) && OpeningInformation.targets(body, selected.getTargetId())) {
            return body.getOpeningTask().getRule();
        }
        return rules.stream().filter(rule -> rule.getTargetMeridianId().equals(selected.getTargetId()))
                .findFirst().orElse(null);
    }

    private String caption() {
        // 同一按钮为自己的进行中任务提供显式暂停。
        if (!pending.isEmpty() || awaitingOperationRefresh) {
            return "等待确认…";
        }
        MeridianOperationView view = chosen();
        if (view == null) {
            return "暂无可用操作";
        }
        return view.getActionLabel();
    }

    private boolean canOperate() {
        // 客户端可用性仅防止明显重复，最低储量和所有资源仍由宿主校验。
        if (!ClientProgression.canCultivate()) {
            return false;
        }
        if (body == null || selected == null || !pending.isEmpty() || awaitingOperationRefresh
                || body.getVitality().getDead()) {
            return false;
        }
        MeridianOperationView view = chosen();
        return view != null && view.getEnabled();
    }

    private void operate() {
        // 从最新快照构造一次请求，先标记待确认，防止同步回执或连续点击重复调元。
        if (!canOperate()) {
            return;
        }
        revisionRetries = 0;
        sendOperation(chosen());
    }

    private void sendOperation(MeridianOperationView view) {
        // 每次尝试使用最新权威版本及新请求身份；已拒绝的回执不能被当作成功重放。
        pending = UUID.randomUUID().toString();
        sentAt = System.nanoTime();
        error = "";
        pendingOperation = view.getId();
        pendingAction = view.getActionId();
        pendingRevision = body.getRevision();
        var request = MeridianOperationRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(pending)
                .setBodyId(body.getId())
                .setExpectedRevision(pendingRevision)
                .setMeridianId(view.getMeridianId())
                .setOperationId(view.getId())
                .setOperationVersion(view.getVersion())
                .setActionId(pendingAction)
                .build();
        show();
        operations.send(request);
    }

    private void retryRefreshedOperation() {
        // 收完当前帧的快照再取最新卡片，不能在一串回执的中间版本上立刻重发。
        if (!awaitingOperationRefresh || body == null || body.getRevision() <= pendingRevision) {
            return;
        }
        MeridianOperationView view = chosen();
        if (view == null || !view.getId().equals(pendingOperation) || !view.getActionId().equals(pendingAction)) {
            awaitingOperationRefresh = false;
            return;
        }
        if (!view.getEnabled()) {
            return;
        }
        awaitingOperationRefresh = false;
        revisionRetries++;
        sendOperation(view);
    }

    void receive(ResponseEnvelope response) {
        // 回执只确认当前请求；成功后等待身体快照显示进度，不能本地标记已拓宽。
        if (!Minecraft.getInstance().isSameThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
        if (response.getProtocolVersion() != 1 || pending.isEmpty() || !response.getRequestId().equals(pending)) {
            return;
        }
        pending = "";
        if (response.hasFailure()
                && response.getFailure().getCode()
                == dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.REVISION_CONFLICT
                && revisionRetries < MAX_REVISION_RETRIES) {
            awaitingOperationRefresh = true;
            show();
            return;
        }
        if (response.hasFailure() && pendingOperation.equals(chosenOperation)) {
            if (pendingOperation.startsWith("meridian:opening/")) {
                error = UiErrors.openingFailure(response.getFailure().getCode());
            } else {
                error = UiErrors.operationFailure(response.getFailure().getCode());
            }
        }
        show();
    }

    private void show() {
        // 只投影通用库存摘要，操作卡来自内容包，客户端不重新裁定开脉门槛。
        if (panel != null) {
            panel.actions(availableActions());
        }
        if (body == null || selected == null || operation == null) {
            return;
        }
        MeridianOperationView choice = chosen();
        if (choice == null) {
            choice = OpeningCards.summary(body, selected.getTargetId()).toBuilder()
                    .setStatus("暂无可用操作")
                    .setHint(UiErrors.OPENING_UNAVAILABLE)
                    .build();
        }
        operation.show(selected.getDisplayName(), choice, error);
    }

    private List<MeridianAction> availableActions() {
        // 常用窗口与条件操作共享一条有序队列；隐藏项不会留下空位。
        if (body == null || body.getVitality().getDead()) {
            return List.of();
        }
        var actions = new ArrayList<MeridianAction>();
        if (ClientProgression.canGather()) {
            actions.add(windowAction("cultivation", "吐纳", "breathing", "打开独立吐纳窗口。"));
        }
        var provider = MeridianSceneExtension.required();
        if (ClientProgression.canCultivate() && provider.lockedReason().isEmpty()) {
            actions.add(windowAction("practice", "周天", "circulation", "打开独立周天窗口。"));
        }
        if (canOpenOperation()) {
            actions.add(windowAction("meridian_operation", "开脉", "opening", "选择当前可开拓的经脉。"));
        }
        actions.addAll(provider.actions(body));
        return List.copyOf(actions);
    }

    private MeridianAction windowAction(String feature, String label, String icon, String hint) {
        // 重复打开交给工作区聚焦已有独立窗口，不创建嵌套页面。
        var texture = Identifier.fromNamespaceAndPath("meridian_ui", "textures/gui/actions/" + icon + ".png");
        return new MeridianAction("meridian-rail-meridian_ui-" + feature, label, texture, hint, () -> {
            if (feature.equals("meridian_operation")) {
                selectOpeningCandidate();
            }
            runtime.open("meridian_ui:" + feature, null);
        });
    }

    boolean canOpenOperation() {
        // 首次入口只在有可执行的当前阶段目标时出现；活动任务保留暂停入口。
        return ClientProgression.canCultivate() && openingCandidate() != null;
    }

    private MeridianOperationView openingCandidate() {
        // 优先保留正在进行或已选中的目标，其余按权威目录顺序选择。
        if (body == null || sender == null || body.getVitality().getDead()) {
            return null;
        }
        List<MeridianOperationView> available = body.getMeridianStatesList().stream()
                .flatMap(state -> operations.views(body, state.getId()).stream())
                .filter(view -> view.getId().startsWith("meridian:opening/"))
                .filter(view -> view.getEnabled() || view.getActive())
                .toList();
        for (MeridianOperationView candidate : available) {
            if (candidate.getActive() || selected != null
                    && candidate.getMeridianId().equals(selected.getTargetId())) {
                return candidate;
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        return available.get(0);
    }

    private void selectOpeningCandidate() {
        // 入口不要求先精准点中细小经脉，自动聚焦服务端认可的可开拓目标。
        MeridianOperationView candidate = openingCandidate();
        if (candidate == null) {
            return;
        }
        BodyVisual node = scene.presentation().getVisualsList().stream()
                .filter(visual -> visual.getTargetKind() == BodyTargetKind.MERIDIAN)
                .filter(visual -> visual.getTargetId().equals(candidate.getMeridianId()))
                .findFirst().orElseThrow();
        select(node);
        if (panel != null) {
            panel.canvas().highlight(node);
        }
    }

    private List<MeridianOperationView> choices() {
        // 同一目标可以同时获得多个附属提供的操作，目录不假定功法数量。
        if (body == null || selected == null) {
            return List.of();
        }
        // 内容可用与传输可用分别判断；未绑定宿主不能让按钮提交后一直等待。
        boolean openingConnected = sender != null && rule() != null;
        var availableViews = new ArrayList<MeridianOperationView>();
        for (MeridianOperationView view : operations.views(body, selected.getTargetId())) {
            if (view.getId().startsWith("meridian:opening/") && !openingConnected) {
                view = view.toBuilder().setEnabled(false).setStatus("操作连接不可用")
                        .setHint(UiErrors.OPENING_UNAVAILABLE).build();
            }
            availableViews.add(view);
        }
        return List.copyOf(availableViews);
    }

    private MeridianOperationView chosen() {
        // 快照刷新保留选择；操作目录变化后选中仍可用的入口。
        List<MeridianOperationView> choices = choices();
        MeridianOperationView view = choices.stream()
                .filter(item -> item.getId().equals(chosenOperation)).findFirst().orElse(null);
        if (view == null && !choices.isEmpty()) {
            view = choices.get(0);
            chosenOperation = view.getId();
        }
        return view;
    }

    private String choiceCaption() {
        // 多来源显示命名空间，避免同名功法无法区分；选择本身没有资源副作用。
        MeridianOperationView view = chosen();
        if (view == null) {
            return "暂无操作";
        }
        String title = view.getTitle();
        if (choices().size() > 1) {
            title += " · " + view.getId().split(":", 2)[0] + " ▸";
        }
        return title;
    }

    private void chooseNext() {
        // 只切换当前卡片，直到明确点击开始才发意图。
        List<MeridianOperationView> choices = choices();
        if (choices.size() < 2) {
            return;
        }
        int index = choices.indexOf(chosen());
        chosenOperation = choices.get((index + 1) % choices.size()).getId();
        error = "";
        show();
    }

    void tick() {
        // UI tick 只处理客户端请求超时，不推进身体时间或开脉进度。
        retryRefreshedOperation();
        if (operation != null) {
            operation.focusActions();
        }
        if ((!pending.isEmpty() || awaitingOperationRefresh)
                && System.nanoTime() - sentAt > REQUEST_TIMEOUT_NANOS) {
            pending = "";
            awaitingOperationRefresh = false;
            error = UiErrors.OPENING_TIMEOUT;
            show();
        }
    }

    private void closeDetails() {
        // 复制窗口身份再关闭，scope 回调可以安全清空对应引用。
        WindowKey controls = operationKey;
        if (runtime != null && controls != null) {
            runtime.workspace().windows().close(controls);
        }
        selected = null;
        awaitingOperationRefresh = false;
    }

    boolean open() {
        // 身体替换前需要检查所有经脉窗口。
        return panel != null || operation != null || practice != null;
    }

    MeridianPanel panel() {
        // 演示验收使用同一个生产场景进行真实点击。
        return panel;
    }

    MeridianPanel practicePanel() {
        // 专属练习拥有独立窗口，宿主检查布局时不能误取普通经脉窗口。
        return practice;
    }

    io.wispforest.owo.ui.core.UIComponent operationRoot() {
        // 与主窗口一致，公开当前组件供宿主检查布局；关闭后不保留组件引用。
        return operation == null ? null : operation.root();
    }

    void clear() {
        // 断线释放显示能力与旧意图，不能将前一个服务器的规则带入新连接。
        if (runtime != null && practiceKey != null) {
            runtime.workspace().windows().close(practiceKey);
        }
        closeDetails();
        if (runtime != null && panelKey != null) {
            runtime.workspace().windows().close(panelKey);
        }
        body = null;
        scene = null;
        rules = List.of();
        sender = null;
        pending = "";
        error = "";
        operations.clear();
        registerOpening();
        chosenOperation = "";
        pendingOperation = "";
    }

    void revoke() {
        // 撤销修炼页时专属弹窗同样停止，不能保留独立活动。
        if (runtime != null && practiceKey != null) {
            runtime.workspace().windows().close(practiceKey);
        }
        // 重置只撤销当前修炼窗口和待处理意图，基础身体检视仍可保留。
        closeDetails();
        if (runtime != null && panelKey != null) {
            runtime.workspace().windows().close(panelKey);
        }
        pending = "";
        pendingOperation = "";
        chosenOperation = "";
        rules = List.of();
        operations.clear();
        registerOpening();
    }

    void progressChanged() {
        // 细分残卷撤权不等同于隐藏整个修炼页；锁定态一变就关闭练习窗，撤权停止活动，解锁后重开即得真实控件。
        if (runtime == null || practiceKey == null) {
            return;
        }
        boolean locked = !dev.kizuna.meridian.ui.api.MeridianSceneExtension.required().lockedReason().isEmpty();
        if (locked != practiceLocked) {
            runtime.workspace().windows().close(practiceKey);
        }
    }
}
