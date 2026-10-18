# 构建与依赖

经脉操作卡扩展需要 Framework 当前源码的 `operation_ui.proto`，吐纳到货累计需要 `BreathingView.stored_qi`。这些是工作区契约变更，运行时须同步更新 Framework、Human Fabric 和 UI，不能只替换 UI JAR。多 Addon 的注册、更新、请求和卸载见[经脉操作卡](Meridian-Opening.md)。

## 本地构建

需要 Java 17、独立经脉框架和 Inventory UI Framework 两个仓库。默认目录关系：

打坐预览适配器编译还需已构建的 Player Animations JAR，默认读取同级 `Kizuna-Players-Animation`。可用 `-PplayerAnimationDir="/path/to/animation-checkout"` 指定。它是 `modCompileOnly` 可选依赖，正式运行未安装时使用生物静态模型；开发演示会装载此 JAR。打坐需该 checkout 已包含 `meditate` 资源，旧版本只有渲染 API 时会静态回退。

```text
Code/
├── Kizuna-Meridian-Mod-Framework/
├── Kizuna-Inventory-UI-Framework/
└── Kizuna-Meridian-UI-Addon/
```

先在 Inventory UI Framework 目录运行 `./gradlew build`，生成可由 Loom 重映射的客户端 JAR；之后在 UI Addon 目录运行：

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

框架不在同级时分别指定路径：

```bash
./gradlew -PmeridianFrameworkDir="/path/to/Kizuna-Meridian-Mod-Framework" -PinventoryUiFrameworkDir="/path/to/Kizuna-Inventory-UI-Framework" build
```

Windows 对应使用 `gradlew.bat`。Gradle Wrapper 包含分发校验和，源码编译目标为 Java 17。

## 依赖关系

双重依赖是本附属的必需关系：

| 本附属模块 | Meridian Mod Framework | Inventory UI Framework |
|---|---|---|
| `client` | `:client`，经脉 protoc 消息 | 根项目 `:`，窗口、HUD 与客户端通信 API |
| `server` | `:server`，权威核心及处理器 | `:server`，公共路由及宿主会话 API |

客户端经脉消息用 `implementation` 参与开发运行，Inventory UI 客户端通过 Loom `modImplementation files(...)` 接入已构建 JAR，以完成 Minecraft 命名重映射；服务端仍使用两项 `compileOnlyApi`。均不内嵌框架。实际宿主须提供匹配的两个框架及各自运行依赖。客户端不依赖经脉服务端核心，服务端不依赖 Inventory UI 客户端或 owo。

`local.meridian:client/server` 与 `local.inventory-ui:client/server` 只是 Gradle composite build 的本地依赖替换键，不是已经发布的 Maven 坐标。配置引用实际 checkout，不自动拉取或锁定远端提交，也不复制源码到本仓库。

经脉框架展示协议基线为 `bc7cdfc`；当前聚气接入还需要同目录工作区新增的 `gathering.proto` 与 `GatheringSession`，单独检出该展示基线不足以构建本轮代码。Inventory UI 必须使用包含 `server/` 公共路由的源码版本；本次接入使用本地 `1a184a6` 加未提交的服务端模块改动，单独检出 `1a184a6` 不足以构建本附属。这些改动属于 Inventory UI 仓库，尚未由本附属提交或发布。

经脉框架使用自己的 `shared/src/main/proto/`，Inventory UI 使用自己的公共协议目录生成消息。UI Addon 复用两套各司其职的 protoc 生成类，不复制 `.proto` 或维护平行消息。本轮模型资源协议新增于经脉框架，而非人体或 UI 私有目录。

Human Addon 仍由宿主作为具体生物内容安装，不是 UI 的强制前置。

经脉操作还要求 Framework 的 `opening.proto`、`BodyViewUpdate.opening/operations` 和 `OpeningSession`，以及 Inventory UI 的六参数 `Feature` 菜单可见性字段；先重建对应框架再构建本包。人体条件与操作卡由 Human 提供。单击/双击经脉仅选中高亮，F/G 直接操作；通用操作卡保留隐藏入口供宿主显式打开，不重复占用功能菜单。具体消息与宿主调度职责见[经脉操作接入](Meridian-Opening.md)。

窗口自适应还需要 Inventory UI 当前源码中的七参数 `UiWindowDefinition` 和 `ResponsiveLayout`。先重建框架 JAR 再构建本附属，运行时同步更新两个客户端产物；只更换 UI Addon 而保留旧窗口框架不满足此 API 依赖。这是本地构建要求，尚无对应的已发布版本号。

客户端已配置 Fabric Loom、Minecraft 1.20.1、Yarn 1.20.1+build.10、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1 与 owo-lib 0.11.2+1.20。静态布局使用 XML，公共相机与窗口 API 来自 Inventory UI。服务端提供纯 Java 聚气路由桥接，未内置 Minecraft 玩家或世界服务。

## 构建产物与验证范围

- `client/build/libs/kizuna-meridian-ui-addon-client-0.1.0-SNAPSHOT.jar`
- `server/build/libs/kizuna-meridian-ui-addon-server-0.1.0-SNAPSHOT.jar`

客户端 JAR 包含 Fabric 窗口入口、通用查看器及 XML；没有人体模型、演示、测试或另一份核心。宿主仍需提供经脉 Java 库与依赖，并向窗口交付已授权的身体快照。经脉框架目前没有 Fabric mod ID，不能只把 UI JAR 放进 `mods` 就视为完整部署。

`BodyPresentationTest` 验证非人体映射、快照状态与坏资源拒绝；真实客户端演示验证下述交互及[手动吐纳](Gathering.md)。生产身体查询入口、玩家实体绑定与资源协商仍由宿主接入。吐纳方向、瞄准、空气与消耗使用框架独立 Protobuf 消息。

## 游戏内演示

真实玩家可由 Human 的可选 `fabric/` 宿主推送 `presentation.proto` 的 `BodyViewUpdate`。客户端在 `meridian:body_view` 接收有界 Protobuf，断线清理、拒绝跨连接迟到消息与倒退版本；此入口仅作展示。Human 适配目前提供行走/疾跑身体与疲劳数值，`I` → 检视 → 勾选肌肉可查看实际占用和恢复半衰期；默认只显示使用玩家基础皮肤的体表。精确当前可用力量/耐力使用宿主 `/meridian tissue <id>` 查询。UI 本身没有新增 Human 编译依赖。

这一路径依赖当前含 `BodyViewUpdate` 的框架源码。Human Fabric 宿主同时提供真实吐纳通道，UI 自动绑定并沿用原三维界面，详见[吐纳接入](Gathering.md)。盘坐预览须装入含 `meditate` 资源的 Player Animations；下述标题界面演示使用独立账本。

可选 Human 仓库仅在显式传入路径时加入开发演示，生产 UI 仍不依赖具体生物：

```bash
./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" -PplayerAnimationDir="/path/to/animation-checkout" :client:runDemo
# Linux 无窗口环境的整体交互验收：
xvfb-run -a ./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" -PuiSmoke=true :client:runDemo
# 分辨率与 GUI 缩放的整体交互验收：
xvfb-run -a -s "-screen 0 1920x1200x24" ./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" -PplayerAnimationDir="/path/to/animation-checkout" -PresponsiveSmoke=true :client:runDemo
```

资源加载后普通演示自动打开工作台中的“检视”和“吐纳”窗口，后者在前。演示使用 Human 可信出生函数创建独立内存身体并接入真实聚气核心；`uiSmoke` 只打开检视窗口，使用只读样例进行模型回归。模型始终来自 Human 客户端 JAR。JavaExec 固定使用 Java 17。成功的模型 smoke 正常退出，在 `client/build/demo/body-smoke-ok.txt` 写入结果，`screenshots/` 保存各层与聚焦截图。

验收覆盖五层、真实 Screen 点击心脏、相机聚焦完成、快照刷新保留视角、中键清除选择、快照不变和窗口清理。演示代码与 Human 的依赖只属于 `src/demo`，不进入正式 UI JAR。具体宿主接口见[模型接入](Body-Viewer.md)。

`responsiveSmoke` 在同一客户端切换 1440×1000（GUI 2/4）、960×720（GUI 3）、1920×1080（自动/GUI 2），检查组件边界、区域互不覆盖、保留身体选择、模型点击及吐纳按钮输入。成功标记为 `client/build/demo/responsive-smoke-ok.txt`，截图保存在 `screenshots/responsive-*.png`。
