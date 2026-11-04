package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.CombatWindowProbe;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.combat.core.CombatConfigFile;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.fabric.client.CombatSlotAccess;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static com.google.common.base.Preconditions.checkState;
import static dev.kizuna.meridian.ui.client.CombatSlotTestInput.*;

/** 完整客户端验证书页、真实资源、输入隔离和唯一持久化路径，并保存帧缓冲截图。 */
public final class CombatSlotClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../combat-slot-results.txt");
    private static final int TIMEOUT_TICKS = 2400;
    private static final int READY_TICKS = 80;
    private static final int SETTLE_TICKS = 20;
    private static final int COMPACT_WIDTH = 320;
    private static final int COMPACT_HEIGHT = 240;
    private static final int COMPACT_SCALE = 3;
    private static final int TEXT_BOX_WIDTH = 100;
    private static final int HEADER_CLICK_X = 30;
    private static final int HEADER_CLICK_Y = 5;
    private static final int SCROLL_DISTANCE = 20;
    private static final int LONG_DESCRIPTION_REPEATS = 40;
    private static final String FIRST = "meridian_peddler:mule_kick";
    private static final String SECOND = "meridian_peddler:roundhouse";
    private enum Stage {
        PREPARE, OPEN, SELECT, DRAG, KEYS, FOCUS, COMMAND, COMPACT, SCROLL, INVALID, SCROLLED,
        CLOSE, LEAK, RELEASE, GAMEPLAY, DISCONNECT, REJOIN, DONE
    }
    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private int screenshots;
    private CompletableFuture<?> serverTask;
    private WindowState window;
    private CombatBookPanel panel;
    private KnownTechniques originalKnown;
    private List<String> beforeInvalid;
    private int selectedHotbar;
    private List<String> inventoryBefore;

    @Override
    public void onInitializeClient() {
        // 测试独立存档和配置目录，所有事件在游戏主线程执行。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        // 等待真实网络投影及布局帧，失败写明当前阶段并结束隔离客户端。
        if (stage == Stage.DONE) {
            return;
        }
        try {
            checkState(++ticks < TIMEOUT_TICKS, "客户端验收超时：" + stage);
            if (stage == Stage.DISCONNECT) {
                reconnect(client);
                return;
            }
            if (ticks < READY_TICKS || client.player == null || client.getSingleplayerServer() == null
                    || client.gui.overlay() != null || ticks - stageTick < SETTLE_TICKS) {
                return;
            }
            advance(client);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(client, stage + ": " + failure);
        }
    }

    private void advance(Minecraft client) throws Exception {
        // 各交互阶段等待自己的网络或布局条件，主 tick 只负责超时和失败处理。
        switch (stage) {
            case PREPARE -> prepare(client);
            case OPEN -> open(client);
            case SELECT -> select();
            case DRAG -> dragSlots();
            case KEYS -> keys();
            case FOCUS -> focus();
            case COMMAND -> command();
            case COMPACT -> compact(client);
            case SCROLL -> scroll();
            case INVALID -> invalid();
            case SCROLLED -> scrolled();
            case CLOSE -> close(client);
            case LEAK -> leak(client);
            case RELEASE -> release(client);
            case GAMEPLAY -> gameplay(client);
            case REJOIN -> rejoined(client);
            case DISCONNECT, DONE -> { }
        }
    }

    private void prepare(Minecraft client) {
        // 通过真实服务学习三卷，不在客户端虚构已学权限。
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        serverTask = server.submit(() -> {
            for (String volume : List.of("road", "ambush", "journey")) {
                TechniqueFabric.service().learn(playerId, "meridian_peddler:" + volume);
            }
        });
        next(Stage.OPEN);
    }

    private void open(Minecraft client) {
        // 从同一权威投影验证十个真实图标、三卷分类与独立简介。
        if (!serverTask.isDone() || CombatSlotAccess.snapshot().known().getTechniquesCount() < CombatIds.SLOT_COUNT) {
            return;
        }
        serverTask.join();
        originalKnown = CombatSlotAccess.snapshot().known();
        verifyResources(client);
        for (int number = 1; number <= CombatIds.SLOT_COUNT; number++) {
            CombatSlotAccess.clear(number);
        }
        var runtime = InventoryUiClient.runtime();
        runtime.motion(false /* enabled */);
        runtime.openWorkspace();
        window = runtime.open(CombatUiModule.FEATURE, null);
        panel = (CombatBookPanel) CombatWindowProbe.content(window.key());
        selectedHotbar = client.player.getInventory().getSelectedSlot();
        inventoryBefore = inventory(client);
        next(Stage.SELECT);
    }

    private void verifyResources(Minecraft client) {
        // 技能图标全部来自实际资源包；卷轴身份也必须与注册目录一致。
        var peddler = originalKnown.getTechniquesList().stream()
                .filter(entry -> entry.getId().startsWith("meridian_peddler:")).toList();
        checkState(peddler.size() == CombatIds.SLOT_COUNT, "千里腿不是十式");
        checkState(peddler.stream().map(entry -> entry.getIconId()).distinct().count() == CombatIds.SLOT_COUNT,
                "不同招式重复使用同一图标");
        for (var entry : peddler) {
            var texture = Identifier.parse(CombatHud.texture(entry.getIconId()));
            checkState(client.getResourceManager().getResource(texture).isPresent(), "缺少招式图标：" + texture);
            checkState(!entry.getDescription().isBlank() && !entry.getName().isBlank(), "名称或简介丢失");
        }
        for (String volume : List.of("road", "ambush", "journey")) {
            var texture = Identifier.parse("meridian_peddler:textures/item/" + volume + "_scroll.png");
            checkState(client.getResourceManager().getResource(texture).isPresent(), "缺少卷轴图标：" + texture);
        }
    }

    private void select() {
        // 无选择的数字输入不写槽；点击仅选中且更新右页。
        capture("combat-book-empty.png");
        var screen = Minecraft.getInstance().gui.screen();
        screen.keyPressed(new net.minecraft.client.input.KeyEvent('1', 0, 0));
        checkState(CombatSlotAccess.snapshot().slots().stream().allMatch(String::isEmpty), "无选择时写槽");
        click(technique(FIRST));
        checkState(panel.selected().equals(FIRST), "点击没有选中功法");
        checkState(CombatSlotAccess.snapshot().slots().stream().allMatch(String::isEmpty), "点击立即写槽");
        next(Stage.DRAG);
    }

    private void dragSlots() {
        // 十个实际落点都经过工作台鼠标路由，重复功法必须允许。
        capture("combat-book-selected.png");
        for (int number = 1; number <= CombatIds.SLOT_COUNT; number++) {
            drag(technique(FIRST), slot(number));
            checkState(CombatSlotAccess.snapshot().slots().get(number - 1).equals(FIRST), "拖动落槽失败：" + number);
        }
        var before = CombatSlotAccess.snapshot().slots();
        startDrag(technique(SECOND), slot(1));
        var screen = Minecraft.getInstance().gui.screen();
        screen.mouseReleased(mouse(0, 0, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        checkState(CombatSlotAccess.snapshot().slots().equals(before), "外部松开改变配置");
        startDrag(technique(SECOND), slot(1));
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.mouseDragged(point(slot(1)), 1, 1);
        screen.mouseReleased(point(slot(1)));
        checkState(Minecraft.getInstance().gui.screen() == screen, "取消拖动关闭了工作台");
        checkState(CombatSlotAccess.snapshot().slots().equals(before), "Escape 取消后仍提交拖动");
        next(Stage.KEYS);
    }

    private void keys() throws Exception {
        // 原版键盘分发验证 1、9、0、覆盖、长按去重及清空。
        capture("combat-book-ten-slots.png");
        click(technique(SECOND));
        for (int key : new int[]{'1', '9', '0'}) {
            key(key, GLFW.GLFW_PRESS);
            key(key, GLFW.GLFW_RELEASE);
            int number = CombatBookSelection.slotNumber(key);
            checkState(CombatSlotAccess.snapshot().slots().get(number - 1).equals(SECOND), "数字映射错误");
        }
        key('1', GLFW.GLFW_PRESS);
        click(technique(FIRST));
        key('1', GLFW.GLFW_REPEAT);
        checkState(CombatSlotAccess.snapshot().slots().getFirst().equals(SECOND), "长按重复保存");
        key('1', GLFW.GLFW_RELEASE);
        var target = point(slot(1));
        var right = mouse(target.x(), target.y(), GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        var screen = Minecraft.getInstance().gui.screen();
        screen.mouseClicked(right, false /* doubleClick */);
        screen.mouseReleased(right);
        checkState(CombatSlotAccess.snapshot().slots().getFirst().isEmpty(), "右键没有清空");
        verifyPersistence();
        verifySaveFailure();
        next(Stage.FOCUS);
    }

    private void verifyPersistence() throws Exception {
        // 重读正式配置文件，确认窗口和命令共用客户端唯一保存路径。
        var path = FabricLoader.getInstance().getConfigDir().resolve("kizuna-meridian-combat.json");
        var loaded = new CombatConfigFile(path).load();
        checkState(loaded.slots().equals(CombatSlotAccess.snapshot().slots()), "保存后重载槽位不一致");
        checkState(CombatSlotAccess.set(1, "test:unlearned").status() == CombatSlotAccess.Status.REJECTED,
                "客户端允许配置未学功法");
    }

    private void verifySaveFailure() throws Exception {
        // 仅在隔离测试配置目录制造目标路径冲突，随后恢复真实文件。
        var path = FabricLoader.getInstance().getConfigDir().resolve("kizuna-meridian-combat.json");
        var backup = path.resolveSibling("combat-save-test-backup.json");
        Files.move(path, backup);
        Files.createDirectory(path);
        try {
            var toasts = Minecraft.getInstance().gui.toastManager();
            var token = CombatBookPanel.CONFIGURATION_ERROR;
            checkState(toasts.getToast(SystemToast.class, token) == null, "成功配置不应显示通知");
            key('1', GLFW.GLFW_PRESS);
            key('1', GLFW.GLFW_RELEASE);
            checkState(toasts.getToast(SystemToast.class, token) != null, "保存失败缺少可见通知");
            SystemToast.forceHide(toasts, token);
        } finally {
            Files.delete(path);
            Files.move(backup, path);
        }
        key('1', GLFW.GLFW_PRESS);
        key('1', GLFW.GLFW_RELEASE);
    }

    private void focus() throws Exception {
        // 窗口内输入框和其他活动窗口都不得触发数字配置。
        var before = CombatSlotAccess.snapshot().slots();
        var text = UIComponents.textBox(Sizing.fixed(TEXT_BOX_WIDTH));
        text.positioning(Positioning.absolute(0, 0));
        panel.child(text);
        panel.focusHandler().focus(text, UIComponent.FocusSource.MOUSE_CLICK);
        key('9', GLFW.GLFW_PRESS);
        Minecraft.getInstance().gui.screen().charTyped(new CharacterEvent('9'));
        key('9', GLFW.GLFW_RELEASE);
        checkState(text.getValue().equals("9"), "文本输入没有收到数字");
        checkState(CombatSlotAccess.snapshot().slots().equals(before), "文本输入触发配置");
        panel.removeChild(text);
        verifyOtherWindow(before);
        startCommand();
        next(Stage.COMMAND);
    }

    private void verifyOtherWindow(List<String> before) throws Exception {
        // 切换活动窗口会取消引用拖动，但保留本窗口的已有选择和配置。
        var runtime = InventoryUiClient.runtime();
        startDrag(technique(FIRST), slot(1));
        var other = runtime.open("meridian_ui:body", null);
        var bounds = other.bounds();
        var screen = Minecraft.getInstance().gui.screen();
        var header = mouse(bounds.x() + HEADER_CLICK_X, bounds.y() + HEADER_CLICK_Y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseClicked(header, false /* doubleClick */);
        screen.mouseReleased(header);
        key('9', GLFW.GLFW_PRESS);
        key('9', GLFW.GLFW_RELEASE);
        checkState(!panel.dragging(), "多窗口失焦没有取消拖动");
        checkState(CombatSlotAccess.snapshot().slots().equals(before), "非活动窗口接管数字");
        runtime.workspace().windows().close(other.key());
        click(technique(FIRST));
    }

    private void startCommand() {
        // 使用正式服务端命令节点，再从客户端投影确认结果。
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        serverTask = server.submit(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
            server.getCommands().performPrefixedCommand(source, "meridian combat slot set 4 " + SECOND);
        });
    }

    private void command() {
        // 服务端命令发回客户端后，窗口必须读到同一档案的更新。
        if (!serverTask.isDone() || !CombatSlotAccess.snapshot().slots().get(3).equals(SECOND)) {
            return;
        }
        serverTask.join();
        checkState(panel.slotId(4).equals(SECOND), "命令更新未反映到窗口");
        next(Stage.COMPACT);
    }

    private void compact(Minecraft client) {
        // GUI scale 改变后按框架最小外框缩放，十槽仍必须全部可见。
        client.options.guiScale().set(COMPACT_SCALE);
        client.resizeGui();
        InventoryUiClient.runtime().workspace().windows().resize(window.key(),
                Integer.toString(COMPACT_WIDTH), Integer.toString(COMPACT_HEIGHT));
        next(Stage.SCROLL);
    }

    private void scroll() {
        // 左页滚动与右页长文都经过真实滚动控件，底部保持固定。
        capture("combat-book-compact.png");
        checkState(slot(CombatIds.SLOT_COUNT).x() + slot(CombatIds.SLOT_COUNT).width()
                <= panel.x() + panel.width(), "最小窗口裁掉第十槽");
        var scroll = panel.childById(ScrollContainer.class, "library-scroll");
        Minecraft.getInstance().gui.screen().mouseScrolled(scroll.x() + 1, scroll.y() + 1, 0, -SCROLL_DISTANCE);
        var longKnown = originalKnown.toBuilder();
        for (int index = 0; index < longKnown.getTechniquesCount(); index++) {
            var entry = longKnown.getTechniques(index);
            if (entry.getId().equals(FIRST)) {
                var longEntry = entry.toBuilder()
                        .setDescription((entry.getDescription() + "\n").repeat(LONG_DESCRIPTION_REPEATS))
                        .build();
                longKnown.setTechniques(index, longEntry);
            }
        }
        sendProjection(longKnown.build());
        next(Stage.INVALID);
    }

    private void invalid() {
        // 长文截图来自实际渲染的有界服务端测试投影，随后空投影模拟内容撤下。
        if (!serverTask.isDone()) {
            return;
        }
        serverTask.join();
        capture("combat-book-long-content.png");
        var scroll = panel.childById(ScrollContainer.class, "detail-scroll");
        Minecraft.getInstance().gui.screen().mouseScrolled(scroll.x() + 1, scroll.y() + 1, 0, -SCROLL_DISTANCE);
        next(Stage.SCROLLED);
    }

    private void scrolled() {
        // 单独留一帧给滚动插值，截图显示实际移动后的正文与固定十槽。
        capture("combat-book-long-scroll.png");
        beforeInvalid = CombatSlotAccess.snapshot().slots();
        sendProjection(KnownTechniques.getDefaultInstance());
        next(Stage.CLOSE);
    }

    private void close(Minecraft client) throws Exception {
        // 失效选择不得写槽，原配置保留；关屏后数字事件不滞留到游戏下一帧。
        if (!serverTask.isDone()) {
            return;
        }
        serverTask.join();
        checkState(panel.selected().isEmpty() && !panel.dragging(), "空已学投影没有取消交互");
        checkState(CombatSlotAccess.snapshot().slots().equals(beforeInvalid), "失效数据删除了槽位");
        capture("combat-book-unavailable.png");
        key('1', GLFW.GLFW_PRESS);
        key('1', GLFW.GLFW_RELEASE);
        checkState(CombatSlotAccess.snapshot().slots().equals(beforeInvalid), "失效选择仍可写槽");
        checkState(client.player.getInventory().getSelectedSlot() == selectedHotbar, "配置数字穿透到快捷栏");
        for (var mapping : client.options.keyHotbarSlots) {
            checkState(!mapping.consumeClick(), "关屏后存在排队的快捷栏点击");
        }
        checkState(inventory(client).equals(inventoryBefore), "配置交互改变了背包物品");
        sendProjection(originalKnown);
        next(Stage.LEAK);
    }

    private void leak(Minecraft client) throws Exception {
        // 配置按键仍按着关闭工作台，再经过原版 repeat 事件与下一 tick，不能泄漏为游戏快捷键。
        if (!serverTask.isDone() || CombatSlotAccess.snapshot().known().getTechniquesCount() == 0) {
            return;
        }
        serverTask.join();
        click(technique(FIRST));
        key('9', GLFW.GLFW_PRESS);
        beforeInvalid = CombatSlotAccess.snapshot().slots();
        InventoryUiClient.runtime().workspace().windows().close(window.key());
        checkState(window.closed(), "窗口没有释放");
        client.gui.setScreen(null);
        key('9', GLFW.GLFW_REPEAT);
        next(Stage.RELEASE);
    }

    private void release(Minecraft client) throws Exception {
        // 下一游戏帧确认旧键未切快捷栏，松开后新按键应恢复正常语义。
        checkState(client.player.getInventory().getSelectedSlot() == selectedHotbar, "配置键关屏后泄漏");
        key('9', GLFW.GLFW_RELEASE);
        key('2', GLFW.GLFW_PRESS);
        key('2', GLFW.GLFW_RELEASE);
        next(Stage.GAMEPLAY);
    }

    private void gameplay(Minecraft client) {
        // 新按键已经在原版 tick 中切换快捷栏，再进行真实断线与重进。
        checkState(client.player.getInventory().getSelectedSlot() == 1, "关屏后没有恢复原版快捷键");
        client.disconnectWithSavingScreen();
        next(Stage.DISCONNECT);
    }

    private void reconnect(Minecraft client) {
        // 等待真实断线事件完成后重开同一测试世界，不能仅重读内存配置。
        if (ticks - stageTick < SETTLE_TICKS || client.getSingleplayerServer() != null) {
            return;
        }
        var disconnected = CombatSlotAccess.snapshot();
        checkState(disconnected.connection() == CombatSlotAccess.Connection.DISCONNECTED,
                "断线后门面仍显示连接状态");
        checkState(disconnected.known().getTechniquesCount() == 0, "断线保留已学展示");
        checkState(disconnected.slots().stream().allMatch(String::isEmpty), "断线保留连接槽位展示");
        checkState(CombatSlotAccess.set(1, FIRST).status() == CombatSlotAccess.Status.REJECTED, "断线仍允许写槽");
        client.createWorldOpenFlows().openWorld("combat-slot-test", () -> { });
        next(Stage.REJOIN);
    }

    private void rejoined(Minecraft client) {
        // 重进收到真实已学投影后，槽位恢复原档案且窗口重新订阅。
        if (CombatSlotAccess.snapshot().known().getTechniquesCount() == 0) {
            return;
        }
        checkState(CombatSlotAccess.snapshot().slots().equals(beforeInvalid), "重进同一世界没有恢复配置");
        var runtime = InventoryUiClient.runtime();
        runtime.openWorkspace();
        var reopened = runtime.open(CombatUiModule.FEATURE, null);
        checkState(CombatWindowProbe.content(reopened.key()) instanceof CombatBookPanel, "重进无法打开窗口");
        finish(client, null);
    }

    private static List<String> inventory(Minecraft client) {
        // 对比真实背包槽，引用拖动不得改变数量或身份。
        var inventory = client.player.getInventory();
        return java.util.stream.IntStream.range(0, inventory.getContainerSize())
                .mapToObj(index -> inventory.getItem(index).toString()).toList();
    }

    private void sendProjection(KnownTechniques snapshot) {
        // 边界夹具经正式网络通道发送，不直接访问客户端门面内部状态。
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        serverTask = server.submit(() -> ServerPlayNetworking.send(server.getPlayerList().getPlayer(playerId),
                CombatChannels.KNOWN.payload(snapshot.toByteArray())));
    }

    private UIComponent technique(String id) {
        // 按公开身份查找 XML 窗口中的真实组件，避免使用绘制坐标猜测选择。
        return panel.childById(UIComponent.class, "combat-technique-" + id);
    }

    private UIComponent slot(int number) {
        // 用同一槽号定位组件和校验配置。
        return panel.childById(UIComponent.class, "combat-slot-" + number);
    }

    private void next(Stage next) {
        // 等待下一批真实布局和网络帧。
        stage = next;
        stageTick = ticks;
    }

    private void capture(String name) {
        // 使用客户端帧缓冲，文件由原版截图写入器保存。
        var client = Minecraft.getInstance();
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> { });
        screenshots++;
    }

    private void finish(Minecraft client, String failure) {
        // Gradle 根据报告判定整体验收成败，失败栈保留在客户端日志。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure
                    + "\nscreenshots=" + screenshots + "\n");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        client.stop();
    }
}
