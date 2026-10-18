package dev.kizuna.meridian.ui.demo;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.client.HumanPresentation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathParticle;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.ui.client.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.texture.NativeImage;
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
import io.wispforest.owo.ui.component.LabelComponent;
import org.lwjgl.glfw.GLFW;

/** 独立演示和整体交互验收；人体依赖及初始化不会进入生产 UI JAR。 */
public final class BodyPreviewDemo implements ClientModInitializer {
    private boolean opened;
    private int ticks;
    private int totalTicks;
    private BodyUiModule module;
    private BodySnapshot snapshot;
    private byte[] original;
    private final boolean smoke = Boolean.getBoolean("meridian.ui.smoke");
    private final boolean gatheringSmoke = Boolean.getBoolean("meridian.ui.gatheringSmoke");
    private final boolean responsiveSmoke = Boolean.getBoolean("meridian.ui.responsiveSmoke");
    private static final int[][] RESPONSIVE_SIZES = {
            {1440, 1000, 2}, {1440, 1000, 4}, {960, 720, 3}, {1920, 1080, 0}, {1920, 1080, 2}
    };
    private Component originalBodyRoot;
    private Component originalBreathingRoot;
    private int informationTopBeforeScroll;
    private GatheringPreviewHost gatheringHost;
    private int gatheringTicks;
    private double acquiredBeforeRenewal;
    private SoundInstance lastBreathingSound;
    private final Set<String> heardBreathingSounds = new java.util.HashSet<>();

    @Override
    public void onInitializeClient() {
        // 演示只在资源就绪的标题界面启动，不连接服务器或修改玩家存档。
        if (Boolean.getBoolean("meridian.ui.openingSmoke")) {
            new OpeningPreviewCheck().register();
            return;
        }
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
        if ((smoke || gatheringSmoke || responsiveSmoke) && totalTicks > 1000) {
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
            if (smoke) {
                // 全模型回归需要通灵才能检视全部经脉；交互演示仍使用实际醒灵初值。
                snapshot = snapshot.toBuilder().setRealm(Realm.SPIRITUAL_ATTUNEMENT).build();
                original = snapshot.toByteArray();
            }
            // Human 的资源属于它自己的 JAR；演示为纯 Java 内容包挂载客户端资源目录。
            module.present(snapshot, new Identifier("meridian_human", "meridian/adult.pb"));
            var runtime = InventoryUiClient.runtime();
            runtime.openWorkspace();
            runtime.open("meridian_ui:body", null);
            opened = true;
            if (gatheringSmoke) {
                // 实际声音管理器的回调用于检查资源播放与窗口清理，不绕过声音系统。
                client.getSoundManager().registerListener((sound, sounds) -> {
                    if (sound.getId().getNamespace().equals("meridian_ui")) {
                        lastBreathingSound = sound;
                        heardBreathingSounds.add(sound.getId().getPath());
                    }
                });
            }
            if (!smoke) {
                gatheringHost = new GatheringPreviewHost(snapshot, module);
            }
            if (smoke) {
                verifyVanillaShape();
            } else {
                // 普通演示与生产使用相同默认层；专项验收仍可显式切换内部装配。
                String preview = System.getProperty("meridian.ui.previewLayer", "surface");
                if (preview.equals("assembly")) {
                    module.panel().layer("skeleton");
                    module.panel().setLayerVisible("muscles", true);
                    module.panel().setLayerVisible("organs", true);
                    module.panel().setLayerVisible("meridians", true);
                } else {
                    module.panel().layer(preview);
                }
                if (!responsiveSmoke) {
                    runtime.open("meridian_ui:cultivation", null);
                }
            }
            System.out.println("MERIDIAN_PREVIEW_READY: manual breathing, meditation animation and read-only body preview; interactive=" + !smoke);
        }
        if (gatheringHost != null) {
            gatheringHost.tick(++gatheringTicks * 50L);
            if (gatheringSmoke) {
                verifyGathering(client);
                return;
            }
        }
        if (opened && responsiveSmoke) {
            verifyResponsive(client);
            return;
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
            verifyVisibleGeometry(client);
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

    private void verifyResponsive(MinecraftClient client) throws Exception {
        // 同一身体组件经历实际窗口尺寸与 GUI 缩放变化，验证布局、选择和 Screen 输入一起迁移。
        int scenario = ticks / 90;
        int step = ++ticks % 90;
        if (scenario == RESPONSIVE_SIZES.length) {
            Files.writeString(client.runDirectory.toPath().resolve("responsive-smoke-ok.txt"),
                    "五组实际分辨率/GUI 缩放；身体布局不相交；保留组件与选择；模型真实点击；吐纳叠加控件及按钮输入。\n");
            client.scheduleStop();
            return;
        }
        if (step == 1) {
            int[] size = RESPONSIVE_SIZES[scenario];
            GLFW.glfwSetWindowSize(client.getWindow().getHandle(), size[0], size[1]);
            client.options.getGuiScale().setValue(size[2]);
            client.onResolutionChanged();
        }
        if (step == 15) {
            BodyPanel panel = module.panel();
            if (scenario == 0) {
                originalBodyRoot = panel.root();
                panel.layer("organs");
            } else if (panel.root() != originalBodyRoot || !panel.selectedId().equals("heart")
                    || !panel.visibleLayers().equals(Set.of("organs"))) {
                throw new IllegalStateException("改变分辨率丢失了身体组件、选择或图层状态");
            }
            var root = panel.root();
            Component layers = root.childById(Component.class, "layers-scroll");
            Component details = root.childById(Component.class, "information-scroll");
            for (Component area : List.of(layers, details, panel.canvas(),
                    root.childById(Component.class, "body-title"), root.childById(Component.class, "body-hint"))) {
                requireInside(root, area);
            }
            requireSeparate(layers, panel.canvas());
            requireSeparate(layers, details);
            requireSeparate(panel.canvas(), details);
            panel.overview();
            panel.canvas().camera().settle();
            verifyClick(client, "heart");
            root.childById(io.wispforest.owo.ui.container.ScrollContainer.class, "information-scroll").scrollTo(0);
            System.out.println("MERIDIAN_RESPONSIVE_BODY: " + scenario + " "
                    + client.getWindow().getScaledWidth() + "x" + client.getWindow().getScaledHeight()
                    + " content=" + root.width() + "x" + root.height());
        }
        if (step == 20) {
            // 新窗口尺寸可能使详情变长；通过真实滚轮确认被裁掉的文字仍能读到。
            var root = module.panel().root();
            Component details = root.childById(Component.class, "information-scroll");
            Component information = root.childById(Component.class, "information");
            informationTopBeforeScroll = information.y();
            client.currentScreen.mouseScrolled(details.x() + details.width() / 2.0,
                    details.y() + details.height() / 2.0, -30);
        }
        if (step == 27) {
            var root = module.panel().root();
            Component details = root.childById(Component.class, "information-scroll");
            Component information = root.childById(Component.class, "information");
            if (information.height() > details.height() && information.y() >= informationTopBeforeScroll) {
                throw new IllegalStateException("窄窗口详情无法通过实际滚轮查看");
            }
        }
        if (step == 30) {
            capture(client, "responsive-body-" + scenario + ".png");
            InventoryUiClient.runtime().open("meridian_ui:cultivation", null);
        }
        if (step == 45) {
            var panel = module.cultivationPanel();
            var root = panel.root();
            if (scenario == 0) {
                originalBreathingRoot = root;
            } else if (root != originalBreathingRoot || !gatheringHost.view().getBreathing().getActive()) {
                throw new IllegalStateException("改变分辨率重建或中断了吐纳窗口");
            }
            var controls = List.of(root.childById(Component.class, "breathing-air"),
                    root.childById(Component.class, "breathing-reserve"),
                    root.childById(Component.class, "breathing-start"),
                    root.childById(Component.class, "breathing-qi"));
            for (int index = 0; index < controls.size(); index++) {
                Component control = controls.get(index);
                requireInside(panel.canvas(), control);
                if (root.childAt(control.x() + control.width() / 2, control.y() + control.height() / 2) != control) {
                    throw new IllegalStateException("吐纳控件的命中与绘制不一致：" + control.id());
                }
                for (int other = index + 1; other < controls.size(); other++) {
                    requireSeparate(control, controls.get(other));
                }
            }
            capture(client, "responsive-breathing-" + scenario + ".png");
            if (scenario == 0) {
                clickCultivation(client, "breathing-start");
            }
        }
        if (step == 55) {
            capture(client, "responsive-tutorial-" + scenario + ".png");
            if (!gatheringHost.view().getBreathing().getActive()) {
                clickCultivation(client, "breathing-start");
            }
        }
        if (step == 65) {
            if (!gatheringHost.view().getBreathing().getActive()) {
                throw new IllegalStateException("调整大小后不能通过实际按钮开始吐纳");
            }
            var windows = InventoryUiClient.runtime().workspace().windows();
            if (!windows.minimize(windows.key("meridian_ui:cultivation_window", "singleton"))) {
                throw new IllegalStateException("无法让身体窗口重新接收鼠标输入");
            }
        }
    }

    private static void requireInside(Component parent, Component child) {
        // 以挂载后的真实组件边界验收，覆盖 XML 百分比、窗口装饰与 GUI 缩放的组合结果。
        if (child.width() <= 0 || child.height() <= 0 || child.x() < parent.x() || child.y() < parent.y()
                || child.x() + child.width() > parent.x() + parent.width()
                || child.y() + child.height() > parent.y() + parent.height()) {
            throw new IllegalStateException("组件超出内容区：" + child.id());
        }
    }

    private static void requireSeparate(Component first, Component second) {
        // 各内容区可以共边，但不能相互覆盖文字或阻挡鼠标操作。
        if (first.x() < second.x() + second.width() && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height() && first.y() + first.height() > second.y()) {
            throw new IllegalStateException("组件相互覆盖：" + first.id() + " / " + second.id());
        }
    }

    private void verifyGathering(MinecraftClient client) throws Exception {
        // 同一个真实窗口反复吸气、呼气，验证首见提示、肺容量、成本、守恒与关闭清理。
        if (gatheringTicks == 10) {
            if (module.panel().root().childById(Component.class, "breathing-start") != null) {
                throw new IllegalStateException("身体窗口仍存在修炼操作");
            }
            verifyBreathingControls();
            capture(client, "breathing-ready.png");
            clickCultivation(client, "breathing-start");
        }
        if (gatheringTicks == 20) {
            capture(client, "breathing-tutorial.png");
            clickCultivation(client, "breathing-start");
            if (!gatheringHost.view().getBreathing().getActive()) {
                throw new IllegalStateException("没有进入主动吐纳");
            }
        }
        if (gatheringTicks == 22) {
            // 经过 Screen 的中键拖动验证旋转；相机变化不修改权威粒子坐标或气息预算。
            var canvas = module.cultivationPanel().canvas();
            var particle = gatheringHost.view().getBreathing().getParticles(0);
            var before = canvas.projectParticle(particle);
            double mouseX = canvas.x() + canvas.width() * .5;
            double mouseY = canvas.y() + canvas.height() * .5;
            client.currentScreen.mouseClicked(mouseX, mouseY, 2);
            client.currentScreen.mouseDragged(mouseX + 85, mouseY + 18, 2, 85, 18);
            client.currentScreen.mouseReleased(mouseX + 85, mouseY + 18, 2);
            if (before.distance(canvas.projectParticle(particle)) < .01) {
                throw new IllegalStateException("中键拖动没有改变三维粒子投影");
            }
        }
        if (gatheringTicks == 25) {
            capture(client, "breathing-orbit.png");
        }
        if (gatheringTicks >= 30 && gatheringTicks <= 125 && gatheringTicks % 5 == 0) {
            steerBreath(client, 0);
        }
        if (gatheringTicks == 75) {
            if (lastBreathingSound == null || !client.getSoundManager().isPlaying(lastBreathingSound)) {
                throw new IllegalStateException("有效吸气没有播放呼吸音效");
            }
            capture(client, "breathing-inhale.png");
        }
        if (gatheringTicks == 130) {
            var breath = gatheringHost.view().getBreathing();
            if (Math.abs(breath.getAirLiters() - breath.getCapacityLiters()) > .001) {
                throw new IllegalStateException("持续吸气未被肺容量边界截断：" + breath);
            }
            capture(client, "breathing-full.png");
            client.currentScreen.mouseReleased(0, 0, 0);
        }
        if (gatheringTicks >= 140 && gatheringTicks <= 235 && gatheringTicks % 5 == 0) {
            steerBreath(client, 1);
        }
        if (gatheringTicks == 180) {
            capture(client, "breathing-exhale.png");
        }
        if (gatheringTicks == 240) {
            if (gatheringHost.view().getBreathing().getAirLiters() > .001) {
                throw new IllegalStateException("持续呼气没有排空并停止");
            }
            client.currentScreen.mouseReleased(0, 0, 1);
        }
        if (gatheringTicks == 245 && client.getSoundManager().isPlaying(lastBreathingSound)) {
            throw new IllegalStateException("排空或松键后呼吸音效没有停止");
        }
        if (gatheringTicks >= 250 && gatheringTicks <= 300 && gatheringTicks % 5 == 0) {
            steerBreath(client, 0);
        }
        if (gatheringTicks == 305) {
            clickCultivation(client, "breathing-start");
            closeWindow("meridian_ui:cultivation_window");
        }
        if (gatheringTicks == 330) {
            if (client.getSoundManager().isPlaying(lastBreathingSound)
                    || !heardBreathingSounds.containsAll(Set.of("breathing.inhale", "breathing.exhale"))) {
                throw new IllegalStateException("吸呼音效未完整播放或关窗后仍在播放");
            }
            var breath = gatheringHost.view().getBreathing();
            if (breath.getActive() || breath.getAbsorbedQi() <= 0
                    || breath.getStaminaRatio() >= 1 || breath.getAttentionRatio() >= 1
                    || Math.abs(gatheringHost.conservedQi() - 100.36) > 1e-9) {
                throw new IllegalStateException("手动吐纳未扣费、关窗未停止或真元不守恒：" + breath);
            }
            if (gatheringHost.view().getBody().getMeridianStatesList().stream()
                    .filter(value -> value.getId().endsWith("lung_meridian"))
                    .mapToDouble(value -> value.getLumenQi().getEntriesList().stream()
                            .mapToDouble(Qi::getAmount).sum()).sum() <= .02) {
                throw new IllegalStateException("捕获的灵气没有炼化并入脉");
            }
            closeWindow("meridian_ui:body_window");
            InventoryUiClient.runtime().open("meridian_ui:cultivation", null);
        }
        if (gatheringTicks == 345) {
            capture(client, "breathing-independent.png");
            if (module.panel() != null || module.cultivationPanel() == null) {
                throw new IllegalStateException("修炼不能独立打开");
            }
            acquiredBeforeRenewal = gatheringHost.view().getBreathing().getAbsorbedQi();
        }
        if (gatheringTicks == 360) {
            // 同进程已确认过教学，重开只点一次启动；继续真实鼠标操作跨过环境窗口。
            clickCultivation(client, "breathing-start");
        }
        if (gatheringTicks == 370 && !gatheringHost.view().getBreathing().getActive()) {
            throw new IllegalStateException("重开窗口后无法再次开始吐纳");
        }
        if (gatheringTicks >= 380 && gatheringTicks <= 690 && gatheringTicks % 5 == 0) {
            var breath = gatheringHost.view().getBreathing();
            int button = breath.getAction() == dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.EXHALE ? 1 : 0;
            if (breath.getAirLiters() <= .001) {
                button = 0;
            } else if (breath.getAirLiters() >= breath.getCapacityLiters() - .001) {
                button = 1;
            }
            steerBreath(client, button);
        }
        if (gatheringTicks == 700) {
            var breath = gatheringHost.view().getBreathing();
            if (!breath.getActive() || breath.getFailureValue() != 0 || breath.getAbsorbedQi() <= acquiredBeforeRenewal
                    || Math.abs(gatheringHost.conservedQi() - 100.36) > 1e-9) {
                throw new IllegalStateException("跨环境续窗的重复吐纳失败：" + breath);
            }
            clickCultivation(client, "breathing-start");
            capture(client, "breathing-renewed.png");
        }
        if (gatheringTicks == 715) {
            InventoryUiClient.runtime().workspace().disconnect();
            Files.writeString(client.runDirectory.toPath().resolve("gathering-smoke-ok.txt"),
                    "场景内叠加布局与资源悬浮；吸呼音效播放与停止；首见教学；真实鼠标反复吸呼；空气上下限；两项权威消耗；环境身体守恒；关窗停止；独立窗口；跨环境续窗。\n");
            client.scheduleStop();
        }
    }

    private void verifyBreathingControls() {
        // 通过实际布局检查四个控件都叠在三维画面内部，并保留场景中央的输入区域。
        var panel = module.cultivationPanel();
        var root = panel.root();
        var canvas = panel.canvas();
        Component air = root.childById(Component.class, "breathing-air");
        Component reserve = root.childById(Component.class, "breathing-reserve");
        Component action = root.childById(Component.class, "breathing-start");
        Component qi = root.childById(Component.class, "breathing-qi");
        for (Component control : List.of(air, reserve, action, qi)) {
            if (control.width() <= 0 || control.height() <= 0
                    || control.x() < canvas.x() || control.y() < canvas.y()
                    || control.x() + control.width() > canvas.x() + canvas.width()
                    || control.y() + control.height() > canvas.y() + canvas.height()
                    || control.zIndex() <= canvas.zIndex()) {
                throw new IllegalStateException("吐纳控件未叠在三维场景内部：" + control.id());
            }
        }

        // 底边提示占最后 18 像素，按钮需在其上方；肺和纳入量分别靠左右下角。
        int centerX = canvas.x() + canvas.width() / 2;
        int bottomY = canvas.y() + canvas.height();
        if (canvas.x() != root.x() || canvas.y() != root.y()
                || canvas.width() != root.width() || canvas.height() != root.height()
                || reserve.y() != canvas.y() + 12
                || air.x() != canvas.x() + 12 || air.y() + air.height() != bottomY - 12
                || qi.x() + qi.width() != canvas.x() + canvas.width() - 12
                || qi.y() + qi.height() != bottomY - 12
                || Math.abs(action.x() + action.width() / 2 - centerX) > 1
                || action.y() + action.height() != bottomY - 34
                || reserve.tooltip().size() != 3 || qi.tooltip().size() != 2) {
            throw new IllegalStateException("吐纳控件位置或资源悬浮明细不完整");
        }
        if (root.childAt(centerX, canvas.y() + canvas.height() / 2) != canvas
                || root.childAt(action.x() + action.width() / 2, action.y() + action.height() / 2) != action) {
            throw new IllegalStateException("场景旋转或按钮输入被叠加控件遮挡");
        }
    }

    private void steerBreath(MinecraftClient client, int button) {
        // 鼠标对准三维相机中的真实粒子，避开叠加控件，仍经过真实 Screen 输入链。
        var canvas = module.cultivationPanel().canvas();
        Comparator<BreathParticle> distanceToIntake = Comparator.comparingDouble(particle ->
                Math.pow(particle.getX() - .5, 2) + Math.pow(particle.getY() - .4, 2)
                        + Math.pow(particle.getZ() - .5, 2));
        // 吸气引导近处灵气，呼气朝远处吹，避免每次排气都把刚拉近的目标推走。
        if (button == 1) {
            distanceToIntake = distanceToIntake.reversed();
        }
        var particle = gatheringHost.view().getBreathing().getParticlesList().stream()
                .filter(value -> {
                    var point = canvas.projectParticle(value);
                    if (point.x <= .08 || point.x >= .92 || point.y <= .08 || point.y >= .85
                            || point.z <= -1 || point.z >= 1) {
                        return false;
                    }
                    int mouseX = (int) (canvas.x() + point.x * canvas.width());
                    int mouseY = (int) (canvas.y() + point.y * canvas.height());
                    return module.cultivationPanel().root().childAt(mouseX, mouseY) == canvas;
                })
                .min(distanceToIntake)
                .orElseThrow();
        var point = canvas.projectParticle(particle);
        double mouseX = canvas.x() + point.x * canvas.width();
        double mouseY = canvas.y() + point.y * canvas.height();
        client.currentScreen.mouseClicked(mouseX, mouseY, button);
    }

    private void clickCultivation(MinecraftClient client, String id) {
        // 经过真实 Screen 事件链验证操作位置，避免直接调用面板方法绕过布局问题。
        Component action = module.cultivationPanel().root().childById(Component.class, id);
        double mouseX = action.x() + action.width() / 2.0;
        double mouseY = action.y() + action.height() / 2.0;
        client.currentScreen.mouseClicked(mouseX, mouseY, 0);
        client.currentScreen.mouseReleased(mouseX, mouseY, 0);
    }

    private void closeWindow(String windowType) {
        // 使用公共窗口生命周期，验证组件清理而不直接清空模块引用。
        var windows = InventoryUiClient.runtime().workspace().windows();
        windows.close(windows.key(windowType, "singleton"));
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
        // 紧凑预览里的小器官可能只有数个逻辑像素，采样不能直接跨过可点击的三角面。
        int probeStep = canvas.height() < 140 ? 1 : 3;
        for (int y = 10; y < canvas.height() - 10; y += probeStep) {
            for (int x = 10; x < canvas.width() - 10; x += probeStep) {
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

    private void verifyVisibleGeometry(MinecraftClient client) {
        // CPU 命中测试无法发现 GPU 矩阵裁剪错误，需检查画布中确实产生了模型像素。
        BodyCanvas canvas = module.panel().canvas();
        double scale = client.getWindow().getScaleFactor();
        int left = (int) ((canvas.x() + 8) * scale);
        int top = (int) ((canvas.y() + 8) * scale);
        int right = (int) ((canvas.x() + canvas.width() - 8) * scale);
        int bottom = (int) ((canvas.y() + canvas.height() - 8) * scale);
        int modelPixels = 0;
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            for (int y = top; y < bottom; y += 2) {
                for (int x = left; x < right; x += 2) {
                    int color = image.getColor(x, y);
                    int red = color & 255;
                    int green = color >> 8 & 255;
                    int blue = color >> 16 & 255;
                    int difference = Math.abs(red - 19) + Math.abs(green - 31) + Math.abs(blue - 37);
                    if (difference > 30) {
                        modelPixels++;
                    }
                }
            }
        }
        if (modelPixels < (right - left) * (bottom - top) / 4000) {
            throw new IllegalStateException("模型可以被选中但没有绘制到身体画布");
        }
    }
}
