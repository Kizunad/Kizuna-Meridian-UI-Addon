package dev.kizuna.meridian.ui.demo;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.ui.client.*;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import java.nio.file.Files;

/** 整体交互回归：真实 Screen 点击、浮窗、权威运输与生命周期。 */
final class OpeningPreviewCheck {
    private BodyUiModule module;
    private GatheringPreviewHost host;
    private int ticks;
    private int age;
    private WindowKey mainKey;
    private String target;
    private int clickX;
    private int clickY;
    private byte[] beforeChoice;
    private Runnable removeAddon;
    private int addonRequests;

    void register() {
        // 演示数据和验收只属于 demo 源集，不进入生产客户端。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("经脉窗口整体验收失败", failure);
            }
        });
    }

    private void tick(MinecraftClient client) throws Exception {
        // 限时且只在资源加载完成后操作，失败保留截图和堆栈。
        if (++age > 700) {
            throw new IllegalStateException("经脉窗口验收超时");
        }
        if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
            client.setScreen(new TitleScreen());
        }
        if (module == null && client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
            client.options.getGuiScale().setValue(2);
            client.onResolutionChanged();
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
            var origin = TimeCursor.newBuilder().setDomain("demo:opening").setEpoch("preview").setElapsedMillis(0).build();
            var initial = BodySnapshot.parseFrom(HumanBirth.candidate(HumanContent.bundle().getTemplates(0), "preview-body", origin)).toBuilder();
            for (int index = 0; index < initial.getMeridianStatesCount(); index++) {
                // 可信样例预装满储量，隔离 UI 请求流程，不改变实际玩家出生条件。
                MeridianState state = initial.getMeridianStates(index);
                double capacity = initial.getStructure().getMeridiansList().stream()
                        .filter(definition -> definition.getId().equals(state.getId())).findFirst().orElseThrow().getLumenCapacity();
                initial.setMeridianStates(index, state.toBuilder().setLumenQi(QiInventory.newBuilder()
                        .addEntries(Qi.newBuilder().setPhase(QiPhase.FREE).setAttribute("meridian:none").setAmount(capacity))));
            }
            module.present(initial.build(), new Identifier("meridian_human", "meridian/adult.pb"));
            removeAddon = module.registerMeridianOperations("preview", request -> {
                // 第二个附属使用生成消息收取意图；模拟服务端拒绝，不能让 UI 乐观改账。
                addonRequests++;
                if (request.getExpectedRevision() != host.view().getBody().getRevision()) {
                    throw new IllegalStateException("附属操作使用了过期身体版本");
                }
                module.receiveMeridianOperation(dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope.newBuilder()
                        .setProtocolVersion(1).setRequestId(request.getRequestId())
                        .setFailure(dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure.newBuilder()
                                .setCode(dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.ACCESS_DENIED)).build());
            });
            host = new GatheringPreviewHost(initial.build(), module);
            var runtime = InventoryUiClient.runtime();
            runtime.openWorkspace();
            mainKey = runtime.open("meridian_ui:meridians", null).key();
        }
        if (module == null) {
            return;
        }
        ticks++;
        host.tick(ticks * 50L);
        if (ticks == 20) {
            capture("opening-overview.png");
            verifyPixels(client);
            findClick();
            // 单击和连续两次点击都只选中目标，不应打开浮窗或提交身体变更。
            beforeChoice = host.view().getBody().toByteArray();
            click(clickX, clickY);
            requireNoDetails();
            click(clickX, clickY);
            click(clickX, clickY);
            requireNoDetails();
            if (!java.util.Arrays.equals(beforeChoice, host.view().getBody().toByteArray()) || addonRequests != 0) {
                throw new IllegalStateException("经脉选择提交了身体变更或附属请求");
            }
        }
        if (ticks == 30) {
            // 没有操作浮窗时仍通过真实画布焦点执行 F 和 G。
            capture("opening-after-click.png");
            key(org.lwjgl.glfw.GLFW.GLFW_KEY_F);
            module.meridianPanel().canvas().camera().settle();
            if (module.meridianPanel().canvas().camera().zoom() <= 1) {
                throw new IllegalStateException("F 未聚焦当前经脉");
            }
            key(org.lwjgl.glfw.GLFW.GLFW_KEY_G);
            if (!host.view().getBody().hasOpeningTask()) {
                throw new IllegalStateException("画布 G 未提交权威任务");
            }
            requireNoDetails();
        }
        if (ticks == 31) {
            // 再次按 G 应暂停同一任务，也不能引入自动弹窗。
            key(org.lwjgl.glfw.GLFW.GLFW_KEY_G);
            if (host.view().getBody().getOpeningTask().getStatus() != OpeningTaskStatus.OPENING_PAUSED) {
                throw new IllegalStateException("画布 G 未暂停任务");
            }
            requireNoDetails();
        }
        if (ticks == 32) {
            // 通用卡片由宿主显式打开，独立覆盖多附属与布局回归。
            module.updateMeridianOperations(dev.kizuna.meridian.protocol.pb.OperationUiMessages.MeridianOperationUpdate.newBuilder()
                    .setProtocolVersion(1).setNamespace("preview").setBodyId(host.view().getBody().getId())
                    .setBodyRevision(host.view().getBody().getRevision())
                    .addOperations(dev.kizuna.meridian.protocol.pb.OperationUiMessages.MeridianOperationView.newBuilder()
                            .setId("preview:practice").setVersion(1).setMeridianId(target).setTitle("附属练习")
                            .setActionId("start").setActionLabel("开始操作").setEnabled(true)).build());
            InventoryUiClient.runtime().open("meridian_ui:meridian_operation", null);
            requireWindow("meridian_ui:meridian_operation_window");
        }
        if (ticks == 35) {
            requireWindow("meridian_ui:meridian_operation_window");
            beforeChoice = host.view().getBody().toByteArray();
            clickControl("gather-choice");
            // 第三方操作允许没有状态文案，空槽也必须安全响应悬浮与点击。
            hoverAndClickControl("status");
            hoverAndClickControl("feedback");
            if (!java.util.Arrays.equals(beforeChoice, host.view().getBody().toByteArray())) {
                throw new IllegalStateException("汇聚选项改变了资源或开脉任务");
            }
            clickControl("opening-action");
            if (addonRequests != 1 || !java.util.Arrays.equals(beforeChoice, host.view().getBody().toByteArray())) {
                throw new IllegalStateException("附属请求未独立路由或拒绝后改变了身体");
            }
            hoverAndClickControl("feedback");
            clickControl("gather-choice");
            hoverAndClickControl("feedback");
        }
        if (ticks == 36) {
            // 在正常 tick 恢复操作焦点后验证 G，不跳过窗口自己的焦点管理。
            key(org.lwjgl.glfw.GLFW.GLFW_KEY_G);
            if (!host.view().getBody().hasOpeningTask()) {
                throw new IllegalStateException("开始操作未提交权威任务");
            }
        }
        if (ticks == 40) {
            // 给真实渲染一帧显示最新任务，避免截图仍停在点击前的准备状态。
            capture("opening-operation.png");
            module.meridianPanel().canvas().overview();
            focusMain();
        }
        if (ticks == 70) {
            removeAddon.run();
            if (host.view().getBody().getTransitsCount() == 0) {
                throw new IllegalStateException("开脉没有实际在途真元");
            }
            capture("opening-flow.png");
            // 旋转和缩放仍经过同一场景，快照更新不能重建相机。
            BodyCanvas canvas = module.meridianPanel().canvas();
            client.currentScreen.mouseClicked(canvas.x() + 20, canvas.y() + 20, 0);
            client.currentScreen.mouseDragged(canvas.x() + 35, canvas.y() + 25, 0, 15, 5);
            client.currentScreen.mouseReleased(canvas.x() + 35, canvas.y() + 25, 0);
            canvas.camera().settle();
            float yaw = canvas.camera().yaw();
            host.publishBody();
            if (canvas.camera().yaw() != yaw) {
                throw new IllegalStateException("快照覆盖了用户镜头");
            }
        }
        if (ticks == 80) {
            focus("meridian_ui:meridian_operation_window");
            key(org.lwjgl.glfw.GLFW.GLFW_KEY_G);
            if (host.view().getBody().getOpeningTask().getStatus() != OpeningTaskStatus.OPENING_PAUSED) {
                throw new IllegalStateException("暂停按钮未停止任务");
            }
            // 极小视口继续保持操作栏在窗内，正文用滚动区承载。
            client.options.getGuiScale().setValue(4);
            client.onResolutionChanged();
        }
        if (ticks == 88) {
            Component root = operationRoot();
            Component button = ((io.wispforest.owo.ui.container.FlowLayout) root)
                    .childById(Component.class, "opening-action");
            if (button.y() < root.y() || button.y() + button.height() > root.y() + root.height()) {
                throw new IllegalStateException("小窗口操作栏超出边界");
            }
            capture("opening-small-window.png");
            InventoryUiClient.runtime().workspace().windows().close(mainKey);
            if (module.meridianPanel() != null || windows().stream().anyMatch(window ->
                    window.key().windowType().startsWith("meridian_ui:meridian_"))) {
                throw new IllegalStateException("主窗口关闭未释放浮窗");
            }
            Files.writeString(client.runDirectory.toPath().resolve("opening-smoke-ok.txt"),
                    "standing silhouette; realm-filtered picking; single/double click without popup; canvas F focus and G start/pause; explicit addon panel; addon isolation; optional text hover/click; authoritative transits; camera preserved; compact layout; cleanup");
            System.out.println("MERIDIAN_OPENING_SMOKE_OK: " + target);
            client.scheduleStop();
        }
    }

    private void findClick() {
        // 在真实模型上找一个可见脉，不靠固定人体坐标伪造选中。
        BodyCanvas canvas = module.meridianPanel().canvas();
        for (int y = 15; y < canvas.height() - 15; y += 2) {
            for (int x = 15; x < canvas.width() - 15; x += 2) {
                var hit = canvas.pick(x, y);
                if (hit != null) {
                    if (!BodyScene.realmAllows(host.view().getBody(), hit)) {
                        throw new IllegalStateException("未解锁经脉仍可被选中");
                    }
                    target = hit.getTargetId();
                    clickX = canvas.x() + x;
                    clickY = canvas.y() + y;
                    return;
                }
            }
        }
        throw new IllegalStateException("模型中找不到可点击经脉");
    }

    private void verifyPixels(MinecraftClient client) {
        // 命中可用还需 GPU 真正产生黄色经脉像素，防止离屏合成失败。
        BodyCanvas canvas = module.meridianPanel().canvas();
        double scale = client.getWindow().getScaleFactor();
        int count = 0;
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            for (int y = (int) (canvas.y() * scale); y < (canvas.y() + canvas.height()) * scale; y++) {
                for (int x = (int) (canvas.x() * scale); x < (canvas.x() + canvas.width()) * scale; x++) {
                    int color = image.getColor(x, y);
                    int red = color & 255;
                    int green = color >> 8 & 255;
                    int blue = color >> 16 & 255;
                    if (red > green && green > blue + 8 && red > 30) {
                        count++;
                    }
                }
            }
        }
        if (count < 30) {
            throw new IllegalStateException("场景未绘制淡黄色经脉");
        }
    }

    private void clickControl(String id) {
        // 按钮输入走真实工作台 Screen，不直接调用业务回调。
        var root = (io.wispforest.owo.ui.container.FlowLayout) operationRoot();
        Component button = root.childById(Component.class, id);
        click(button.x() + button.width() / 2, button.y() + button.height() / 2);
    }

    private void hoverAndClickControl(String id) {
        // 用实际组件树执行 tooltip 遍历，覆盖 owo 空 Label 曾访问第 -1 行的崩溃路径。
        var client = MinecraftClient.getInstance();
        var root = (io.wispforest.owo.ui.container.FlowLayout) operationRoot();
        Component control = root.childById(Component.class, id);
        int mouseX = control.x() + 1;
        int mouseY = control.y() + 1;
        var context = new DrawContext(client, client.getBufferBuilders().getEntityVertexConsumers());
        root.drawTooltip(OwoUIDrawContext.of(context), mouseX, mouseY, 0, 0);
        context.draw();

        // 点击仍经过工作台 Screen，验证移除文字后槽位不会留下失效的输入目标。
        click(mouseX, mouseY);
    }

    private Component operationRoot() {
        // 验收读取当前生产组件树，输入仍通过实际 Screen 路由。
        return module.openingOperationRoot();
    }

    private static java.util.List<dev.kizuna.inventoryui.window.UiWindowManager.WindowState> windows() {
        // 当前工作台窗口是公开只读快照。
        return InventoryUiClient.runtime().workspace().windows().snapshot();
    }

    private static void requireWindow(String type) {
        // 选择必须真正打开操作窗口，而非只更新内部选择字段。
        if (windows().stream().noneMatch(window -> window.key().windowType().equals(type))) {
            throw new IllegalStateException("未打开预期窗口：" + type);
        }
    }

    private void requireNoDetails() {
        // 输入通过真实 Screen 路由后，组件引用和工作台窗口都应保持无浮窗。
        if (module.openingOperationRoot() != null || windows().stream().anyMatch(window ->
                window.key().windowType().equals("meridian_ui:meridian_operation_window")
                        || window.key().windowType().equals("meridian_ui:meridian_information_window"))) {
            throw new IllegalStateException("经脉点击或快捷键自动打开了浮窗");
        }
    }

    private static void focus(String type) {
        // 浮窗可遮住场景，需要显式切回所测窗口的前台。
        windows().stream().filter(window -> window.key().windowType().equals(type)).findFirst()
                .ifPresent(window -> InventoryUiClient.runtime().workspace().windows().focus(window.key()));
    }

    private void focusMain() {
        // 主场景继续接收旋转与点击输入。
        InventoryUiClient.runtime().workspace().windows().focus(mainKey);
    }

    private static void click(int x, int y) {
        // 按下与释放使用相同坐标，不被识别为模型拖动。
        var screen = MinecraftClient.getInstance().currentScreen;
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    private static void key(int code) {
        // 快捷键必须经过工作台输入路由，不能直接调用聚焦或任务回调。
        var screen = MinecraftClient.getInstance().currentScreen;
        screen.keyPressed(code, 0, 0);
        screen.keyReleased(code, 0, 0);
    }

    private static void capture(String filename) {
        // 记录真实渲染结果，布局和遮挡可在测试后复核。
        var client = MinecraftClient.getInstance();
        ScreenshotRecorder.saveScreenshot(client.runDirectory, filename, client.getFramebuffer(), text -> { /* 演示不弹通知。 */ });
    }
}
