package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import io.wispforest.owo.ui.component.Components;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.io.IOException;
import java.util.Set;

/** 通用窗口入口；宿主提供 Protobuf 身体及生物包的资源标识。 */
public final class BodyUiModule implements ClientModule {
    private BodyScene scene;
    private BodySnapshot body;
    private BodyPanel panel;

    @Override
    public FrameworkCatalog.Module definition() {
        // 只声明通用身体查看窗口，不登记任何人体模型或人体 ID。
        return FrameworkCatalog.Module.of("meridian_ui:body", Set.of("kiui:core"),
                new FrameworkCatalog.Window(new UiWindowDefinition("meridian_ui:body_window", "meridian_ui:body",
                        580, 400, Set.of(UiWindowDefinition.Capability.WINDOW))),
                new FrameworkCatalog.Feature("meridian_ui:body", "身体", "修炼", "meridian_ui:body_icon", "meridian_ui:body_window"));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 没有宿主数据时显示等待状态，不偷偷生成默认人体。
        bindings.window("meridian_ui:body_window", (runtime, window) -> {
            if (scene == null || body == null) {
                return Components.label(Text.literal(UiErrors.MISSING_BODY));
            }
            panel = new BodyPanel(scene, body);
            BodyPanel opened = panel;
            window.scope().addCleanup(() -> {
                if (panel == opened) {
                    panel = null;
                }
            });
            return panel.root();
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
            if (panel != null) {
                throw UiErrors.invalid(UiErrors.WINDOW_OPEN);
            }
            scene = candidate;
            body = snapshot;
        } catch (IOException failure) {
            throw UiErrors.invalid(UiErrors.RESOURCE_UNREADABLE + resource);
        }
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

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 断线清除身体引用，不能把旧服务器的数据带到下一次连接。
        body = null;
        scene = null;
        panel = null;
    }
}
