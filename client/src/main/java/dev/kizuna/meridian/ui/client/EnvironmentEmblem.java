package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringEnvironment;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;

/**
 * 吐纳窗口的灵气徽记：圆形像素画表示当地灵气偏哪一行，光晕和浮动光点表示浓淡。
 * 悬浮看一句带档位的简略读数，点一下打开「此地灵气」小窗看详细数值。数据只读吐纳视图带来的当地环境。
 */
final class EnvironmentEmblem extends BaseUIComponent {
    static final int ART = 48;
    static final int PADDING = 8;
    static final int SIZE = ART + PADDING * 2;
    /** 第二多的一行达到最多那行的这个比例时，徽记左右各画一半。 */
    static final double SPLIT_RATIO = 0.8;
    /** 光晕和光点按浓度连续变化，到这个浓度封顶（元/格）。 */
    static final double FULL_GLOW = 2.0;
    /** 每格不到这么多元算稀薄：沙漠、恶地一带。 */
    static final double THIN_BELOW = 0.5;
    /** 每格达到这么多元算充盈：沼泽、蘑菇岛、丛林一带。 */
    static final double RICH_FROM = 1.2;
    /** 点徽记打开的小窗。 */
    static final String DETAIL_FEATURE = "meridian_ui:aura";
    private static final int MAX_MOTES = 6;
    private static final double ORBIT_MILLIS = 9000;
    private static final float DIM = 0.55f;
    private static final Identifier GLOW = art("glow");
    private static final Identifier RING = art("ring");
    private static final List<String> ORDER = List.of("meridian:metal", "meridian:wood", "meridian:water",
            "meridian:fire", "meridian:earth", "meridian:none");
    private static final Map<String, String> NAMES = Map.of("meridian:metal", "金", "meridian:wood", "木",
            "meridian:water", "水", "meridian:fire", "火", "meridian:earth", "土", "meridian:none", "无属性");
    private static final Map<String, String> ARTS = Map.of("meridian:metal", "metal", "meridian:wood", "wood",
            "meridian:water", "water", "meridian:fire", "fire", "meridian:earth", "earth", "meridian:none", "none");
    private static final Map<String, Integer> TINTS = Map.of("meridian:metal", 0xF1DC9A, "meridian:wood", 0x7FD08A,
            "meridian:water", 0x7FB7E8, "meridian:fire", 0xF0946A, "meridian:earth", 0xD9B36C, "meridian:none", 0xC8D2D2);
    private GatheringEnvironment environment;
    private int shrink = 1;

    EnvironmentEmblem() {
        // 徽记本身固定大小；展开的明细画在组件范围之外的下方，不改变布局。
        sizing(Sizing.fixed(SIZE), Sizing.fixed(SIZE));
        id("breathing-emblem");
    }

    void update(GatheringEnvironment environment) {
        // 没有环境视图时不画，也不留悬浮。
        this.environment = environment;
        if (environment == null) {
            tooltip(List.<Component>of());
            return;
        }
        tooltip(List.of((Component) Component.literal(brief(environment)), Component.literal("点一下看详细数值")));
    }

    /** 现在悬浮里写的那句；还没有环境视图时是空串。 */
    String currentBrief() {
        return environment == null ? "" : brief(environment);
    }

    /** 悬浮的一句话，例如「寻常·偏木，约 0.80 元/格」。 */
    static String brief(GatheringEnvironment environment) {
        if (!environment.getAvailable()) {
            return "此地没有灵气可纳";
        }
        List<String> leading = leading(environment);
        String bias = leading.isEmpty() ? "" : "·偏" + String.join("", leading.stream().map(NAMES::get).toList());
        return String.format(Locale.ROOT, "%s%s，约 %.2f 元/格", level(environment), bias,
                environment.getConcentrationPerBlock());
    }

    /** 点开后的明细：五行和无属性各多少、总量、浓度、每秒上限、档位分界。 */
    static List<String> details(GatheringEnvironment environment) {
        if (!environment.getAvailable()) {
            return List.of("只有主世界的地界有灵气。");
        }
        var totals = totals(environment);
        double total = totals.values().stream().mapToDouble(Double::doubleValue).sum();
        var lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT, "此地灵气：%s", level(environment)));
        for (String attribute : ORDER) {
            double amount = totals.getOrDefault(attribute, 0.0);
            double share = total > 0 ? amount / total : 0;
            lines.add(String.format(Locale.ROOT, "%s %.0f 元（%.0f%%）", NAMES.get(attribute), amount, share * 100));
        }
        lines.add(String.format(Locale.ROOT, "总量 %.0f 元", total));
        lines.add(String.format(Locale.ROOT, "浓度 %.2f 元/格", environment.getConcentrationPerBlock()));
        lines.add(String.format(Locale.ROOT, "每秒最多纳 %.2f 元，在场的人共用", environment.getSupplyPerSecond()));
        lines.add(String.format(Locale.ROOT, "分档：不到 %.1f 稀薄，%.1f 以上充盈", THIN_BELOW, RICH_FROM));
        return List.copyOf(lines);
    }

    /** 画哪一行：最多的那行；第二多的够接近时两行都画。没有明细时画无属性的淡雾。 */
    static List<String> leading(GatheringEnvironment environment) {
        var sorted = totals(environment).entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList();
        if (sorted.isEmpty()) {
            return List.of();
        }
        if (sorted.size() > 1 && sorted.get(1).getValue() >= sorted.get(0).getValue() * SPLIT_RATIO) {
            return List.of(sorted.get(0).getKey(), sorted.get(1).getKey());
        }
        return List.of(sorted.get(0).getKey());
    }

    /** 浓淡换成 0 到 1：光晕亮度、图的明暗、光点个数都跟它走。 */
    static double glow(GatheringEnvironment environment) {
        return Math.max(0, Math.min(1, environment.getConcentrationPerBlock() / FULL_GLOW));
    }

    private static String level(GatheringEnvironment environment) {
        // 档位和那一行文字用同一组分界。
        if (environment.getConcentrationPerBlock() < THIN_BELOW) {
            return "稀薄";
        }
        return environment.getConcentrationPerBlock() >= RICH_FROM ? "充盈" : "寻常";
    }

    private static Map<String, Double> totals(GatheringEnvironment environment) {
        // 按属性合计；不认识的属性不参与画图和明细。
        var totals = new LinkedHashMap<String, Double>();
        for (Qi entry : environment.getQi().getEntriesList()) {
            if (NAMES.containsKey(entry.getAttribute())) {
                totals.merge(entry.getAttribute(), entry.getAmount(), Double::sum);
            }
        }
        return totals;
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 左键打开「此地灵气」小窗；已经开着时由工作台把它提到前面。
        if (click.button() != 0 || environment == null) {
            return false;
        }
        InventoryUiClient.runtime().open(DETAIL_FEATURE, null);
        return true;
    }

    /** 缩成一半大小，给小窗口用；画法不变。 */
    EnvironmentEmblem half() {
        shrink = 2;
        sizing(Sizing.fixed(SIZE / shrink), Sizing.fixed(SIZE / shrink));
        return this;
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        if (environment == null) {
            return;
        }
        if (shrink == 1) {
            drawFull(context);
            return;
        }
        // 以左上角为准整体缩小。
        var pose = context.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(1f / shrink, 1f / shrink);
        pose.translate(-x, -y);
        drawFull(context);
        pose.popMatrix();
    }

    private void drawFull(OwoUIGraphics context) {
        List<String> leading = leading(environment);
        String first = leading.isEmpty() ? "meridian:none" : leading.get(0);
        float glow = (float) glow(environment);
        int left = x + PADDING;
        int top = y + PADDING;
        // 光晕在最底下，颜色取主导那一行，越浓越亮。
        context.blit(RenderPipelines.GUI_TEXTURED, GLOW, x, y, 0, 0, SIZE, SIZE, SIZE, SIZE,
                ARGB.color(Math.round(glow * 255), TINTS.get(first)));
        // 图本身稀薄时发暗，充盈时原色。
        float shade = DIM + (1 - DIM) * glow;
        int tone = ARGB.colorFromFloat(1, shade, shade, shade);
        if (leading.size() < 2) {
            context.blit(RenderPipelines.GUI_TEXTURED, art(ARTS.get(first)), left, top, 0, 0, ART, ART, ART, ART, tone);
        } else {
            // 两行接近：左半画最多的，右半画第二多的。
            int half = ART / 2;
            context.blit(RenderPipelines.GUI_TEXTURED, art(ARTS.get(first)), left, top, 0, 0, half, ART, ART, ART, tone);
            context.blit(RenderPipelines.GUI_TEXTURED, art(ARTS.get(leading.get(1))), left + half, top, half, 0,
                    ART - half, ART, ART, ART, tone);
        }
        context.blit(RenderPipelines.GUI_TEXTURED, RING, left, top, 0, 0, ART, ART, ART, ART);
        motes(context, first, glow);
    }

    private void motes(OwoUIGraphics context, String attribute, float glow) {
        // 几个小光点绕着徽记慢慢转，个数跟浓度走；稀薄时一个都没有。
        int count = Math.round(glow * MAX_MOTES);
        double phase = (Util.getMillis() % (long) ORBIT_MILLIS) / ORBIT_MILLIS * Math.PI * 2;
        int color = ARGB.color(220, TINTS.get(attribute));
        double radius = ART / 2.0 + 3;
        for (int index = 0; index < count; index++) {
            double angle = phase + index * Math.PI * 2 / count;
            double wobble = Math.sin(phase * 3 + index) * 1.5;
            int moteX = (int) Math.round(x + SIZE / 2.0 + Math.cos(angle) * (radius + wobble));
            int moteY = (int) Math.round(y + SIZE / 2.0 + Math.sin(angle) * (radius + wobble));
            context.fill(moteX, moteY, moteX + 2, moteY + 2, color);
        }
    }

    private static Identifier art(String name) {
        // 徽记贴图都在同一个目录下。
        return Identifier.fromNamespaceAndPath("meridian_ui", "textures/gui/aura/" + name + ".png");
    }
}
