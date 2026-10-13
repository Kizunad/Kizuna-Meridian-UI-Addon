package dev.kizuna.meridian.ui.demo;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.client.HumanPresentation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.ui.client.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Vector3f;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.wispforest.owo.ui.core.Component;

/** 独立演示和整体交互验收；人体依赖及初始化不会进入生产 UI JAR。 */
public final class BodyPreviewDemo implements ClientModInitializer {
    private boolean opened;
    private int ticks;
    private int totalTicks;
    private BodyUiModule module;
    private BodySnapshot snapshot;
    private byte[] original;
    private final boolean smoke = Boolean.getBoolean("meridian.ui.smoke");

    @Override
    public void onInitializeClient() {
        // 演示只在资源就绪的标题界面启动，不连接服务器或修改玩家存档。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("身体模型演示验收失败", failure);
            }
        });
    }

    private void tick(MinecraftClient client) throws Exception {
        // 超时不会写成功标记，保留崩溃现场以定位资源或窗口失败。
        totalTicks++;
        if (smoke && totalTicks > 1000) {
            throw new IllegalStateException("模型验收超时");
        }
        if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
            client.setScreen(new TitleScreen());
        }
        if (!opened && client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
            client.options.getGuiScale().setValue(2);
            client.onResolutionChanged();
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
            BodyTemplate template = HumanContent.bundle().getTemplates(0);
            TimeCursor time = TimeCursor.newBuilder().setDomain("demo:preview").setEpoch("preview").setElapsedMillis(0).build();
            original = HumanBirth.candidate(template, "preview-body", time);
            snapshot = BodySnapshot.parseFrom(original);
            // Human 的资源属于它自己的 JAR；演示为纯 Java 内容包挂载客户端资源目录。
            module.present(snapshot, new Identifier("meridian_human", "meridian/adult.pb"));
            var runtime = InventoryUiClient.runtime();
            runtime.openWorkspace();
            runtime.open("meridian_ui:body", null);
            opened = true;
            if (smoke) {
                verifyVanillaShape();
            } else {
                // 手动验收可直接打开本轮正在制作的层，生产窗口仍从内容包的首层开始。
                String preview = System.getProperty("meridian.ui.previewLayer", "assembly");
                if (preview.equals("assembly")) {
                    module.panel().layer("skeleton");
                    module.panel().setLayerVisible("muscles", true);
                    module.panel().setLayerVisible("organs", true);
                    module.panel().setLayerVisible("meridians", true);
                } else {
                    module.panel().layer(preview);
                }
            }
            System.out.println("MERIDIAN_PREVIEW_READY: textured Blockbench assembly, independent eyes and layer checkboxes; interactive=" + !smoke);
        }
        if (!opened || !smoke) {
            return;
        }
        ticks++;
        if (ticks == 30) {
            capture(client, "body-silhouette.png");
            module.panel().canvas().camera().drag(30, -14);
        }
        if (ticks == 35) {
            capture(client, "body-silhouette-angled.png");
        }
        if (ticks == 40) {
            module.panel().layer("skeleton");
        }
        if (ticks == 55) {
            capture(client, "body-skeleton.png");
            verifyClick(client, "head");
        }
        if (ticks == 75) {
            capture(client, "body-skull-focused.png");
            module.panel().overview();
            module.panel().canvas().camera().settle();
            module.panel().canvas().camera().drag(30, -14);
        }
        if (ticks == 85) {
            capture(client, "body-skeleton-angled.png");
        }
        if (ticks == 90) {
            module.panel().layer("muscles");
        }
        if (ticks == 105) {
            capture(client, "body-muscles.png");
            verifyClick(client, "left_biceps_brachii");
        }
        if (ticks == 125) {
            capture(client, "body-biceps-focused.png");
            module.panel().overview();
            module.panel().canvas().camera().settle();
            module.panel().canvas().camera().drag(240, 0);
        }
        if (ticks == 135) {
            capture(client, "body-muscles-back.png");
            verifyClick(client, "left_gastrocnemius");
        }
        if (ticks == 155) {
            capture(client, "body-calf-focused.png");
            verifyRearMuscleFocus();
        }
        if (ticks == 165) {
            capture(client, "body-triceps-focused.png");
        }
        if (ticks == 170) {
            module.panel().layer("organs");
        }
        if (ticks == 185) {
            capture(client, "body-organs.png");
            verifyClick(client, "heart");
        }
        if (ticks == 205) {
            capture(client, "body-focused.png");
            if (module.panel().canvas().camera().moving()) {
                throw new IllegalStateException("聚焦未完成");
            }
            verifyRefreshAndOverview(client);
        }
        if (ticks == 210) {
            verifyOrganSelection(client);
            focus(BodyTargetKind.ORGAN, "brain");
        }
        if (ticks == 230) {
            capture(client, "body-brain-focused.png");
            focus(BodyTargetKind.ORGAN, "left_lung");
        }
        if (ticks == 250) {
            capture(client, "body-lung-focused.png");
            focus(BodyTargetKind.ORGAN, "small_intestine");
        }
        if (ticks == 270) {
            capture(client, "body-intestines-focused.png");
            focus(BodyTargetKind.ORGAN, "left_kidney");
        }
        if (ticks == 290) {
            capture(client, "body-kidney-focused.png");
            module.panel().overview();
            module.panel().canvas().camera().settle();
            module.panel().canvas().camera().drag(240, 0);
        }
        if (ticks == 300) {
            capture(client, "body-organs-back.png");
            module.panel().layer("meridians");
        }
        if (ticks == 320) {
            capture(client, "body-meridians.png");
            verifyMeridianSelection(client);
            focus(BodyTargetKind.MERIDIAN, "left_lung_meridian");
        }
        if (ticks == 340) {
            capture(client, "body-lung-meridian-focused.png");
            focus(BodyTargetKind.MERIDIAN, "dai");
        }
        if (ticks == 360) {
            module.panel().canvas().camera().drag(35, -40);
            capture(client, "body-dai-meridian-focused.png");
            focus(BodyTargetKind.MERIDIAN, "du");
        }
        if (ticks == 380) {
            capture(client, "body-du-meridian-focused.png");
            module.panel().overview();
            module.panel().canvas().camera().settle();
            module.panel().canvas().camera().drag(240, 0);
        }
        if (ticks == 390) {
            capture(client, "body-meridians-back.png");
            verifyLayerCheckboxes(client);
            module.panel().overview();
        }
        if (ticks == 410) {
            capture(client, "body-assembly.png");
            module.panel().setLayerVisible("muscles", false);
            module.panel().setLayerVisible("meridians", false);
            focus(BodyTargetKind.ORGAN, "left_eye");
            verifyClick(client, "left_eye");
        }
        if (ticks == 430) {
            capture(client, "body-eye-assembled.png");
            if (!Arrays.equals(original, snapshot.toByteArray())) {
                throw new IllegalStateException("UI 改写了身体快照");
            }
            InventoryUiClient.runtime().workspace().disconnect();
            if (module.panel() != null) {
                throw new IllegalStateException("窗口关闭未清理");
            }
            Files.writeString(client.runDirectory.toPath().resolve("body-smoke-ok.txt"),
                    "Blockbench textures; vanilla player shape; multi-layer checkboxes; assembled eye click; all 15 organs and 36 meridians selectable; stable refresh; hidden selection cleared; empty layers; unchanged body; window cleanup\n");
            client.scheduleStop();
        }
    }

    private void verifyLayerCheckboxes(MinecraftClient client) {
        // 通过真正的左侧勾选控件叠加各层，不能依赖已移除的对象列表绕过交互。
        var panel = module.panel();
        panel.layer("skeleton");
        for (String layer : List.of("muscles", "organs", "meridians")) {
            Component checkbox = panel.root().childById(Component.class, "layer-toggle-" + layer);
            client.currentScreen.mouseClicked(checkbox.x() + 12, checkbox.y() + 13, 0);
            client.currentScreen.mouseReleased(checkbox.x() + 12, checkbox.y() + 13, 0);
        }
        if (!panel.visibleLayers().equals(Set.of("skeleton", "muscles", "organs", "meridians"))) {
            throw new IllegalStateException("勾选没有保留多层组合");
        }
        panel.select(HumanPresentation.create().getVisualsList().stream()
                .filter(node -> node.getTargetId().equals("left_eye")).findFirst().orElseThrow());
        panel.setLayerVisible("organs", false);
        if (!panel.selectedId().isEmpty()) {
            throw new IllegalStateException("隐藏器官后仍保留眼球选择");
        }
        for (String layer : List.copyOf(panel.visibleLayers())) {
            panel.setLayerVisible(layer, false);
        }
        if (panel.canvas().pick(10, 10) != null) {
            throw new IllegalStateException("全层关闭后仍能选中模型");
        }
        for (String layer : List.of("skeleton", "muscles", "organs", "meridians")) {
            panel.setLayerVisible(layer, true);
        }
    }

    private void verifyClick(MinecraftClient client, String targetId) {
        // 优先从聚焦中心寻找可见面，避免为细长经脉逐次扫描大量空白区域。
        BodyCanvas canvas = module.panel().canvas();
        List<int[]> probes = new ArrayList<>();
        for (int y = 10; y < canvas.height() - 10; y += 3) {
            for (int x = 10; x < canvas.width() - 10; x += 3) {
                probes.add(new int[]{x, y});
            }
        }
        probes.sort(Comparator.comparingDouble(point -> Math.pow(point[0] - canvas.width() / 2.0, 2)
                + Math.pow(point[1] - canvas.height() / 2.0, 2)));

        // 命中后仍经过真实 Screen 按下和释放链，验证选择事件接线。
        for (int[] point : probes) {
            var hit = canvas.pick(point[0], point[1]);
            if (hit == null || !hit.getTargetId().equals(targetId)) {
                continue;
            }
            client.currentScreen.mouseClicked(canvas.x() + point[0], canvas.y() + point[1], 0);
            client.currentScreen.mouseReleased(canvas.x() + point[0], canvas.y() + point[1], 0);
            if (!module.panel().selectedId().equals(targetId)) {
                throw new IllegalStateException("点击没有选择相应身体对象：" + targetId);
            }
            return;
        }
        throw new IllegalStateException("当前层中没有可点击的对象：" + targetId);
    }

    private void verifyRearMuscleFocus() {
        // 聚焦背侧肌肉后，相机应自动转向背面，点击与信息必须继续绑定同一组织。
        BodyVisual triceps = HumanPresentation.create().getVisualsList().stream()
                .filter(node -> node.getTargetId().equals("left_triceps_brachii"))
                .findFirst().orElseThrow();
        module.panel().overview();
        module.panel().canvas().camera().settle();
        module.panel().select(triceps);
        module.panel().canvas().camera().settle();
        float yaw = module.panel().canvas().camera().yaw();
        if (Math.abs(Math.abs(yaw) - 180) > 0.01) {
            throw new IllegalStateException("背侧肌肉聚焦未转向背面");
        }
        verifyClick(MinecraftClient.getInstance(), "left_triceps_brachii");
        List<String> information = BodyInformation.lines(snapshot, triceps);
        if (information.stream().noneMatch(line -> line.startsWith("基础力量："))
                || information.stream().noneMatch(line -> line.startsWith("疲劳："))) {
            throw new IllegalStateException("肌肉模型没有显示组织能力与疲劳");
        }
    }

    private void verifyOrganSelection(MinecraftClient client) {
        // 逐个聚焦后再用真实可见面点击，覆盖小器官及背侧器官的遮挡问题。
        List<BodyVisual> organs = HumanPresentation.create().getVisualsList().stream()
                .filter(node -> node.getTargetKind() == BodyTargetKind.ORGAN).toList();
        for (BodyVisual organ : organs) {
            module.panel().select(organ);
            module.panel().canvas().camera().settle();
            verifyClick(client, organ.getTargetId());
            List<String> information = BodyInformation.lines(snapshot, organ);
            if (!information.contains("器官：" + organ.getDisplayName())
                    || information.stream().noneMatch(line -> line.startsWith("缓存："))
                    || information.stream().noneMatch(line -> line.startsWith("加工："))) {
                throw new IllegalStateException("器官选择没有显示对应状态：" + organ.getTargetId());
            }
        }
    }

    private void verifyMeridianSelection(MinecraftClient client) {
        // 每条主干与分支共用一个身份；密集交织后仍必须能找到可点击的可见表面。
        List<BodyVisual> meridians = HumanPresentation.create().getVisualsList().stream()
                .filter(node -> node.getTargetKind() == BodyTargetKind.MERIDIAN).toList();
        for (BodyVisual meridian : meridians) {
            module.panel().select(meridian);
            module.panel().canvas().camera().settle();
            verifyClick(client, meridian.getTargetId());
            List<String> information = BodyInformation.lines(snapshot, meridian);
            if (!information.contains("经脉：" + meridian.getDisplayName())
                    || information.stream().noneMatch(line -> line.startsWith("内腔："))
                    || information.stream().noneMatch(line -> line.startsWith("开拓："))) {
                throw new IllegalStateException("经脉模型没有显示对应状态：" + meridian.getTargetId());
            }
        }
    }

    private void focus(BodyTargetKind kind, String id) {
        // 截图和模型点击共用选择入口，等待相机动画后再记录画面。
        BodyVisual node = HumanPresentation.create().getVisualsList().stream()
                .filter(candidate -> candidate.getTargetKind() == kind && candidate.getTargetId().equals(id))
                .findFirst().orElseThrow();
        module.panel().select(node);
    }

    private static void verifyVanillaShape() {
        // 用游戏自身烘焙的玩家模型核对外形尺寸，避免只和另一份手写数字互相验证。
        var vanilla = TexturedModelData.of(PlayerEntityModel.getTexturedModelData(Dilation.NONE, false), 64, 64).createModel();
        Map<String, List<String>> groups = Map.of(
                "head", List.of("head"), "body", List.of("neck", "chest", "abdomen", "pelvis"),
                "left_arm", List.of("left_upper_arm", "left_forearm", "left_hand"),
                "right_arm", List.of("right_upper_arm", "right_forearm", "right_hand"),
                "left_leg", List.of("left_thigh", "left_lower_leg", "left_foot"),
                "right_leg", List.of("right_thigh", "right_lower_leg", "right_foot"));
        BodyPresentation presentation = HumanPresentation.create();
        for (var group : groups.entrySet()) {
            Vector3f expectedMin = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f expectedMax = new Vector3f(Float.NEGATIVE_INFINITY);
            vanilla.getChild(group.getKey()).forEachCuboid(new MatrixStack(), (entry, path, index, cuboid) -> {
                // 原版坐标向下、正面朝 -Z；转为展示契约的脚底原点、Y 向上、Z 向前。
                for (float x : new float[]{cuboid.minX, cuboid.maxX}) {
                    for (float y : new float[]{cuboid.minY, cuboid.maxY}) {
                        for (float z : new float[]{cuboid.minZ, cuboid.maxZ}) {
                            Vector3f vertex = entry.getPositionMatrix().transformPosition(new Vector3f(x / 16, y / 16, z / 16));
                            vertex.set(vertex.x, 1.5f - vertex.y, -vertex.z);
                            expectedMin.min(vertex);
                            expectedMax.max(vertex);
                        }
                    }
                }
            });
            Vector3f actualMin = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f actualMax = new Vector3f(Float.NEGATIVE_INFINITY);
            for (BodyVisual node : presentation.getVisualsList()) {
                if (!node.getLayerId().equals("surface") || !group.getValue().contains(node.getTargetId())) {
                    continue;
                }
                for (ModelPoint point : node.getVerticesList()) {
                    Vector3f vertex = new Vector3f(point.getX(), point.getY(), point.getZ());
                    actualMin.min(vertex);
                    actualMax.max(vertex);
                }
            }
            if (expectedMin.distance(actualMin) > 0.00001 || expectedMax.distance(actualMax) > 0.00001) {
                throw new IllegalStateException("剪影偏离原版玩家模型：" + group.getKey());
            }
        }
    }

    private void verifyRefreshAndOverview(MinecraftClient client) {
        // 模拟玩家转动局部视角后收到新快照，刷新不能覆盖镜头或选中对象。
        BodyCanvas canvas = module.panel().canvas();
        canvas.camera().drag(20, 10);
        canvas.camera().settle();
        float yaw = canvas.camera().yaw();
        float pitch = canvas.camera().pitch();
        module.update(snapshot.toBuilder().setRevision(snapshot.getRevision() + 1).build());
        canvas.camera().settle();
        if (canvas.camera().yaw() != yaw || canvas.camera().pitch() != pitch
                || !module.panel().selectedId().equals("heart")) {
            throw new IllegalStateException("快照刷新覆盖了镜头或选中对象");
        }

        // 通过实际 Screen 中键链返回全身，检查面板选择也一起清空。
        client.currentScreen.mouseClicked(canvas.x() + canvas.width() / 2.0, canvas.y() + canvas.height() / 2.0, 2);
        client.currentScreen.mouseReleased(canvas.x() + canvas.width() / 2.0, canvas.y() + canvas.height() / 2.0, 2);
        if (!module.panel().selectedId().isEmpty()) {
            throw new IllegalStateException("回到全身后仍保留了旧对象选择");
        }
    }

    private static void capture(MinecraftClient client, String name) {
        // 保存每一层的真实游戏截图，便于核验遮挡、坐标和信息可读性。
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), message -> {});
    }
}
