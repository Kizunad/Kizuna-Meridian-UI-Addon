# Kizuna's Meridian UI Addon

仓库：<https://github.com/Kizunad/Kizuna-Meridian-UI-Addon>。

这是经脉框架的独立 UI 附属。提供易上手的经脉操作界面是本包方向：查看身体与真元分布，选择器官配方，指定真元运输目标，并呈现权威执行结果。

## 当前状态

已建立 Java 17、Gradle 8.8 的 `client/` 与 `server/` 工程，并通过 composite build 复用框架协议与服务端 API。当前只有构建配置和包职责说明，没有运行时 UI 功能。

尚未接入 Fabric、XML/owo-ui、Inventory UI Framework、真实网络或游戏宿主。构建得到的是骨架 JAR，不能直接放入 Minecraft `mods` 目录游玩。当前也没有注册附属内容清单。

## 文档

- [职责与交互方向](Architecture.md)
- [构建与依赖](Integration.md)

文档编辑源为本仓库 `docs/wiki/`。GitHub Wiki 开关启用与 Wiki 页面发布是两件事；本次仅准备仓库内文档。
