package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.*;

/** 吐纳窗口独占一个低音量声道，权威空气边界决定是否继续播放。 */
final class BreathingAudio {
    private BreathAction action = HOLD;
    private BreathSound sound;

    void update(BreathingView view, BreathAction held, boolean blocked) {
        // 按真实吸呼和肺容量判定声音，按住键到达边界后也会停下。
        BreathAction desired = HOLD;
        if (view.getAvailable() && view.getActive() && !blocked && held == view.getAction()) {
            if (held == INHALE && view.getAirLiters() < view.getCapacityLiters()) {
                desired = INHALE;
            } else if (held == EXHALE && view.getAirLiters() > 0) {
                desired = EXHALE;
            }
        }
        if (desired == action) {
            return;
        }

        // 回执和输入心跳不重播；切换方向先停止旧实例，快速交替也不会堆积。
        if (desired == HOLD) {
            stop();
            return;
        }
        var manager = MinecraftClient.getInstance().getSoundManager();
        if (sound != null) {
            manager.stop(sound);
        }
        String path = desired == INHALE ? "breathing.inhale" : "breathing.exhale";
        sound = new BreathSound(new Identifier("meridian_ui", path));
        action = desired;
        manager.play(sound);
    }

    void stop() {
        // 松键、教学和失焦采用短淡出，避免截断录音产生突兀边缘。
        action = HOLD;
        if (sound != null) {
            sound.fadeOut();
        }
    }

    void close() {
        // 关窗立刻释放自己的声音，不影响游戏中的其他音效。
        if (sound != null) {
            MinecraftClient.getInstance().getSoundManager().stop(sound);
            sound = null;
        }
        action = HOLD;
    }

    private static final class BreathSound extends AbstractSoundInstance implements TickableSoundInstance {
        private static final float MAX_VOLUME = .28f;
        private float targetVolume = MAX_VOLUME;
        private boolean done;

        BreathSound(Identifier id) {
            // 界面声音不随相机距离衰减，沿用玩家音效音量设置；轻声循环支持持续吸呼。
            super(id, SoundCategory.PLAYERS, Random.create());
            repeat = true;
            repeatDelay = 0;
            relative = true;
            attenuationType = AttenuationType.NONE;
            volume = .01f;
            pitch = 1;
        }

        void fadeOut() {
            // 声音系统按 tick 完成约 150 ms 的淡出，UI 不另建音频线程。
            targetVolume = 0;
        }

        @Override
        public void tick() {
            // 有界线性渐变让声音平缓开始和结束，归零后由原生声音管理器回收。
            if (volume < targetVolume) {
                volume = Math.min(targetVolume, volume + .095f);
            } else {
                volume = Math.max(targetVolume, volume - .095f);
            }
            done = targetVolume == 0 && volume == 0;
        }

        @Override
        public boolean isDone() {
            // 将播放生命周期交给 Minecraft，关闭窗口还会主动停止同一实例。
            return done;
        }
    }
}
