# 构建与依赖

内观新增必需的 `MeridianSceneExtension` 客户端接线，提供标题、锁定原因、组件、快照更新、按键优先权与关窗清理。Human-UI负责人体玩法；`BodyCanvas.effects`只投影模型空间实线圆环（`Marker` 的半径与线宽均为屏幕像素，颜色含义由附属决定）和光团颜色。修炼页的独立练习入口（`meridian_ui:practice`）要求札记权限且 `lockedReason()` 为空；未解锁时从菜单隐藏，直接打开也被拒绝。窗口工厂再次校验，保留统一锁形提示卡（`LockedNotice`）用于锁定提示；撤权关闭已有窗口并停止活动。吐纳入口独立要求聚气卷，经脉入口要求札记，读完对应残卷才显示相应菜单项。练习控件固定占场景底部 144 逻辑像素。`QiTransit.circuit` 按整圈线路和权威工作进度显示，普通运输仍按单段到达插值。不会通过 Framework 通用协议传人体坐标。

经脉操作卡扩展需要 Framework 当前源码的 `operation_ui.proto`，吐纳到货累计需要 `BreathingView.stored_qi`。这些是工作区契约变更，运行时须同步更新 Framework、Human Fabric 和 UI，不能只替换 UI JAR。多 Addon 的注册、更新、请求和卸载见[经脉操作卡](Meridian-Opening.md)。

Human-UI 是客户端必需运行依赖：`fabric.mod.json` 声明 `meridian_human_ui`，Gradle 固定装配同级仓库的安装 JAR。UI 只编译通用路线接口，Human-UI 编译使用 UI 接口与 Human 模型，二者不形成源码编译环。首次从空构建目录装配时，先在 UI 执行 `./gradlew :client:remapJar` 生成接口所在 JAR，再在 Human-UI 执行 `./gradlew build`，最后回 UI 执行完整 `./gradlew build`；之后修改接口也按此顺序重建。Framework、Human、UI、Items、Damage、Healing、Human-UI、Players-Animation 在适用端整套必装。处理后的依赖审计见 Human-UI 的 `docs/wiki/Dependency-Audit.md`。

## 本地构建

需要 Java 17、八个同级 Meridian/Animation 仓库，以及 Inventory UI、BS3D 的已构建产物。默认目录关系示例：

打坐预览通过 `modImplementation` 直接依赖同级 `Kizuna-Players-Animation` 的公开 API。`BreathingCanvas` 和 `MeditationAvatar` 不判断安装状态、不反射调用。吐纳要求动画侧已加载模型及 `meditate`，缺资源明确报错。当前动画 JAR 已包含双体型骨架与八段动画，2026-10-21 WN 验收直接使用 JAR 内资源；开发装配同样无需外置 `93f8b5b` 补丁资源包。预览按真实玩家选择 classic/slim；外世界同步由 Human 服务端调度，预览播放不作为外世界同步成功的依据。

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

Human Addon 由宿主装配人体内容，客户端通过必需的 Human-UI 与 UI 一起安装；UI 源码仍不引用人体实现。

人体专属光迹及后续人体表现归独立 `Kizuna-Meridian-Human-UI-Addon`，它依赖 Human 与本包，通过必需的客户端 `MeridianRouteProvider` 接入。UI 声明其为强制运行依赖；Framework 无新增光迹协议。修改接口后须先重新构建本包客户端 JAR，再构建 Human-UI。注册方式与约束见[经脉光迹](Meridian-Opening.md)。

经脉操作还要求 Framework 的 `opening.proto`、`BodyViewUpdate.opening/operations` 和 `OpeningSession`，以及 Inventory UI 的六参数 `Feature` 菜单可见性字段；先重建对应框架再构建本包。人体条件与操作卡由 Human 提供。单击/双击经脉仅选中高亮，F/G 直接操作；通用操作卡保留隐藏入口供宿主显式打开，不重复占用功能菜单。具体消息与宿主调度职责见[经脉操作接入](Meridian-Opening.md)。

窗口自适应还需要 Inventory UI 当前源码中的七参数 `UiWindowDefinition` 和 `ResponsiveLayout`。先重建框架 JAR 再构建本附属，运行时同步更新两个客户端产物；只更换 UI Addon 而保留旧窗口框架不满足此 API 依赖。这是本地构建要求，尚无对应的已发布版本号。

客户端已配置 Fabric Loom、Minecraft 1.20.1、Yarn 1.20.1+build.10、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1 与 owo-lib 0.11.2+1.20。静态布局使用 XML，公共相机与窗口 API 来自 Inventory UI。服务端提供纯 Java 聚气路由桥接，未内置 Minecraft 玩家或世界服务。

## 构建产物与验证范围

- `client/build/libs/kizuna-meridian-ui-addon-client-0.1.0-SNAPSHOT.jar`
- `server/build/libs/kizuna-meridian-ui-addon-server-0.1.0-SNAPSHOT.jar`

客户端 JAR 包含 Fabric 窗口入口、通用查看器及 XML；没有人体模型、演示、测试或另一份核心。宿主仍需提供经脉 Java 库与依赖，并向窗口交付已授权的身体快照。经脉框架目前没有 Fabric mod ID，不能只把 UI JAR 放进 `mods` 就视为完整部署。

`BodyPresentationTest` 验证非人体映射、快照状态与坏资源拒绝；游戏内交互及[手动吐纳](Gathering.md)由 Human-UI 的整合服 smoke 在真实玩家身体上验收（见下节）。生产身体查询入口、玩家实体绑定与资源协商仍由宿主接入。吐纳方向、## 游戏内验收

真实玩家由 Human 的 `fabric/` 宿主推送 `presentation.proto` 的 `BodyViewUpdate`。客户端在 `meridian:body_view` 接收有界 Protobuf，断线清理、拒绝跨连接迟到消息与倒退版本；此入口仅作展示。Human 适配目前提供行走/疾跑身体与疲劳数值，`I` → 检视 → 勾选肌肉可查看实际占用和恢复半衰期；默认只显示使用玩家基础皮肤的体表。精确当前可用力量/耐力使用宿主 `/meridian tissue <id>` 查询。UI 本身没有 Human 编译依赖。

这一路径依赖当前含 `BodyViewUpdate` 的框架源码。Human Fabric 宿主同时提供真实吐纳通道，UI 自动绑定并沿用原三维界面，详见[吐纳接入](Gathering.md)。盘坐预览须装入含 `meditate` 资源的 Player Animations。

2026-10-22 起本仓不再提供标题画面 demo 源集（原 `runDemo` 及 `uiSmoke`、`gatheringSmoke`、`responsiveSmoke` 三种验收模式）。修炼入口与内部层由 Progression 服务端授权，标题画面没有服务器无法打开，且客户端不得自行解锁。原检查点改在 `Kizuna-Meridian-Human-UI-Addon` 的整合服 smoke 中、在真实玩家身体上执行：

```bash
# 在 Human-UI 仓库运行；Linux 无窗口环境加 xvfb-run，Windows 直接运行 gradlew.bat。
xvfb-run -a -s "-screen 0 1920x1100x24" ./gradlew :client:runSmoke --offline --console=plain --no-watch-fs
```

| 原检查点 | 现由整合服 smoke 承担 |
|---|---|
| 五层显示、真实 Screen 点击选取、聚焦完成、背侧肌肉转向、全部可见器官与经脉的点击与信息、快照刷新保留视角与选择、中键清除选择、多层勾选与隐藏清除选择、全层关闭不可选、眼球叠加点击、GPU 实际出图、关窗清理（原 `uiSmoke`） | `BodyViewerJourney`，截图 `body-*.png` |
| 五组分辨率与 GUI 缩放下身体与吐纳窗口的边界、互不覆盖、组件与选择保留、详情滚轮、心脏点击、吐纳控件命中与权威会话保持、首次开始先显示指引（原 `responsiveSmoke`） | `ResponsiveJourney`，截图 `responsive-*.png` |
| 吐纳控件布局与悬浮明细、中键旋转、真实鼠标吸呼、肺容量上下限、吸呼音效播放与停止、精力消耗与真实摄入、关窗停止、独立重开与再次开始（原 `gatheringSmoke`） | `ManualBreathingJourney`，截图 `breathing-*.png` |
| 内存宿主的环境与身体守恒、跨环境窗口续窗 | 宿主逻辑，由 Human `HumanGatheringTest`、`HumanPlayerBodyTest` 与 Framework `OrganIntakeTest` 回归覆盖 |
| 体表剪影与原版玩家模型尺寸一致 | 暂无对应：该比对在退役前已失败（Human 表层模型与原版尺寸不一致），待单独修复后再补回 |

UI 生产源码不引用具体生物；客户端 JAR 不包含任何验收代码。具体宿主接口见[模型接入](Body-Viewer.md)。creenshots/responsive-*.png`。
