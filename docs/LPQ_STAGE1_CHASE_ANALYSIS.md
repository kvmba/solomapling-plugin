# 玩具塔 101 组队任务：追怪停顿与击杀后原地走路

分析日期：2026-10-16。宿主：相邻仓库 `GMS083`。

## 结论边界

以下是从实际生产调用链确认的代码缺陷，不等同于已完成客户端实测。
本地 `GMS083/gms-server/logs/out.log` 只有旧启动日志，没有本次卡住时的运行记录。
原有移动模拟、补充的定向测试、编译通过，都不能替代带真实玩家的组队任务验收。

## 1. 同一只 bot 的追怪状态有两个并发写入者

第一关的两条路径：

- 宏任务：`BotTickService` → `LudiPQBot.updateState` → `workEntryRoom`
  → `LudiStages.gatherPasses` → `PqActions.seekAndAttack`。
- 战斗循环：`GrindTickRegistry.sweep` → `PartyQuestBot.grindTick`
  → `PqActions.seekAndAttack`，每 250ms 一拍。

宏任务的“不重入”只约束宏任务自身，不约束战斗线程。原实现把一个追怪过程拆在
多张 `ConcurrentHashMap` 中：单个 `get/put/remove` 安全，不代表整个换目标操作原子。
例如一拍读到旧的 `seekAnchorByBot` 后，另一拍杀怪换目标并清除 `seekBestDistByBot`，
第一拍随后执行 `mobDist < bestDist - PROGRESS_EPS_PX` 就会对 null 拆箱。
更一般的交错会把 A 怪的平台、B 怪的进度和旧移动命令混在一起。

修正：用一个按 bot 分配的 `SeekState` 保存追怪过程，整拍及释放使用同一个监视器；
换地图实例时清除目标与路径缓存。不在整个组队任务或所有 bot 上使用全局锁。

## 2. “我上次发过这个坐标”不等于驱动现在仍在去那里

原来是否重发移动的条件是：`!hasMoveTarget || 新目标偏离上次发出的目标`。
宏任务的拾取、驻点等逻辑可覆盖驱动目标，却不更新追怪自己的 `seekLastGoalByBot`。
结果是上层怪物还活着、驱动也有目标，但这个目标属于另一项任务；追怪因旧缓存而不重发。

修正：移除该缓存，由移动层在驱动监视器下直接核对当前目标与本拍目标。
目标未变时不刷新无进展计时，目标被覆盖或已经结束时才重新下发。
移动命令的目标、精度、进度计时和到达回调一起发布，避免与旧移动的到达清理交错。

## 3. 移动帧有两个并发写入者

`BotSM.updateState` → `MovementCommands.BotIdleStandingUpdate` 仍在动态控制的 LPQ bot 上运行。
旧刷新先捕获坐标、生成 200ms 站立片段，再经 `BotMove` → 宿主 `updatePositionBot`
回写角色坐标并广播。在这两步之间动态驱动可以已经前进或起跳；旧刷新随后写回旧位置，
其发包也不参与动态驱动的去重缓存。这是不应同时运行的两个移动引擎，不是步长问题。
现让旧刷新对动态控制角色直接返回，站立/警戒姿势完全由现有动态驱动处理。

攻击转向的 `GCMovement.face` 原先直接和移动线程竞争更新移动包去重缓存并发包。
现使用驱动同一监视器，避免发出坐标、速度、姿势属于不同帧的包。

## 已检查但不能归因的方向

`canPathTo` 与驱动对爬绳起点的解析方式不同，是一个需要审视的契约差异。
但本次使用真实塔图，对各绳索中点到各地面区域进行比较，旧查询与绳索起点查询的
可达性布尔值没有出现差异。因此已撤回对此函数的试验性修改，不能将它列为本次根因。

## 为什么原测试不足

- 多数执行模拟直接反射调用 `tick`，没有真实宏任务和战斗循环同时运行。
- `LpqLiveMobChaseRealSeekTest` 的角色不装备武器，不会真实杀怪；原有两例不包含死怪换目标。
- 模拟常直接写 `moveTarget`，不覆盖移动命令发布与到达清理的线程交错。
- 未找到宿主 WZ 时，该测试类会在 `BeforeAll` 中终止；Maven 仍可能报构建成功且执行 0 例。

新增定向用例只约束上述具体缺陷：被覆盖目标的恢复、未变目标不重置进度、
死怪转向上层怪、同 bot 追怪串行、旧站立刷新不改写动态角色。它们不是“实服 BUG 已消失”的证明。

本次已通过 SOCKS `127.0.0.1:31080` 编译相邻宿主并安装其 provided 依赖，再编译插件。
WZ 相关测试使用临时 POM 把 Surefire 的 **workingDirectory** 设为
`GMS083/gms-server`，不改仓库构建配置；仅设置 `wzpath` 或 `user.dir` 不足以保证
宿主的 WZ 读取实际进入该目录。

辅助验证范围：追怪定向用例 6 项、`LpqStage1ExecutionSimTest` 6 项、
`PqOrphanProbeTest` 10 项。另有锁顺序检查 `PqStageNudgeLockOrderTest` 3 项通过。

另外在临时目录编译 `HEAD` 的原实现、优先加载这三个旧类作对照，不覆盖工作区。
三个新增检查在旧实现上分别失败：

- 目标应恢复为 `(120,-713)`，实际仍为被覆盖后的 `(88,-450)`。
- 第一拍暂停在目标读取时，第二拍仍可进入同一追怪过程。
- 站立刷新确实经宿主 `AbstractMovementPacketHandler.updatePosition` 和
  `MapleMap.moveBot` 两次调用 `setPosition`；当前实现不再回写。

同三个检查在当前实现通过。这证明具体缺陷存在且被修正，仍不证明完整实服场景已验收。

## 实服取证与验收

保持原有导航图、物理参数、重试超时不变；本次不靠扩大容差、加传送或清缓存掩盖问题。
更新插件后，在第一关对出现问题的 bot 执行：

```text
!gcmove trace <botId> 60000
```

保留从“击杀前 → 怪物消失 → 选择下一只 → 上跳/爬绳或卡住”整段的 `[seek-trace]`，
以及同期 `Grind participant tick failed` / `GCMove tick failed` 异常日志。
trace 现在包含线程、怪物 OID、`reason=chase/timeout/no-target`、追怪目标、驱动真实目标、
导航边、拒绝原因、飞行/爬绳状态、速度、profile、预热状态和进度计时；记录本身不额外寻路。

- 若追怪 `goal` 与驱动 `move` 不一致，检查宏任务覆盖与任务优先级。
- 若目标一致但反复 `no-path`，检查具体起点、终点及图中边链。
- 若始终同一条边且 `block=jump-pos`，检查真实起跳像素、速度与有效窗口，不再改追怪超时。
- 若服务端坐标持续变化但客户端仍原地走路，需要对照 MOVE_PLAYER 发包时序进一步检查客户端表现。

验收必须在真实玩家在场、完整队伍、真实攻击和掉落下连续清理多个楼层，
并覆盖不同职业和击退后的继续攀爬。
