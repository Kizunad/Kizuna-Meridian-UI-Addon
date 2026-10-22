# Kizuna's Meridian UI Addon

经脉框架的独立 UI 附属，用于展示身体状态并引导真元操作。

已实现 XML/owo 身体模型窗口：内容包提供剪影、骨架、肌肉、器官和经脉几何，UI 通用地分层显示、点击选中、聚焦并读取身体信息。依赖 Meridian Mod Framework 与 Inventory UI Framework。服务端网络桥接仍待实现。

文档：[项目说明](docs/wiki/Home.md) · [职责与交互方向](docs/wiki/Architecture.md) · [构建与验收](docs/wiki/Integration.md) · [模型接入](docs/wiki/Body-Viewer.md)。

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

游戏内整体验收在 `Kizuna-Meridian-Human-UI-Addon` 的整合服 smoke（`./gradlew :client:runSmoke`）中运行，见[构建与验收](docs/wiki/Integration.md)。

默认使用同级 `Kizuna-Meridian-Mod-Framework` 与 `Kizuna-Inventory-UI-Framework`；路径可分别通过 `-PmeridianFrameworkDir` 和 `-PinventoryUiFrameworkDir` 指定。Inventory UI 必须包含服务端路由子项目，详见构建文档。

许可：PolyForm Noncommercial 1.0.0，见 [LICENSE](LICENSE)。
