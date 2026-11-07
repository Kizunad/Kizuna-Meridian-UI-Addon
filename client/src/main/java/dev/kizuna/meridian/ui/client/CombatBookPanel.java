package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.WindowInputHandler;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.fabric.client.CombatSlotAccess;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Size;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static dev.kizuna.meridian.ui.client.CombatBookStyle.*;

/** XML 书页组件树；展示与引用交互独立于 Combat 的配置、校验和保存。 */
final class CombatBookPanel extends FlowLayout implements WindowInputHandler, AutoCloseable {
    private static final int SCROLLBAR_SPACE = 6;
    static final SystemToast.SystemToastId CONFIGURATION_ERROR = new SystemToast.SystemToastId();
    private static final long MILLIS_PER_SECOND = 1000;
    private final ClientRuntime runtime;
    private final dev.kizuna.inventoryui.window.UiWindowManager.WindowKey windowKey;
    private final FlowLayout content;
    private final FlowLayout library;
    private final FlowLayout detail;
    private final FlowLayout slots;
    private final CombatBookSelection selection = new CombatBookSelection();
    private final List<CombatBookTile> slotTiles = new ArrayList<>();
    private final AutoCloseable subscription;
    private CombatSlotAccess.Snapshot snapshot;
    private Map<String, KnownTechnique> known = Map.of();
    private List<KnownTechnique> libraryEntries = List.of();
    private KnownTechnique displayedDetail;
    private int arrangedWidth = -1;
    private int arrangedHeight = -1;
    private int pageWidth = TILE_WIDTH;
    private boolean closed;
    private net.minecraft.client.gui.screens.Screen inputScreen;

    CombatBookPanel(ClientRuntime runtime, dev.kizuna.inventoryui.window.UiWindowManager.WindowKey windowKey) {
        // 内容来自 XML，窗口生命周期负责解除 Combat 变化订阅。
        super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
        this.runtime = runtime;
        this.windowKey = windowKey;
        content = UIModelLoader.get(Identifier.fromNamespaceAndPath("meridian_ui", "combat"))
                .expandTemplate(FlowLayout.class, "combat-book", Map.of());
        library = content.childById(FlowLayout.class, "library");
        detail = content.childById(FlowLayout.class, "detail");
        slots = content.childById(FlowLayout.class, "slots");
        slots.gap(GAP);
        var settings = dev.kizuna.meridian.ui.client.settings.SettingsWidgets.button(runtime, "姿态设置", () -> {
            // 通过公开接口定位同一设置窗口，不为功法页另建配置副本。
            var result = dev.kizuna.meridian.ui.api.settings.MeridianSettings.open(
                    dev.kizuna.meridian.ui.client.settings.combat.CombatSettingsProvider.ID);
            if (result.status() == dev.kizuna.meridian.ui.api.settings.MeridianSettings.Status.UNAVAILABLE) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(result.message()));
            }
        });
        settings.id("stance-settings");
        content.child(settings);
        detail.gap(GAP * 2);
        library.gap(GAP * 2);
        child(content);
        initializeSlots();
        refresh();
        subscription = CombatSlotAccess.subscribe(this::refresh);
    }

    private void initializeSlots() {
        // 十个组件保持稳定，不因功法刷新丢失焦点或捕获状态。
        for (int number = 1; number <= CombatIds.SLOT_COUNT; number++) {
            var tile = new CombatBookTile(this, "", number);
            tile.id("combat-slot-" + number);
            slotTiles.add(tile);
            slots.child(tile);
        }
    }

    private void refresh() {
        // 只在快照变化时更新模型，左页忽略冷却变化以保留滚动与拖动来源组件。
        snapshot = CombatSlotAccess.snapshot();
        var entries = new LinkedHashMap<String, KnownTechnique>();
        snapshot.known().getTechniquesList().forEach(entry -> entries.put(entry.getId(), entry));
        known = Map.copyOf(entries);
        selection.update(known.keySet());
        var display = known.values().stream()
                .map(CombatBookPanel::libraryEntry)
                .sorted(java.util.Comparator.comparing(KnownTechnique::getId))
                .toList();
        if (!libraryEntries.equals(display)) {
            libraryEntries = display;
            rebuildLibrary();
        }
        updateDetail();
        if (snapshot.connection() == CombatSlotAccess.Connection.DISCONNECTED) {
            cancelInput();
        }
    }

    private static KnownTechnique libraryEntry(KnownTechnique entry) {
        // 冷却与可用原因不影响左页分组、标题或图标，避免周期推送重建来源组件。
        return entry.toBuilder()
                .clearCooldownRemainingMillis()
                .clearCooldownTotalMillis()
                .clearUnavailableReason()
                .build();
    }

    @Override
    public void layout(Size space) {
        // 与工作台内容区同步布局；底部固定，仅压缩上方两页滚动区域。
        if (width != arrangedWidth || height != arrangedHeight) {
            arrangedWidth = width;
            arrangedHeight = height;
            runAndDeferEvents(this::arrange);
        }
        super.layout(space);
    }

    private void arrange() {
        // 文字用明确宽度折行，避免 owo 首次挂载时按零宽度生成竖排。
        pageWidth = Math.max(1, width / 2 - MARGIN * 2);
        int pageHeight = Math.max(1, height - FOOTER_HEIGHT - TITLE_HEIGHT - MARGIN);
        int rightX = width / 2 + MARGIN;
        place("library-title", MARGIN, MARGIN, pageWidth, LINE_HEIGHT);
        place("detail-title", rightX, MARGIN, pageWidth, LINE_HEIGHT);
        place("library-scroll", MARGIN, TITLE_HEIGHT + MARGIN, pageWidth, pageHeight);
        place("detail-scroll", rightX, TITLE_HEIGHT + MARGIN, pageWidth, pageHeight);
        int footerY = height - FOOTER_HEIGHT + GAP;
        place("slots", MARGIN, footerY, width - MARGIN * 2, SLOT_HEIGHT);
        place("stance-settings", MARGIN, footerY + SLOT_HEIGHT + GAP, width - MARGIN * 2, TITLE_HEIGHT);
        int slotWidth = Math.max(1, (width - MARGIN * 2 - GAP * (CombatIds.SLOT_COUNT - 1))
                / CombatIds.SLOT_COUNT);
        slotTiles.forEach(tile -> tile.horizontalSizing(Sizing.fixed(slotWidth)));
        rebuildLibrary();
        rebuildDetail();
    }

    private void place(String id, int left, int top, int width, int height) {
        // 同一定位方法用于文字和滚动容器，缩放不影响业务身份。
        var component = content.childById(UIComponent.class, id);
        MeridianPanel.place(component, left, top, width, height);
        if (component instanceof LabelComponent label) {
            label.color(Color.ofArgb(paper(ThemeTokens.TEXT)));
        }
    }

    private void rebuildLibrary() {
        // 分组只来自服务端的精确已学版本所属卷；无卷信息的功法稳定平铺。
        selection.cancelDrag();
        library.clearChildren();
        if (libraryEntries.isEmpty()) {
            library.child(label("尚无已学功法", paper(ThemeTokens.MUTED_TEXT)));
            return;
        }
        var groups = new TreeMap<String, List<KnownTechnique>>();
        for (var entry : libraryEntries) {
            groups.computeIfAbsent(entry.getVolumeId(), ignored -> new ArrayList<>()).add(entry);
        }
        groups.values().forEach(this::addGroup);
    }

    private void addGroup(List<KnownTechnique> entries) {
        // 各卷标题采用内容声明；卷轴只作组图标，招式始终使用自己的技能图标。
        var first = entries.getFirst();
        if (!first.getVolumeId().isEmpty()) {
            library.child(groupHeading(first));
        }
        int availableWidth = Math.max(1, pageWidth - SCROLLBAR_SPACE);
        int columns = Math.max(1, availableWidth / TILE_WIDTH);
        int tileWidth = Math.max(1, (availableWidth - GAP * (columns - 1)) / columns);
        FlowLayout row = null;
        for (int index = 0; index < entries.size(); index++) {
            if (index % columns == 0) {
                row = UIContainers.horizontalFlow(Sizing.fill(FULL), Sizing.content()).gap(GAP);
                library.child(row);
            }
            var tile = new CombatBookTile(this, entries.get(index).getId(), 0);
            tile.id("combat-technique-" + entries.get(index).getId());
            tile.horizontalSizing(Sizing.fixed(tileWidth));
            row.child(tile);
        }
    }

    private FlowLayout groupHeading(KnownTechnique entry) {
        // 卷轴身份由功法框架按卷 ID 注册；这里仅创建展示图标，不接入库存拖放。
        var row = UIContainers.horizontalFlow(Sizing.fill(FULL), Sizing.content()).gap(GAP);
        var itemId = Identifier.parse(entry.getVolumeId() + "_scroll");
        if (BuiltInRegistries.ITEM.containsKey(itemId)) {
            row.child(UIComponents.item(new ItemStack(BuiltInRegistries.ITEM.getValue(itemId))));
        }
        String title = entry.getVolumeName();
        String bookName = entry.getBookName();
        if (!bookName.isBlank() && !title.startsWith(bookName)) {
            title = bookName + " · " + title;
        }
        if (title.isBlank()) {
            title = "未命名卷";
        }
        var heading = label(title, paper(ThemeTokens.ACCENT));
        heading.maxWidth(Math.max(1, pageWidth - TITLE_HEIGHT - SCROLLBAR_SPACE));
        row.child(heading);
        return row;
    }

    private void updateDetail() {
        // 剩余冷却随时间变化不重建长文，内容与可用原因变化时才替换说明。
        var entry = known.get(selection.selected());
        KnownTechnique normalized = null;
        if (entry != null) {
            normalized = entry.toBuilder().clearCooldownRemainingMillis().build();
        }
        if (!java.util.Objects.equals(displayedDetail, normalized)) {
            displayedDetail = normalized;
            rebuildDetail();
        }
    }

    private void rebuildDetail() {
        // 长文始终在右页滚动，不挤走十槽。
        detail.clearChildren();
        if (displayedDetail == null) {
            detail.child(label("选择左页功法\n查看简介与已知条件", paper(ThemeTokens.MUTED_TEXT)));
            return;
        }
        var entry = displayedDetail;
        var icon = new CombatBookTile(this, entry.getId(), 0);
        icon.id("combat-detail-icon");
        detail.child(icon);
        detail.child(label(name(entry).getString(), paper(ThemeTokens.ACCENT)));
        String description = entry.getDescription();
        if (description.isBlank()) {
            description = "暂无功法简介";
        }
        detail.child(label(description, paper(ThemeTokens.TEXT)));
        long cooldown = entry.getCooldownTotalMillis();
        detail.child(label("冷却：" + (double) cooldown / MILLIS_PER_SECOND + " 秒",
                paper(ThemeTokens.MUTED_TEXT)));
        if (!entry.getUnavailableReason().isBlank()) {
            detail.child(label("当前不可用：" + entry.getUnavailableReason(),
                    paper(ThemeTokens.TEXT)));
        }
    }

    private LabelComponent label(String text, int color) {
        // 所有动态正文使用内容宽度与 maxWidth，保留中文换行能力。
        var label = UIComponents.label(Component.literal(text));
        label.maxWidth(Math.max(1, pageWidth - SCROLLBAR_SPACE));
        label.color(Color.ofArgb(color));
        label.shadow(false /* shadow */);
        label.sizing(Sizing.content(), Sizing.content());
        return label;
    }

    void select(String id) {
        // 点击只更新选择与右页，不触碰配置。
        selection.select(id);
        updateDetail();
    }

    String selected() {
        // 供图标读取独立的选中态。
        return selection.selected();
    }

    KnownTechnique entry(String id) {
        // 失效槽返回空，保留槽中的原身份。
        return known.get(id);
    }

    String slotId(int number) {
        // 读取快照不创建第二份可写配置。
        return snapshot.slots().get(CombatIds.slotIndex(number));
    }

    void beginDrag(String id) {
        // 所有来源只携带 ID，不调用库存或服务端施放接口。
        selection.beginDrag(id);
    }

    void pressSource(String id) {
        // 鼠标重新按下之前，已取消的拖动不能恢复。
        selection.pressSource(id);
    }

    boolean dragging() {
        // 有效拖动用于落点高亮和 Escape 取消。
        return !selection.dragging().isEmpty();
    }

    void drop(double screenX, double screenY) {
        // 先消费临时引用；外部松开不改变任何槽位。
        String id = selection.takeDrag();
        if (id.isEmpty()) {
            return;
        }
        var target = runtime.workspace().windows().hitTest(screenX, screenY);
        if (target == null || !target.key().equals(windowKey)) {
            return;
        }
        for (int index = 0; index < slotTiles.size(); index++) {
            if (slotTiles.get(index).isInBoundingBox(screenX, screenY)) {
                showResult(CombatSlotAccess.set(index + 1, id));
                return;
            }
        }
    }

    void clear(int number) {
        // 与命令共用保存反馈，失效槽也可显式清空。
        selection.cancelDrag();
        showResult(CombatSlotAccess.clear(number));
    }

    private void showResult(CombatSlotAccess.Result result) {
        // 失败通过游戏通知说明，成功直接反映在槽位中。
        if (result.status() != CombatSlotAccess.Status.SAVED) {
            SystemToast.addOrUpdate(Minecraft.getInstance().gui.toastManager(), CONFIGURATION_ERROR,
                    Component.literal("功法配置"), Component.literal(result.message()));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 只由工作台给活动窗口路由；文本输入和其他可聚焦控件不能触发配置。
        var screen = Minecraft.getInstance().gui.screen();
        if (screen != inputScreen) {
            inputScreen = screen;
            selection.resetKeys();
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && dragging()) {
            selection.cancelDrag();
            return true;
        }
        var focused = focusHandler().focused();
        if (focused != null && !(focused instanceof CombatBookTile) && !(focused instanceof ScrollContainer<?>)) {
            return false;
        }
        if (event.modifiers() != 0 || selection.selected().isEmpty()
                || CombatBookSelection.slotNumber(event.key()) == 0) {
            return false;
        }
        var binding = selection.press(event.key());
        CombatSlotAccess.consumeConfigurationKey(event.key());
        if (binding != null) {
            selection.cancelDrag();
            showResult(CombatSlotAccess.set(binding.number(), binding.techniqueId()));
        }
        return true;
    }

    @Override
    public void keyReleased(KeyEvent event) {
        // 即使已经失焦也只清理锁存，不执行配置。
        selection.release(event.key());
    }

    @Override
    public void cancelInput() {
        // 工作台离开、菜单打开和窗口切换都只取消拖动，保留选择。
        selection.cancelDrag();
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 书页只覆盖上方阅读区，底部十槽采用工作台主题。
        if (!Minecraft.getInstance().isWindowActive()) {
            cancelInput();
        }
        book(context, new Bounds(x, y, width, Math.max(1, height - FOOTER_HEIGHT)));
        super.draw(context, mouseX, mouseY, partialTicks, delta);
        var entry = known.get(selection.dragging());
        if (entry != null) {
            icon(context, entry, new Bounds(mouseX + GAP, mouseY + GAP, ICON_SIZE, ICON_SIZE));
        }
    }

    @Override
    public void close() {
        // scope 清理可重复进入，订阅只释放一次。
        if (closed) {
            return;
        }
        closed = true;
        cancelInput();
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("无法释放功法窗口订阅", failure);
        }
    }
}
