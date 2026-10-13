# 构建与依赖

## 本地构建

需要 Java 17 和独立经脉框架仓库。默认目录关系：

```text
Code/
├── Kizuna-Meridian-Mod-Framework/
└── Kizuna-Meridian-UI-Addon/
```

在 UI Addon 目录运行：

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

框架不在同级时使用：

```bash
./gradlew -PmeridianFrameworkDir="/path/to/Kizuna-Meridian-Mod-Framework" build
```

Windows 对应使用 `gradlew.bat`。Gradle Wrapper 包含分发校验和，源码编译目标为 Java 17。

## 依赖关系

客户端编译依赖框架 client，服务端桥接编译依赖框架 server。两端均不内嵌依赖，运行时由各自宿主提供匹配的框架和 Protobuf 运行库。客户端不会通过本工程依赖服务端实现。

`local.meridian:client/server` 只是 Gradle composite build 的本地依赖替换键，不是已经发布的 Maven 坐标。初始构建验证使用框架 `90d2205`；配置引用实际 checkout，不自动拉取或锁定远端提交。

框架构建使用自己的 `shared/src/main/proto/` 生成消息。UI Addon 复用 protoc 生成类，不复制 `.proto` 或维护平行消息。本次没有新增通信语义，因此没有新增协议文件。

当前工程未依赖 Human Addon 或 Inventory UI Framework。前者作为具体生物内容由宿主安装；后者在真正实现窗口接入时再引入相应公开 API 和游戏依赖。

## 构建产物与验证范围

- `client/build/libs/kizuna-meridian-ui-addon-client-0.1.0-SNAPSHOT.jar`
- `server/build/libs/kizuna-meridian-ui-addon-server-0.1.0-SNAPSHOT.jar`

当前 JAR 仅用于验证工程与分发边界，没有界面、路由实现或 Fabric 加载入口，也没有自动导出的 `AddonBundle`。`build` 验证编译和打包；文档脚本检查本地链接。骨架没有业务测试，通过构建不代表通过游戏内功能验收。

后续增加实际交互时，保留覆盖 Protobuf 往返、服务端拒绝与刷新行为、客户端与服务端依赖隔离的必要整体回归。
