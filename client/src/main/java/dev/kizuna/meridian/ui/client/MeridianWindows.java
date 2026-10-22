package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import io.wispforest.owo.ui.component.Components;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 经脉窗口与操作意图编排；只读快照、网络回执和镜头各自独立。 */
final class MeridianWindows {
    private static final Identifier REQUEST = new Identifier("meridian", "opening_request");
    private static final Identifier RESPONSE = new Identifier("meridian", "opening_response");
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
                return Components.label(Text.literal(UiErrors.MISSING_BODY));
            }
            practice = new MeridianPanel(scene, body, selected -> {}, () -> {}, true);
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
        ClientPlayNetworking.registerGlobalReceiver(RESPONSE, (client, handler, buffer, responseSender) -> {
            if (buffer.readableBytes() == 0 || buffer.readableBytes() > 1024 * 1024) {
                return;
            }
            byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            client.execute(() -> {
                // 旧连接迟到回执不能改变新连接的请求状态。
                if (client.getNetworkHandler() != handler) {
                    return;
                }
                try {
                    receive(ResponseEnvelope.parseFrom(payload));
                } catch (IOException invalid) {
                    org.slf4j.LoggerFactory.getLogger("meridian_ui").warn(UiErrors.INVALID_UPDATE, invalid);
                }
            });
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
                return Components.label(Text.literal(UiErrors.MISSING_BODY));
            }
            panel = new MeridianPanel(scene, body, this::select, this::operate);
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
            operationKey = window.key();
            operation = new OpeningPanel(this::caption, this::canOperate, this::operate,
                    this::choiceCaption, () -> choices().size() > 1, this::chooseNext, this::focusSelected);
            OpeningPanel opened = operation;
            window.scope().addCleanup(() -> {
                // 关窗不会偷偷取消服务端任务，暂停有明确按钮。
                if (operation == opened) {
                    operation = null;
                    operationKey = null;
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
        if (!ClientPlayNetworking.canSend(REQUEST)) {
            throw new IllegalStateException(UiErrors.REQUIRED_HOST_CHANNEL + REQUEST);
        }
        sender = request -> {
            // 仅传意图与快照版本；时间、身份与资源均由服务器独立判定。
            var buffer = PacketByteBufs.create();
            buffer.writeBytes(request.toByteArray());
            ClientPlayNetworking.send(REQUEST, buffer);
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
        if (!pending.isEmpty()) {
            return "等待确认…";
        }
        MeridianOperationView view = chosen();
        return view == null ? "暂无可用操作" : view.getActionLabel();
    }

    private boolean canOperate() {
        // 客户端可用性仅防止明显重复，最低储量和所有资源仍由宿主校验。
        if (!ClientProgression.canCultivate()) {
            return false;
        }
        if (body == null || selected == null || !pending.isEmpty() || body.getVitality().getDead()) {
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
        pending = UUID.randomUUID().toString();
        sentAt = System.nanoTime();
        error = "";
        MeridianOperationView view = chosen();
        pendingOperation = view.getId();
        var request = MeridianOperationRequest.newBuilder().setProtocolVersion(1).setRequestId(pending)
                .setBodyId(body.getId()).setExpectedRevision(body.getRevision()).setMeridianId(selected.getTargetId())
                .setOperationId(view.getId()).setOperationVersion(view.getVersion()).setActionId(view.getActionId());
        show();
        operations.send(request.build());
    }

    void receive(ResponseEnvelope response) {
        // 回执只确认当前请求；成功后等待身体快照显示进度，不能本地标记已拓宽。
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
        if (response.getProtocolVersion() != 1 || pending.isEmpty() || !response.getRequestId().equals(pending)) {
            return;
        }
        pending = "";
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
        if (body == null || selected == null || operation == null) {
            return;
        }
        MeridianOperationView choice = chosen();
        if (choice == null) {
            choice = OpeningCards.summary(body, selected.getTargetId()).toBuilder()
                    .setStatus("暂无可用操作").setHint(UiErrors.OPENING_UNAVAILABLE).build();
        }
        operation.show(selected.getDisplayName(), choice, error);
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
        if (operation != null) {
            operation.focusActions();
        }
        if (!pending.isEmpty() && System.nanoTime() - sentAt > 5_000_000_000L) {
            pending = "";
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

    io.wispforest.owo.ui.core.Component operationRoot() {
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
