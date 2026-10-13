# Kizuna's Meridian UI Addon

仓库：<https://github.com/Kizunad/Kizuna-Meridian-UI-Addon>。

这是经脉框架的独立 UI 附属。提供易上手的经脉操作界面是本包方向：查看身体与真元分布，选择器官配方，指定真元运输目标，并呈现权威执行结果。

## 当前状态

已建立 Java 17、Gradle 8.8 的 `client/` 与 `server/` 工程，同时依赖 Meridian Mod Framework 与 Inventory UI Framework。客户端已实现模型资源解码、XML/owo 窗口、分层显示、三角面选取、高亮、旋转、缩放、平滑聚焦与身体信息投影。服务端仍为桥接工程骨架。

Human 模型与坐标只存在于 Human Addon。本 UI 的正式 JAR 不包含人体依赖或演示数据。通过独立演示装配，在真实 Minecraft 1.20.1 / Fabric / Java 17 客户端验证了五层显示、点击心脏、聚焦和窗口清理。

尚未连接服务器身体查询或状态订阅；宿主须交付已授权的身体快照和资源标识。经脉框架目前仍是普通 Java 库，实际部署还需宿主提供其运行类，不能仅将 UI JAR 放进 `mods` 就获得完整修炼玩法。

## 文档

- [职责与交互方向](Architecture.md)
- [构建与依赖](Integration.md)
- [模型资源与通用查看器](Body-Viewer.md)

文档编辑源为本仓库 `docs/wiki/`。GitHub Wiki 开关启用与 Wiki 页面发布是两件事；本次仅准备仓库内文档。
