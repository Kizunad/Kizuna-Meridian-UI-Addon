# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 版本 / 来源 | 许可与使用 |
|---|---|---|
| Kizuna's Meridian Mod Framework | 独立仓库，初始构建基线 `90d2205` | PolyForm Noncommercial 1.0.0；外部编译依赖，不内嵌源码、生成类或 JAR |
| Gradle Wrapper | 8.8，[来源](https://github.com/gradle/gradle/tree/v8.8.0) | Apache License 2.0；从经脉框架复制启动脚本、Wrapper JAR 与校验配置，保留版权头及 JAR 内 LICENSE |
| Protocol Buffers | 4.36.2，由框架解析 | BSD 3-Clause；复用框架生成消息，不单独打包运行库 |

当前尚未引入 Minecraft、Fabric、owo-lib 或 Inventory UI Framework。后续实际接入时同步登记依赖及许可。
