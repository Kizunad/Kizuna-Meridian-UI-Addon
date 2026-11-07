package dev.kizuna.meridian.ui.api;

import net.minecraft.resources.Identifier;

/** 经脉模型下方的一项操作；提供者只发布当前可见的操作。 */
public record MeridianAction(String id, String label, Identifier icon, String hint, Runnable activate) {
    public MeridianAction {
        // 稳定身份用于保留焦点；可用条件由提供者读取权威状态后筛选。
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(label);
        java.util.Objects.requireNonNull(icon);
        java.util.Objects.requireNonNull(hint);
        java.util.Objects.requireNonNull(activate);
    }
}
