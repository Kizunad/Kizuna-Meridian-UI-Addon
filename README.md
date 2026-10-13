# Kizuna's Meridian UI Addon

经脉框架的独立 UI 附属，用于展示身体状态并引导真元操作。

**当前为 Java 17 工程骨架，尚未实现游戏界面或网络桥接。** 已配置 `client/`、`server/` 和经脉框架的本地独立构建依赖。

文档：[项目说明](docs/wiki/Home.md) · [职责与交互方向](docs/wiki/Architecture.md) · [构建与依赖](docs/wiki/Integration.md)。

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

默认使用同级 `Kizuna-Meridian-Mod-Framework`；可用 `-PmeridianFrameworkDir="/path/to/framework"` 指定位置。

许可：PolyForm Noncommercial 1.0.0，见 [LICENSE](LICENSE)。
