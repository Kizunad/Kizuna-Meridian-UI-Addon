# 经脉窗口与操作卡

状态：已实现独立经脉场景、点击选中高亮、快捷键、权威开始/暂停请求，以及普通扩散与开脉的真实在途展示。紧凑条形面板支持宿主显式打开及多个 Addon 注册操作。界面使用 XML + owo 和 Inventory UI 窗口 API，模型与规则由生物附属及宿主提供。

## 游戏内操作

安装匹配版本的两个框架、UI 和生物宿主后，按 `I` → 修炼 → 经脉。Human Fabric 自动提供人体模型、身体快照与可用开脉规则。

主窗口保持站立黑色半透明模型。未拓宽经脉为极淡黄色，已拓宽经脉为微亮黄色。拖动旋转、滚轮缩放、中键复位；选择和快照刷新保留用户视角。

| 输入 | 结果 |
|---|---|
| 单击或双击经脉 | 选中并高亮，保留视角，不弹出信息或操作窗口 |
| `F` | Focus，聚焦当前选中经脉 |
| `G` | 凝聚真元或暂停，通过所选操作的权威请求执行 |
| 点击操作选择栏 | 多操作时循环切换，显示所属命名空间；单一操作隐藏此栏；选择不发送请求 |
| 点击操作按钮 | 提交所选操作的意图、精确版本和当前身体 revision；文案由 Addon 提供 |
| 关闭主窗口 | 关闭附属浮窗并释放渲染资源，服务端任务按显式暂停和宿主生命周期处置 |

菜单只保留一个「经脉」。标题「经脉 · 内观」右侧圆形 `i` 悬浮显示 F/G 快捷键，没有常驻提示或首次教程。内容区可滚动，操作按钮固定在底部，适配分辨率与 GUI 缩放；快捷键在模型画布和操作控件获得焦点时均可使用。

经脉点击和 F/G 快捷键均不自动打开浮窗。宿主需要展示通用操作卡时，可显式调用 Inventory UI 的 `runtime.open("meridian_ui:meridian_operation", null)`，卡片读取当前所选经脉；此入口不占用功能菜单。以下操作选择栏、按钮和条形说明适用于显式打开的操作卡。

操作卡没有数值清单，悬浮条形查看条件和调整方向。实体填充为已到账，淡色断续段为在途预约。绿、金、灰黄和红分别表示已满足、运行、等待和阻塞。严格门槛处即使条满格仍可显示等待，恰好达到不代表严格超过。失败时保持简短状态，悬浮查看具体原因。

状态或失败文案为空时保留布局槽并移除文字标签；说明仍可从槽位悬浮查看。恢复文案时复用原标签，避免 owo 0.11.2 对空文字执行悬浮或点击查询时越界，也适用于未提供状态文案的附属卡片。

生物展示节点可声明 `minimum_realm`，UI 根据权威身体境界累计筛选绘制与选取，降境后清除失效选择和浮窗。未解锁端点的在途光迹也隐藏。省略字段的第三方资源继续完整展示；人体分组由 Human 提供，UI 不内置经脉名单。展示筛选不替代执行权限，拓宽授权仍由服务端内容策略检查。

开脉卡由内容附属按权威身体生成；Human 当前提供全身准备、局部凝聚、参与组供给、持续摄入、器官/组织余力和连续适应。活动任务使用固定规则，UI 不解释策略 Any，也不重新判断人体门槛。Human 需要同时配合[吐纳窗口](Gathering.md)持续摄入。

## 真元展示口径

所有页面通过 `QiDisplay` 汇总同一身体快照，每笔资源只属于内腔、外壁、器官缓存/加工输入、在途之一。预约不额外增加总量。

吐纳「纳入」读取 `BreathingView.stored_qi`：当前连接内，炼化产物实际抵达经脉后才累计。`absorbed_qi` 是炼化前灵气，单独放在悬浮说明中；Human 初始炼化效率为 20%。累计入脉不随消耗回减，也不含出生真元和管理员注入，因此与当前余额含义不同。身体推送同时刷新吐纳总账提示，避免独立窗口停留在旧余额。

`MeridianFlow` 展示真实 `MOVING` 记录中的游离态真元，包含器官出口、日常被动扩散和开脉，最多 96 条光迹。方向来自实际连接，轨迹沿生物网格边提取并按弧长插值。每个模块用首个绑定网格；器官与经脉按类型和 ID 联合寻址。缺少端点几何的线路不绘制。

主要亮度来自 `opening.progress`，`OPENED` 使用完成亮度；局部储量只增加微弱底光，未开满储量仍保持淡黄色。客户端仅平滑展示；到达终点后等新快照，不循环制造货物。模型独立离屏渲染，文字与浮窗按正常 UI 顺序叠放。

## 多 Addon 接入

唯一数据契约为 Framework 的 `operation_ui.proto`。公开 API 均在客户端线程调用。

1. 用 `BodyUiModule.registerMeridianOperations(namespace, sender)` 注册连接内发布者，保存返回的撤销句柄。`meridian` 保留给通用开脉请求适配器，其他 Addon 不能注册它的 sender；注册顺序决定卡片顺序。
2. 先用 `present/update` 交付权威身体，再调用 `updateMeridianOperations(MeridianOperationUpdate)`，或由宿主通过 `BodyViewUpdate.operations` 一同推送。消息必须带当前身体 ID 与精确 revision，每包完整替换自身命名空间。空表只撤销自身操作。可信宿主可在 `meridian` 命名空间发布内容附属生成的开脉卡，UI 本身不生成条件卡。
3. 卡片声明目标、标题、阶段、进度条、当前动作 ID/文案、可用性与调整提示。每包最多 128 个操作、每卡最多 32 条、总包最多 256 KiB。ID 须属于发布者，目标须存在，进度为有限 `[0,1]`。
4. 玩家点击执行，sender 收到 `MeridianOperationRequest`：请求 ID、身体/经脉、操作 ID/版本、预期 revision 和动作 ID。附属自行通过 Protobuf 路由到其服务端，重新校验身份、知识、资源与版本；`enabled` 不是执行授权。
5. 回执调用 `receiveMeridianOperation(ResponseEnvelope)`，随后推送权威身体与新卡片。成功回执本身不改变资源。单请求背压和超时提示由窗口管理，超时不代表服务端已停止。
6. 身体更新后旧卡保留但禁用，等所属附属刷新。卸载调用撤销句柄，断线清空目录与闭包，重连须重新注册。

不接收脚本或任意 UI 组件树。当前没有接受任意第三方动作的通用 Fabric 服务端执行器，各附属负责自己的路由与规则。开脉卡 ID 为 `meridian:opening/<目标ID>`，适配器按当前目标查找可信规则并转换为既有 `MeridianOpeningRequest`；缺少规则或 sender 时禁用。内容包改变条件或进度条无需修改 UI，UI 不直接访问 Store。

## 开脉宿主接入

- `BodyViewUpdate.opening` 提供可选规则：存在即替换，空表撤销，省略保留。
- `BodyViewUpdate.operations` 携带内容附属生成的同版本卡片；Java 宿主也可调用 `updateMeridianOperations`。仅绑定规则不会自动生成开脉条件卡。
- Fabric 通道为 `meridian:opening_request/response`；请求为 `MeridianOpeningRequest`，响应为框架 `ResponseEnvelope`，后续身体推送提供事实。
- Java 宿主使用 `bindOpening(rules, sender)`、`receiveOpening(response)` 与 `present/update`。
- Inventory 公共路由为 `meridian_ui:opening_request/response`。`GatheringBridge(connection, gatheringSession, openingSession)` 同时提供吐纳与开脉，旧两参数构造仍只提供吐纳。

`OpeningSession` 校验客户端原 revision、结清旧活动区间、复验权限并去重。客户端不提交时间、余额或成功结果。`GatheringBridge.advance/nextBoundaryMillis` 只编排吐纳，宿主另按 `BodyTimeAdvancement.nextEventBoundary` 推进身体并发布授权快照。关闭桥接不自动暂停开脉；Human Fabric 已接入调度和断线暂停。

## 验证

```bash
./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" build :client:compileDemoJava
xvfb-run -a ./gradlew -PhumanAddonDir="../Kizuna-Meridian-Human-Addon" -PopeningSmoke=true :client:runDemo
python3 "scripts/check_docs.py"
```

真实客户端回归覆盖单击/双击不弹窗且无身体变更、无浮窗时的 F/G 聚焦与开始/暂停、宿主显式打开操作卡、第二 Addon 独立请求/拒绝/卸载、空状态及反馈显隐后的悬浮/点击、选择无副作用、开始/在途/暂停、相机保留、小窗和清理。成功标记为 `client/build/demo/opening-smoke-ok.txt`，截图在 `screenshots/opening-*.png`。演示预装满储量隔离交互，不改变实际出生规则。

UI 整体回归保护命名空间隔离、过期卡禁用、唯一分账和原始摄入与实际到货区分；人体异构门槛、严格边界与固定任务规则由 Human/Framework 验证。完整持续吐纳至成功开脉仍需 Windows 实机验收。
