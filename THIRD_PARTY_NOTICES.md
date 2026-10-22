# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

开脉完成／中断 OGG 由 `scripts/generate_opening_sounds.py` 数学合成，属于本项目原创占位素材；ffmpeg 只用于编码，不内嵌其程序或第三方录音。

| 组件 | 版本 / 来源 | 许可与使用 |
|---|---|---|
| Kizuna's Meridian Mod Framework | 独立仓库，初始构建基线 `90d2205` | PolyForm Noncommercial 1.0.0；外部编译依赖，不内嵌源码、生成类或 JAR |
| Kizuna's Inventory UI Framework | 独立仓库，需包含服务端公共路由；本次使用 `1a184a6` 加本地未提交改动 | MIT；客户端窗口与服务端路由均为外部编译依赖，不内嵌源码或 JAR |
| Gradle Wrapper | 8.8，[来源](https://github.com/gradle/gradle/tree/v8.8.0) | Apache License 2.0；从经脉框架复制启动脚本、Wrapper JAR 与校验配置，保留版权头及 JAR 内 LICENSE |
| Protocol Buffers | 4.36.2，由两个框架解析 | BSD 3-Clause；复用各框架生成消息，不单独打包运行库 |
| Kizuna's Meridian Human Addon | 同级独立仓库 | PolyForm Noncommercial 1.0.0；由必需的 Human UI Addon 装配，本仓源码不依赖 Human，人体几何不进入 UI JAR |
| Kizuna's Meridian Human UI Addon | 同级独立仓库 | PolyForm Noncommercial 1.0.0；必需客户端运行依赖，提供人体光迹，不内嵌源码或 JAR |
| 平静呼吸录音 | christophe1138，[breathingsleeping.wav](https://freesound.org/people/christophe1138/sounds/554074/) | CC0 1.0；裁剪、降噪后作为吸气与呼气 OGG 资源分发，详见[吐纳素材](docs/wiki/Breathing-Assets.md) |
| JUnit / Hamcrest | 4.13.2 / 1.3 | EPL 1.0 / BSD 3-Clause；仅整体回归测试 |

客户端构建使用 Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1、owo-lib 0.11.2+1.20 与 Fabric Loom。Minecraft 遵循 Mojang 条款；Fabric Loader、Fabric API、Loom 使用 Apache License 2.0，owo-lib 使用 MIT。它们不内嵌到本附属 JAR。
