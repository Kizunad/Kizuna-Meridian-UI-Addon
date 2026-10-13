# 吐纳素材

## 肺部空气图

资源为 `assets/meridian_ui/textures/gui/lungs.png` 与 `lungs_air.png`，均为 64 × 64 RGBA。前者保留蓝色边框与黑色肺腔，后者只保留肺腔掩码，运行时染淡蓝色并按空气比例裁剪。外部透明；肺腔纵向范围为 `[20, 54)`，透明边距不参与比例计算。

图像使用 Bong 的 `scripts/images/gen.py`，`hud` 档、透明背景，通过 CPA（`cliproxy` 后端）与 `gpt-image-2` 生成。原始图经最近邻缩至 32 × 32，规范为透明、纯黑与两种蓝色，再最近邻放大到 64 × 64，保持整齐像素边缘。原始生成图与提示副本在本地 `/tmp/meridian-breathing-assets/`。

完整提示词：

```text
transparent RGBA overlay texture: a single minimal pixel-art HUD icon of a pair of human lungs, centered front view, two simple rounded lobes connected to one short trachea. Strict 32 by 32 pixel grid aesthetic, large clean square pixels, ONLY a crisp medium blue outline with one pale blue highlight edge. Each lung lobe has a completely solid pure black #000000 interior. Outside the lungs is transparent. Flat 2D game UI asset. No inner anatomy, veins, text, symbols, shadow, glow, gradients or scenery. Each blue outline completely encloses its black lobe, so the interior can be filled by a dynamic air-capacity meter. This is pixel art, never ink wash or painterly.
```

## 呼吸录音

吸气与呼气来自同一条录音：[breathingsleeping.wav，christophe1138](https://freesound.org/people/christophe1138/sounds/554074/)。作者描述为 Rode VideomicNTG 录制的平静呼吸。页面标明 [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)，允许复制、修改和分发；该第三方素材不受本项目 PolyForm Noncommercial 限制。

采用作者公开的 [OGG 预览](https://cdn.freesound.org/previews/554/554074_9054093-lq.ogg)，选择两个相邻呼吸片段，处理后存入客户端资源：

| 文件 | 起始时间 | 时长 |
|---|---|---|
| `assets/meridian_ui/sounds/breathing/inhale.ogg` | 1.35 秒 | 1.90 秒 |
| `assets/meridian_ui/sounds/breathing/exhale.ogg` | 4.50 秒 | 2.10 秒 |

FFmpeg 后处理：120 Hz 高通、5000 Hz 低通、`afftdn=nf=-64:tn=1` 降噪、原录音增益 30 dB、120 ms 渐入、180 ms 渐出，转单声道 44100 Hz Vorbis（质量 4）。运行时音量上限 0.28，另受玩家音效音量控制，声音由 Minecraft 原生声音管理器播放。
