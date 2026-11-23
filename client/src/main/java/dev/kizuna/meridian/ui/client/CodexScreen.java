package dev.kizuna.meridian.ui.client;

import dev.kizuna.inventoryui.client.VanillaHudHidden;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 翻开的功法谱：屏幕正中两页羊皮纸，没有窗口外框和工作台按钮；Esc 合上。
 * 书没盖住的边上不画原版的聊天、快捷栏和状态栏，合上以后照常。
 */
final class CodexScreen extends BaseOwoScreen<FlowLayout> implements VanillaHudHidden {
    private static final int MAX_WIDTH = 600;
    private static final int MAX_HEIGHT = 390;
    private static final int EDGE = 8;
    private CodexPanel panel;

    private CodexScreen() {
        // 屏幕标题给旁白读，书页里另写「功法谱」。
        super(Component.translatable("item.meridian_technique.technique_codex"));
    }

    static void open() {
        // 右键功法谱时在客户端主线程翻开。
        Minecraft.getInstance().gui.setScreen(new CodexScreen());
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        // 整屏一层，书放正中。
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        // 书页内容和原型同一份；大小跟着屏幕走，最大与原型窗口一样大。
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);
        panel = new CodexPanel();
        root.child(panel);
        fit();
    }

    @Override
    protected void init() {
        // 窗口改了大小重新量一次书。
        super.init();
        fit();
    }

    private void fit() {
        // 小屏幕上四边各留一点空，不贴边。
        if (panel != null) {
            panel.sizing(Sizing.fixed(Math.min(MAX_WIDTH, width - EDGE * 2)),
                    Sizing.fixed(Math.min(MAX_HEIGHT, height - EDGE * 2)));
        }
    }

    CodexPanel panel() {
        // 客户端验收读取翻到哪本书。
        return panel;
    }

    @Override
    public void removed() {
        // 合上书时把这本书播完的新卷记成看过，并解除已学列表订阅。
        if (panel != null) {
            panel.close();
        }
        super.removed();
    }
}
