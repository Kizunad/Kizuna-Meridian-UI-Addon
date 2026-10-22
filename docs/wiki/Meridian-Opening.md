# 经脉窗口与操作卡

状态：已实现独立经脉场景、点击选中高亮、快捷键、权威开始/暂停请求，以及普通扩散与开脉的真实在途展示。紧凑条形面板支持宿主显式打开及多个 Addon 注册操作。界面使用 XML + owo 和 Inventory UI 窗口 API，模型与规则由生物附属及宿主提供。

## 游戏内操作

安装匹配版本的两个框架、UI 和生物宿主后，按 `I` → 修炼 → 经脉。Human Fabric 自动提供人体模型、身体快照与可用开脉规则。

主窗口保持站立黑色半透明模型。未拓宽经脉为极淡黄色，已拓宽经脉为微亮黄色。拖动旋转、滚轮缩放、中键复位；选择和快照刷新保留用户视角。

## 开脉视听反馈

目标脉沿生物附属提供的真实网格路线定向流光。金色亮段、暗色未拓宽段和菱形前沿标记权威进度；进度不会按本地时间自行补满。维持期间随进度增强呼吸光。全身或局部占用距离任务维持阈值不足 8 个百分点时，以低亮琥珀色、断续线和轻闪预警，Human 默认对应低于 68% 接近 60% 线。

收到新 `OPENED` 状态后整脉与支路爆亮并落回常亮，播放一次柔和上行音；维持退回准备、暂停、损坏或耗竭则播放暗红退光和低沉下行音。连接级 `OpeningFeedback` 去重：多窗口、重复快照、关窗重开都不重复播音；首次同步旧存档不补播历史结果。OP 完成同样读取服务端状态变化。局部效果时间仅控制淡出，不参与身体资源或成功裁定。

同一叠层用于经脉窗口、检视勾选经脉层以及吐纳中的打坐模型。打坐路线按附属提供的骨骼权重变形，沿用真实肺部动画和粒子场景。人体路径、方向与预警策略留在 Human-UI；UI 用强制 `meridian_ui_opening_effects` / `OpeningEffectProvider` 接线，不增加 Framework 网络字段。

设置保存于 `config/meridian-ui-effects.properties`：`particle_limit=96`（允许 0～384，按每个画布合计开脉光点、运输光点及环境灵气粒子）、`reduced_motion=false`。经脉窗口效果按钮左键切换减少动态，右键切换 0/24/96/192 档。减少动态关闭流光、脉动、闪烁和动态关口收缩，保留静态进度边界、风险断续线及柔和结果定色；环境灵气保留实际移动，以便吐纳仍可操作。粒子上限不影响服务端粒子捕获或资源结算。线条、前沿和关口标记属于信息图形，不计作装饰粒子。

`opening.complete` 与 `opening.interrupted` 是本项目合成占位音效，通过 `python3 scripts/generate_opening_sounds.py` 可重建（需要 ffmpeg）；没有使用第三方音频样本。

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

开脉卡由内容附属按权威身体生成；Human 当前提供全身准备、目标经脉体积占用、持续摄入、器官/组织余力和连续适应。活动任务使用固定规则，UI 不解释策略 Any，也不重新判断人体门槛。Human 需要同时配合[吐纳窗口](Gathering.md)持续摄入。

## 真元展示口径

所有页面通过 `QiDisplay` 汇总同一身体快照，每笔资源只属于内腔、外壁、器官缓存/加工输入、在途之一。预约不额外增加总量。

真元只存属性、数量和密度，相态由密度区间推导。储量条按 `Σ(数量 / 密度) / 稳定体积` 绘制，在途虚线也使用货物的实际体积；不同密度的元数不能直接充当占用比例。数字形式的等效元容量使用当前库存平均密度换算，空账户使用游离态基准密度 `0.01`。器官加工读取 `process.input` 中已经扣离缓存的实际原料，不再按配方标称输入重复计量。

吐纳「纳入」读取 `BreathingView.stored_qi`：当前连接内，炼化产物实际抵达经脉后才累计。`absorbed_qi` 是炼化前灵气，单独放在悬浮说明中；Human 初始炼化效率为 20%。累计入脉不随消耗回减，也不含出生真元和管理员注入，因此与当前余额含义不同。身体推送同时刷新吐纳总账提示，避免独立窗口停留在旧余额。

`MeridianFlow` 展示真实 `MOVING` 记录中的游离态真元，包含器官出口、日常被动扩散和开脉，最多 96 条光迹。方向来自实际连接，按弧长插值。必需路线提供者给出中间点，UI 沿两端模块网格边衔接，每个模块用首个绑定网格；器官与经脉按类型和 ID 联合寻址。缺少实际连接、端点几何或端点尚未解锁时不绘制。身体检视没有静态模块间连线。

客户端通过 Fabric `meridian_ui_routes` entrypoint 强制装配唯一的 `dev.kizuna.meridian.ui.api.MeridianRouteProvider`。`waypoints(model, structure, connection)` 必须返回从源到目标的中间 `Point` 列表，UI 衔接源网格→中间点→目标网格；缺少接线、重复装配、空路线或无效路线直接报错。接口只在本机 Java 调用，不扩展 Framework Protobuf，不创建连接、在途或资源。

路线须为 2～256 个有限且位于模型包围盒内的点；UI 复制为只读列表并按连接缓存，构型变化后重新取路线。UI 源码仅依赖通用 Java 接口，运行时强制依赖 Human-UI；全部人体连接路线、周天坐标和相关表现由 Human-UI 直接使用人体 ID 与模型提供。

未开脉网格保持淡色底光，`opening.progress` 决定沿路线已亮起的长度；`OPENED` 使用完成亮度，局部储量只增加微弱底光。运输到达终点后等新快照，不循环制造货物；开脉装饰流光不代表运输账户。模型独立离屏渲染，文字与浮窗按正常 UI 顺序叠放。

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
./gradlew build :client:compileDemoJava
python3 "scripts/check_docs.py"
```

内观客户端 smoke 统一由同级 `Kizuna-Meridian-Human-UI-Addon` 提供：在该仓运行 `xvfb-run -a ./gradlew :client:runSmoke --offline --console=plain --no-watch-fs`。它强制装配 UI、Human 与 Human-UI，检查真实人体模型、人中交叉光尾及移除在途后的清理。完整构建顺序见[构建与依赖](Integration.md)。

UI 整体回归保护命名空间隔离、过期卡禁用、唯一分账、原始摄入与实际到货区分，以及开脉结果去重、重连历史基线和计时重置反馈；人体门槛与固定任务规则由 Human/Framework 验证。2026-10-20 已在 WN 独立单机存档通过正常吐纳、持续15秒成功开脉、三视图、预警、暂停及 OP 完成验收。具体入口和关键帧见 Human-UI 的 Integration 文档。
