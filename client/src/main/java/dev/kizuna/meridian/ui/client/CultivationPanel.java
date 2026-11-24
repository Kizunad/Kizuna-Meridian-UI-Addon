package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import io.wispforest.owo.ui.container.FlowLayout;

/** 吐纳窗口的正文。两版界面各有一种排法，模块只管喂快照、按 tick 续输入和关窗。 */
public interface CultivationPanel {
    /** 挂进窗口的组件。 */
    FlowLayout root();

    /** 三维场景；吸呼、旋转、缩放都在它上面。 */
    BreathingCanvas canvas();

    /** 换上新的聚气快照；pending 是还有请求没回执，error 是上一次请求被拒的原因（没有就是空串）。 */
    void update(GatheringView view, boolean pending, String error);

    /** 每个客户端 tick 调一次。 */
    void tick();

    /** 窗口关了：停掉输入，放掉场景资源。 */
    void close();
}
