package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import io.wispforest.owo.ui.component.Components;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 通用窗口入口；宿主提供 Protobuf 身体及生物包的资源标识。 */
public final class BodyUiModule implements ClientModule {
    private BodyScene scene;
    private BodySnapshot body;
    private BodyPanel panel;
    private CultivationPanel cultivationPanel;
    private GatheringView gathering;
    private Consumer<GatheringRequest> gatheringSender;
    private String pendingRequest = "";
    private String gatheringError = "";
    private long breathSequence;
    private BreathControl queuedBreath;

    @Override
    public FrameworkCatalog.Module definition() {
        // 查看与操作分别注册入口，同一模块共享权威快照及聚气消息通道。
        return FrameworkCatalog.Module.of("meridian_ui:body", Set.of("kiui:core"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:body_window", "meridian_ui:body",
                        580, 400, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:body", "身体", "修炼", "meridian_ui:body_icon", "meridian_ui:body_window"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:cultivation_window", "meridian_ui:body",
                        560, 450, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:cultivation", "吐纳", "修炼",
                        "meridian_ui:cultivation_icon", "meridian_ui:cultivation_window"));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 同一生成消息同时支持本地宿主和已协商的网络路由，客户端不依赖权威实现。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 输入租约按客户端 tick 续期，渲染帧率不会改变控制消息的频率。
            if (cultivationPanel != null) {
                cultivationPanel.canvas().tickInput();
            }
        });
        bindings.message(new UiWire.Contract<>(new UiMessageRouter.Contract<>("meridian_ui:body",
                "meridian_ui:gathering_request", 1, UiMessageRouter.Direction.CLIENT_TO_SERVER,
                GatheringRequest.class, null), UiWire.Codec.protobuf(GatheringRequest.getDefaultInstance()), false));
        bindings.message(new UiWire.Contract<>(new UiMessageRouter.Contract<>("meridian_ui:body",
                "meridian_ui:gathering_response", 1, UiMessageRouter.Direction.SERVER_TO_CLIENT,
                GatheringResponse.class, this::receiveGathering), UiWire.Codec.protobuf(GatheringResponse.getDefaultInstance()), false));
        // 没有宿主数据时显示等待状态，不偷偷生成默认人体。
        bindings.window("meridian_ui:body_window", (runtime, window) -> {
            if (scene == null || body == null) {
                return Components.label(Text.literal(UiErrors.MISSING_BODY));
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
        });
        bindings.window("meridian_ui:cultivation_window", (runtime, window) -> {
            // 内观模型仍由生物附属提供；没有身体时只显示等待状态。
            if (scene == null || body == null) {
                return Components.label(Text.literal(UiErrors.MISSING_BODY));
            }
            cultivationPanel = new CultivationPanel(scene, this::controlBreathing);
            showGathering();
            CultivationPanel opened = cultivationPanel;
            window.scope().addCleanup(() -> {
                if (cultivationPanel == opened) {
                    opened.close();
                    cultivationPanel = null;
                }
            });
            refreshGathering();
            return cultivationPanel.root();
        });
    }

    public void present(BodySnapshot snapshot, Identifier resource) {
        // 只接受客户端线程上的宿主交付；资源按 Minecraft 资源管理器查找，支持内容包覆盖。
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
        try (var stream = MinecraftClient.getInstance().getResourceManager().open(resource)) {
            BodyScene candidate = new BodyScene(stream.readNBytes(BodyScene.MAX_BYTES + 1));
            candidate.requireTemplate(snapshot);
            if (panel != null || (body != null && cultivationPanel != null)) {
                throw UiErrors.invalid(UiErrors.WINDOW_OPEN);
            }
            scene = candidate;
            body = snapshot;
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
        if (!MinecraftClient.getInstance().isOnThread() || body == null || scene == null
                || !body.getId().equals(snapshot.getId()) || snapshot.getRevision() < body.getRevision()) {
            throw UiErrors.invalid(UiErrors.INVALID_UPDATE);
        }
        scene.requireTemplate(snapshot);
        if (panel != null) {
            panel.update(snapshot);
        }
        body = snapshot;
    }

    public BodyPanel panel() {
        // 宿主可检查当前窗口，未打开时返回空。
        return panel;
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
        if (body != null && gatheringSender != null && pendingRequest.isEmpty()) {
            sendGathering(GatheringRequest.newBuilder().setInspect(InspectGathering.getDefaultInstance()));
        }
    }

    private void controlBreathing(BreathControl control) {
        // 背压只保留最新方向；停止意图不会因为上一份回执尚未到达而丢失。
        if (body == null || gatheringSender == null) {
            return;
        }
        queuedBreath = control;
        if (pendingRequest.isEmpty()) {
            queuedBreath = null;
            sendGathering(GatheringRequest.newBuilder().setBreath(control.toBuilder().setSequence(++breathSequence)));
        }
    }

    private void sendGathering(GatheringRequest.Builder request) {
        // 请求发出后等业务回执，重复按钮不会排出多次摄入。
        pendingRequest = UUID.randomUUID().toString();
        gatheringError = "";
        showGathering();
        gatheringSender.accept(request.setProtocolVersion(1).setRequestId(pendingRequest).setBodyId(body.getId()).build());
    }

    public void receiveGathering(GatheringResponse response) {
        // 所有响应在客户端线程交付，迟到的旧身体或旧版本不会覆盖当前窗口。
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw UiErrors.invalid(UiErrors.WRONG_THREAD);
        }
        if (response.getProtocolVersion() != 1 || body == null) {
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
        } else if (response.hasView() && response.getView().getBody().getId().equals(body.getId())
                && response.getView().getBody().getRevision() >= body.getRevision()
                && (gathering == null || response.getView().getBreathing().getSequence() >= gathering.getBreathing().getSequence())) {
            gathering = response.getView();
            breathSequence = Math.max(breathSequence, gathering.getBreathing().getSequence());
            update(gathering.getBody());
        }
        showGathering();
        if (pendingRequest.isEmpty() && queuedBreath != null) {
            controlBreathing(queuedBreath);
        }
    }

    private void showGathering() {
        // 窗口关闭期间只保留最后的只读投影，再打开时恢复当前阶段。
        if (cultivationPanel != null) {
            cultivationPanel.update(gathering, !pendingRequest.isEmpty(), gatheringError);
        }
    }

    @Override
    public void protocolReady(ClientRuntime runtime) {
        // 网络输送交给 Inventory UI；业务结果由独立 Protobuf 响应更新。
        bindGathering(request -> runtime.protocol().request("meridian_ui:gathering_request", request,
                System.currentTimeMillis()).thenAccept(outcome -> MinecraftClient.getInstance().execute(() -> {
                    // 连接拒绝或超时允许重新查询；不自动用新 ID 重复启动。
                    if (!outcome.accepted() && pendingRequest.equals(request.getRequestId())) {
                        pendingRequest = "";
                        gatheringError = UiErrors.GATHERING_UNAVAILABLE;
                        showGathering();
                    }
                })));
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 断线清除身体引用，不能把旧服务器的数据带到下一次连接。
        body = null;
        scene = null;
        panel = null;
        cultivationPanel = null;
        gathering = null;
        gatheringSender = null;
        pendingRequest = "";
        gatheringError = "";
        queuedBreath = null;
        breathSequence = 0;
    }
}
