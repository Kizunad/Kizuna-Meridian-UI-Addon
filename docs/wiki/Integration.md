# 构建与依赖

## 本地构建

需要 Java 17、独立经脉框架和 Inventory UI Framework 两个仓库。默认目录关系：

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

经脉框架本轮展示协议基线为 `bc7cdfc`，包含 `presentation.proto`。Inventory UI 必须使用包含 `server/` 公共路由的源码版本；本次接入使用本地 `1a184a6` 加未提交的服务端模块改动，单独检出 `1a184a6` 不足以构建本附属。这些改动属于 Inventory UI 仓库，尚未由本附属提交或发布。

经脉框架使用自己的 `shared/src/main/proto/`，Inventory UI 使用自己的公共协议目录生成消息。UI Addon 复用两套各司其职的 protoc 生成类，不复制 `.proto` 或维护平行消息。本轮模型资源协议新增于经脉框架，而非人体或 UI 私有目录。

Human Addon 仍由宿主作为具体生物内容安装，不是 UI 的强制前置。

客户端已配置 Fabric Loom、Minecraft 1.20.1、Yarn 1.20.1+build.10、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1 与 owo-lib 0.11.2+1.20。静态布局使用 XML，公共相机与窗口 API 来自 Inventory UI。服务端仍为纯 Java 桥接骨架。

## 构建产物与验证范围

- `client/build/libs/kizuna-meridian-ui-addon-client-0.1.0-SNAPSHOT.jar`
- `server/build/libs/kizuna-meridian-ui-addon-server-0.1.0-SNAPSHOT.jar`

客户端 JAR 包含 Fabric 窗口入口、通用查看器及 XML；没有人体模型、演示、测试或另一份核心。宿主仍需提供经脉 Java 库与依赖，并向窗口交付已授权的身体快照。经脉框架目前没有 Fabric mod ID，不能只把 UI JAR 放进 `mods` 就视为完整部署。

`BodyPresentationTest` 验证非人体映射、快照状态与坏资源拒绝；真实客户端演示验证下述交互。身体网络查询、玩家实体绑定、生产资源协商、聚气和配方按钮尚未实现。

## 游戏内演示

可选 Human 仓库仅在显式传入路径时加入开发演示，生产 UI 仍不依赖具体生物：

```bash
./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" :client:runDemo
# Linux 无窗口环境的整体交互验收：
xvfb-run -a ./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" -PuiSmoke=true :client:runDemo
```

资源加载后自动打开工作台中的“身体”窗口。演示使用 Human 可信出生函数的只读样例快照和其客户端 JAR 内的模型资源。JavaExec 固定使用 Java 17。成功的 smoke 正常退出，在 `client/build/demo/body-smoke-ok.txt` 写入结果，`screenshots/` 保存五层与聚焦后的六张截图。

验收覆盖五层、真实 Screen 点击心脏、相机聚焦完成、快照刷新保留视角、中键清除选择、快照不变和窗口清理。演示代码与 Human 的依赖只属于 `src/demo`，不进入正式 UI JAR。具体宿主接口见[模型接入](Body-Viewer.md)。
