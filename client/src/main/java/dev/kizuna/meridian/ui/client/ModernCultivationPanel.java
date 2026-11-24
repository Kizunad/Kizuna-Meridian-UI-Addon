package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.WindowInputHandler;
import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * 新版吐纳窗口的正文。自上而下：垫深色底的三维场景（左上角是缩小的灵气徽记，受阻原因压在场景底边），
 * 余力一行带条，空气和纳入一行，「自动」勾选和主按钮一行；勾了自动再多两行代价说明；最底下一行留给悬停时才显示的操作提示。
 * 窗口被拉大时多出来的地方都给场景。状态和按钮的行为与旧版窗口是同一份（BreathingSession）。
 */
final class ModernCultivationPanel implements CultivationPanel {
    /** 缺省大小下场景那一块的高度。 */
    static final int WELL_HEIGHT = 90;
    private static final int FULL = 100;
    private static final int SHEET_WIDTH = 166;
    private static final int PADDING = 3;
    private static final int GAP = 3;
    private static final int FOOT_GAP = 2;
    private static final int LINE = BreathSheet.LINE;
    private static final int BUTTON_HEIGHT = 13;
    /** 按钮组件四周自带这么宽的空边，摆的时候往外让出来。 */
    private static final int BUTTON_INSET = 3;
    private static final int TOGGLE_WIDTH = 36;
    private static final int EMBLEM_MARGIN = 2;
    private static final int NOTICE_LINES = 2;
    private final WindowRoom room;
    private final BreathingCanvas canvas;
    private final BreathingSession session;
    private final FlowLayout root = new Root();
    private final FlowLayout content = UIContainers.verticalFlow(Sizing.fill(FULL), Sizing.fill(FULL));
    private final Well well = new Well();
    private final EnvironmentEmblem emblem = new EnvironmentEmblem().half();
    private final Notice notice = new Notice();
    private final ReserveRow reserve = new ReserveRow();
    private final Cell air = new Cell("breathing-air", Side.LEFT);
    private final Cell qi = new Cell("breathing-qi", Side.RIGHT);
    private final ModernLayerToggle automatic;
    private final ActionRow action;
    private final Note note = new Note();
    private final Hint hint = new Hint();
    private final IntakeStall stall = new IntakeStall();
    private BreathingView breath = BreathingView.getDefaultInstance();
    private GatheringEnvironment environment = GatheringEnvironment.getDefaultInstance();
    private String requestError = "";
    private List<String> intakeDetails = List.of();
    private int width;
    private int height;
    private int noteLines;

    ModernCultivationPanel(BodyScene scene, Consumer<BreathControl> sender, WindowRoom room) {
        this.room = room;
        canvas = new BreathingCanvas(scene, sender);
        canvas.captions(BreathingCanvas.Captions.TIGHT);
        session = new BreathingSession(canvas, sender);
        automatic = new ModernLayerToggle("自动", session::automaticSelected, session::toggleAutomatic).compact();
        automatic.id("breathing-automatic");
        action = new ActionRow(session::actionLabel, session::canAct, session::act);
        action.id("breathing-start");
        // 后加入的画在上面：徽记和受阻原因压着场景。
        for (var child : List.of(well, canvas, emblem, notice, reserve, air, qi, automatic, action, note, hint)) {
            content.child(child);
        }
        root.child(new ResponsiveLayout(content, this::arrange));
    }

    private enum Side { LEFT, RIGHT }

    /** 场景四周的一圈细边。 */
    private static final class Well extends BaseUIComponent {
        @Override
        public boolean isInBoundingBox(double x, double y) {
            return false;
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            graphics.fill(x, y, x + width, y + height, MeridianTheme.color(ThemeTokens.HEADER_RULE));
        }
    }

    /** 场景底边的受阻原因：只画字、不接鼠标。 */
    private final class Notice extends BaseUIComponent {
        Notice() {
            id("breathing-rejection");
        }

        @Override
        public boolean isInBoundingBox(double x, double y) {
            return false;
        }

        List<String> lines() {
            // 请求被拒写回执那句，一直纳不进写原因，都没有就是空的。
            var font = Minecraft.getInstance().font;
            long now = Util.getMillis();
            String reason = "";
            if (stall.stalled(now)) {
                reason = IntakeRejection.reason(stall.failure(now), environment);
            }
            return IntakeRejection.lines(requestError, reason, font::width, width);
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            // 贴着底边往上排，每行居中。
            var font = Minecraft.getInstance().font;
            var lines = lines();
            int top = y + height - lines.size() * LINE;
            for (String line : lines) {
                graphics.text(font, line, x + (width - font.width(line)) / 2, top,
                        MeridianTheme.color(ThemeTokens.INPUT_ERROR), false /* shadow */);
                top += LINE;
            }
        }
    }

    /** 余力一行。 */
    private final class ReserveRow extends BaseUIComponent {
        ReserveRow() {
            id("breathing-reserve");
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            BreathSheet.reserve(new WindowCanvas(graphics, x, y), width, reserve());
        }
    }

    /** 空气、纳入各占半行，各有自己的悬浮说明。 */
    private final class Cell extends BaseUIComponent {
        private final Side side;

        Cell(String id, Side side) {
            this.side = side;
            id(id);
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            var sheet = new WindowCanvas(graphics, x, y);
            int color = sheet.color(ThemeTokens.MUTED_TEXT);
            if (side == Side.LEFT) {
                sheet.text(0, 0, airText(), color);
            } else {
                sheet.text(width - sheet.width(intakeText()), 0, intakeText(), color);
            }
        }
    }

    /** 勾了自动以后常显的代价说明。 */
    private final class Note extends BaseUIComponent {
        Note() {
            id("breathing-automatic-note");
        }

        @Override
        public boolean isInBoundingBox(double x, double y) {
            return false;
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            var font = Minecraft.getInstance().font;
            int top = y;
            for (var line : noteText(width)) {
                graphics.text(font, line, x, top, MeridianTheme.color(ThemeTokens.MUTED_TEXT), false /* shadow */);
                top += LINE;
            }
        }
    }

    /** 鼠标停在窗口里才显示的一行操作提示。 */
    private final class Hint extends BaseUIComponent {
        Hint() {
            id("breathing-hint");
        }

        @Override
        public boolean isInBoundingBox(double x, double y) {
            return false;
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            if (root.isInBoundingBox(mouseX, mouseY)) {
                var font = Minecraft.getInstance().font;
                graphics.text(font, font.plainSubstrByWidth(canvas.hint(), width), x, y,
                        MeridianTheme.color(ThemeTokens.MUTED_TEXT), false /* shadow */);
            }
        }
    }

    private final class Root extends FlowLayout implements WindowInputHandler {
        Root() {
            super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            // 吐纳使用鼠标，未消费的键交回工作区。
            return false;
        }

        @Override
        public void keyReleased(KeyEvent event) {
            // 没有持续键盘输入需要释放。
        }

        @Override
        public void cancelInput() {
            // 切换窗口或最小化时只释放吸呼方向，自动吐纳继续运行。
            canvas.releaseInput();
        }
    }

    private List<net.minecraft.util.FormattedCharSequence> noteText(int innerWidth) {
        // 没勾自动、或者快照里还没有呼吸视图时不写。
        if (!session.automaticSelected() || !session.described()) {
            return List.of();
        }
        return Minecraft.getInstance().font.split(Component.literal(session.automaticDescription()),
                Math.max(1, innerWidth));
    }

    private int belowWell(int lines) {
        // 场景下面依次是：余力一行、空气纳入一行、按钮一行、代价说明（有才占）、提示行。
        int noteHeight = 0;
        if (lines > 0) {
            noteHeight = lines * LINE + FOOT_GAP;
        }
        return GAP + BreathSheet.RESERVE_HEIGHT + LINE + GAP + BUTTON_HEIGHT + FOOT_GAP + noteHeight + LINE + PADDING;
    }

    private void arrange(int nextWidth, int nextHeight) {
        // 下面几行高度固定、自下而上留位，剩下的都给场景。
        width = nextWidth;
        height = nextHeight;
        MeridianTheme.apply(content);
        int inner = Math.max(1, width - PADDING * 2);
        noteLines = noteText(inner).size();
        int wellHeight = Math.max(1, height - PADDING - belowWell(noteLines));
        MeridianPanel.place(well, PADDING, PADDING, inner, wellHeight);
        MeridianPanel.place(canvas, PADDING + 1, PADDING + 1, inner - 2, wellHeight - 2);
        int emblemSize = EnvironmentEmblem.SIZE / 2;
        MeridianPanel.place(emblem, PADDING + EMBLEM_MARGIN, PADDING + EMBLEM_MARGIN, emblemSize, emblemSize);
        int noticeHeight = NOTICE_LINES * LINE;
        MeridianPanel.place(notice, PADDING + GAP, PADDING + wellHeight - 1 - noticeHeight, inner - GAP * 2,
                noticeHeight);
        int reserveTop = PADDING + wellHeight + GAP;
        MeridianPanel.place(reserve, PADDING, reserveTop, inner, BreathSheet.RESERVE_HEIGHT);
        int cellsTop = reserveTop + BreathSheet.RESERVE_HEIGHT;
        int half = inner / 2;
        MeridianPanel.place(air, PADDING, cellsTop, half, LINE);
        MeridianPanel.place(qi, PADDING + half, cellsTop, inner - half, LINE);
        int buttonTop = cellsTop + LINE + GAP;
        MeridianPanel.place(automatic, PADDING, buttonTop, TOGGLE_WIDTH, BUTTON_HEIGHT);
        int buttonLeft = PADDING + TOGGLE_WIDTH + GAP;
        MeridianPanel.place(action, buttonLeft - BUTTON_INSET, buttonTop - BUTTON_INSET,
                width - PADDING - buttonLeft + BUTTON_INSET * 2, BUTTON_HEIGHT + BUTTON_INSET * 2);
        int noteTop = buttonTop + BUTTON_HEIGHT + FOOT_GAP;
        MeridianPanel.place(note, PADDING, noteTop, inner, Math.max(1, noteLines * LINE));
        MeridianPanel.place(hint, PADDING, Math.max(PADDING, height - PADDING - LINE), inner, LINE);
    }

    /** 余力一行现在写的和画的；自动吐纳只看呼吸耐力，条上标出暂停线和恢复线。 */
    BreathSheet.Reserve reserve() {
        var summary = BreathSummary.of(breath);
        if (!breath.getAutomatic()) {
            return new BreathSheet.Reserve("余力", summary.reservePercent(), summary.reserve(), List.of());
        }
        return new BreathSheet.Reserve("呼吸耐力", summary.reservePercent(), summary.reserve(),
                BreathSheet.automaticMarks(breath.getAutomaticMinimumStaminaRatio(),
                        breath.getAutomaticResumeStaminaRatio()));
    }

    String airText() {
        return BreathSummary.of(breath).airLabel();
    }

    String intakeText() {
        return "纳入 " + BreathSummary.of(breath).intakeAmount();
    }

    /** 主按钮现在写的字，和勾了自动以后常显的说明占几行；验收用。 */
    String actionLabel() {
        return session.actionLabel();
    }

    int noteLines() {
        return noteLines;
    }

    /** 「纳入」的悬浮说明现在有哪几行。 */
    List<String> intakeDetails() {
        return intakeDetails;
    }

    /** 这会儿场景底边写着的受阻原因。 */
    List<String> noticeLines() {
        return notice.lines();
    }

    @Override
    public FlowLayout root() {
        return root;
    }

    @Override
    public BreathingCanvas canvas() {
        return canvas;
    }

    private List<Component> intakeTooltip(GatheringView view, String blocked) {
        // 总账后面接窗口里不再常显的明细：相态、五行、摄入倍率；最后是受阻原因。
        var lines = BreathingTips.qi(view);
        StoredPhaseCaption.of(breath).ifPresent(text -> lines.add(Component.literal(text)));
        StoredElementCaption.of(breath).ifPresent(text -> lines.add(Component.literal(text)));
        IntakeCaption.of(breath).ifPresent(caption -> lines.add(Component.literal(caption.text())));
        if (!blocked.isEmpty()) {
            lines.add(Component.literal(blocked));
        }
        return lines;
    }

    @Override
    public void update(GatheringView view, boolean pending, String error) {
        session.update(view, pending);
        requestError = error;
        GatheringEnvironment place = null;
        if (view != null && view.hasEnvironment()) {
            place = view.getEnvironment();
        }
        emblem.update(place);
        if (view != null) {
            canvas.update(view);
        }
        if (view == null || !view.hasBreathing()) {
            breath = BreathingView.getDefaultInstance();
            environment = GatheringEnvironment.getDefaultInstance();
            stall.observe(breath, Util.getMillis());
            String unavailable = UiErrors.BREATHING_UNAVAILABLE;
            if (!error.isEmpty()) {
                unavailable = error;
            }
            action.tooltip(Component.literal(unavailable));
            for (var readout : List.of(reserve, air, qi)) {
                readout.tooltip(List.<Component>of());
            }
            intakeDetails = List.of();
            return;
        }
        breath = view.getBreathing();
        environment = view.getEnvironment();
        stall.observe(breath, Util.getMillis());
        automatic.tooltip(Component.literal(session.automaticDescription()));
        reserve.tooltip(BreathingTips.reserve(breath));
        air.tooltip(BreathingTips.air(breath));
        String blocked = BreathingTips.blocked(breath, error);
        var details = intakeTooltip(view, blocked);
        intakeDetails = details.stream().map(Component::getString).toList();
        qi.tooltip(details);
        String feedback = breath.getStatus();
        if (!blocked.isEmpty()) {
            feedback = blocked;
        }
        action.tooltip(Component.literal(feedback));
    }

    @Override
    public void tick() {
        canvas.tickInput();
        if (width <= 0) {
            return;
        }
        // 勾选自动、或代价说明换了行数，下面几行要重排；窗口跟着要到放得下缺省场景的高度。
        int lines = noteText(SHEET_WIDTH - PADDING * 2).size();
        if (noteText(Math.max(1, width - PADDING * 2)).size() != noteLines) {
            arrange(width, height);
        }
        room.content(SHEET_WIDTH, PADDING + WELL_HEIGHT + belowWell(lines));
    }

    @Override
    public void close() {
        session.close();
    }
}
