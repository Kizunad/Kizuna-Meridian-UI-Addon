package dev.kizuna.meridian.ui.api;

import dev.kizuna.meridian.ui.client.SummaryCanvas;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 新版体表窗口的正文，内容由生物附属给：一幅平面人形、受伤部位逐行、接在后面的几行说明，
 * 以及人形下方通栏的脚注（可以带悬浮说明，没有就不占行）。
 */
public record SurfaceReadout(Figure figure, List<Row> wounds, List<String> notes, List<Component> footnotes) {
    public SurfaceReadout {
        wounds = List.copyOf(wounds);
        notes = List.copyOf(notes);
        footnotes = List.copyOf(footnotes);
    }

    /** 平面人形；格子画在以人形左上角为原点的画布上、声明的宽高以内，格子的描边可以多出一像素。 */
    public interface Figure {
        int width();

        int height();

        void draw(SummaryCanvas canvas);
    }

    /** 一处受伤部位：名字、写成「当前/上限」的读数、当前占上限的比例。 */
    public record Row(String name, String value, double fraction) { }
}
