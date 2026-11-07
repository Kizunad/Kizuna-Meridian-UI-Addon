# 集中设置接入

客户端 API 位于 `dev.kizuna.meridian.ui.api.settings`。集中入口、导航、窗口和草稿生命周期，
各模块继续拥有自己的业务校验、配置文件和运行状态。参考实现位于 `client/settings/combat/`，
通过 Combat 的 `CombatSettingsAccess` 调用业务，无反向依赖。

## 注册

在客户端 `fabric.mod.json` 声明 `meridian_settings` 入口，实现 `SettingsRegistration`：

```java
public void register(SettingsPageCatalog catalog) {
    // 客户端注册阶段提供独立页面，宿主不认识业务字段。
    catalog.register(this);
}
```

参考 `CombatSettingsProvider`，同时实现 `SettingsPageProvider`：
`id()` 提供稳定 Identifier（Combat 为 `meridian_combat:stance`），`title()` 提供本地化 Component，
`order()` 控制排序，`create(SettingsPageContext)` 创建独立会话。相同排序值按 ID 决胜。
所有入口执行完毕后冻结，重复 ID、空标题、空会话或缺少接线明确失败。
不每帧发现模块、不反射业务字段，也不建立通用字段 DSL。

## 打开与导航

```java
var result = MeridianSettings.open(CombatSettingsProvider.ID);
if (result.status() == MeridianSettings.Status.UNAVAILABLE) {
    // 显示原因，不缓存到未来登录。
    player.sendSystemMessage(Component.literal(result.message()));
}
```

`open()` 首次选择首页，已有窗口保留选择；`open(pageId)` 定向打开。
仅客户端线程、有效工作区与世界允许执行，未显示工作区时自动打开。
结果为 `OPENED`、`FOCUSED`、`DIRTY_PAGE`、`UNKNOWN_PAGE` 或 `UNAVAILABLE`。
未知页不回退，脏页切换显示“放弃修改并切换 / 继续编辑”，不自动保存。

固定功能为 `meridian_ui:settings`，窗口类型 `meridian_ui:settings_window`，objectId 始终为空。
公开 API 复用 `ClientRuntime.open(feature, null)` 与 `openWorkspace()`；pageId 不产生额外窗口。
重复打开保留会话和组件，程序切页与鼠标切页都经过 `SettingsNavigation.select`。

## 页面与保存失败

`SettingsPageSession.component()` 返回整页 owo 树，`dirty()` 表示未提交状态，`close()` 必须幂等。
布局采用 XML 模板，颜色来自工作区主题；业务校验、保存和错误展示由页面负责。
`CombatSettingsPage` 实际保存路径如下：

```java
var result = CombatSettingsAccess.save(settings);
if (result.status() == CombatSettingsAccess.Status.SAVED) {
    baseline = settings;
}
message(result.message());
```

失败不关闭页面，保留草稿。Combat 文件与 options 分开提交，页面逐项更新成功基线并显示两份结果。
取消不恢复整个 options，也不撤销其他入口或子预览已经提交的变更。

Combat 页的 `Lock current view` 表示鼠标同时带动镜头与人物朝向，按钮悬浮说明展示此语义。
目标锁定期间人物优先朝向目标，镜头仍跟随鼠标；解除目标锁后恢复联动。该优先级由 Combat 维护，UI 只保存偏好。

## 清理和预览

窗口 scope 及换页调用同一 `close()`。关闭按钮、Esc、离开工作区和断线按取消处理。
`cancelInput()` 处理临时失焦，不应丢弃草稿；`captureKey(KeyEvent)` 先于普通控件消费录入，
Esc 只取消录入。透明预览可通过 `ownsScreen(Screen)` 声明自己持有的屏幕，延续同一页会话。

参考 `CombatSettingsPage`：持有自己的 `CombatCameraScreen`，返回后清空引用；关闭时先标记已关闭，
再 `preview.abandon()`，防止清理回调重新打开父窗口。`Preview.close()` 释放相机覆盖和输入，
失效令牌不能重新进入战斗。预览保存只提交几何，父页之后取消不回滚该次保存。

## 验证

`SettingsNavigationTest` 用测试专用提供者验证排序、重复 ID、冻结、脏页拦截和单次关闭。
`./gradlew :client:runStanceSettingsTest` 使用真实完整客户端验证输入、镜头、实体和保存。
报告为 `client/build/stance-settings-results.txt`，截图在 `client/build/stance-settings-screens/`。
运行目录与正式安装分离，测试源码不打入发布包；完整装配仍须依赖协议与 JAR 匹配。
