# Kizuna's Meridian UI Addon

仓库：<https://github.com/Kizunad/Kizuna-Meridian-UI-Addon>。

这是经脉框架的独立 UI 附属。提供易上手的经脉操作界面是本包方向：查看身体与真元分布，选择器官配方，指定真元运输目标，并呈现权威执行结果。

## 当前状态

已建立 Java 17、Gradle 8.8 的 `client/` 与 `server/` 工程，同时依赖 Meridian Mod Framework 与 Inventory UI Framework。客户端已实现模型资源解码、XML/owo 窗口、分层显示、三角面选取、高亮、旋转、缩放、平滑聚焦与身体信息投影。“检视”窗口暂时只读，独立“吐纳”窗口提供可重复的左键吸气、右键呼气、肺部伸缩与五色灵气引导；“经脉”窗口提供站姿选脉、F/G 快捷键、境界筛选、真实开脉请求和在途光迹，选脉不弹窗，通用操作卡由宿主显式打开。服务端已有协议到 Inventory UI 公共路由的桥接，由宿主绑定身份、核心与事件调度。

Human 模型与坐标只存在于 Human Addon。本 UI 的正式 JAR 不包含人体依赖或演示数据。通过独立演示装配，在真实 Minecraft 1.20.1 / Fabric / Java 17 客户端验证了五层显示、点击心脏、聚焦和窗口清理。

Human Fabric 宿主已经通过 Protobuf 推送玩家身体快照、展示资源和开脉选项，并提供真实吐纳/开脉通道。其他宿主须自行交付已授权的数据并安排事件推进。经脉框架核心是普通 Java 库，实际部署还需宿主提供其运行类，不能仅将 UI JAR 放进 `mods` 就获得完整修炼玩法。

## 文档

- [职责与交互方向](Architecture.md)
- [构建与依赖](Integration.md)
- [模型资源与通用查看器](Body-Viewer.md)
- [独立修炼窗口](Gathering.md)
- [经脉窗口与开脉操作](Meridian-Opening.md)

文档编辑源为本仓库 `docs/wiki/`。GitHub Wiki 开关启用与 Wiki 页面发布是两件事；本次仅准备仓库内文档。
