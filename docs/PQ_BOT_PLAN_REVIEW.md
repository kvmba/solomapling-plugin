# 组队任务（PQ）Bot 方案 · 复查与勘误（v2）

> 对 [PQ_BOT_PLAN.md](PQ_BOT_PLAN.md) 的**逐条复核**：哪些结论成立、哪些是错的、漏了什么。
> 所有新结论都已在本仓库源码 + host 引擎源码里坐实，附证据位置。
>
> 复核范围：`gms-server`（引擎/PQ 脚本/WZ）· `solomapling-plugin`（bot 实现）
>
> 另见：[PARTY_CONTENT_TAXONOMY.md](PARTY_CONTENT_TAXONOMY.md) ——
> 补全"组队内容类型"的覆盖缺口（v1 只梳理了 8 类中的 1 类）。
>
> 再另见：[PQ_BOT_IMPLEMENTATION.md](PQ_BOT_IMPLEMENTATION.md) ——
> 实施级细节。**其中"第七部分 · 给 v1/v2 的勘误回填"纠正了本文的多条结论**
> （最主要：祭坛要"一叠恰好 20"而非 20 个散落；祭坛 `type==100` 打它不推进）。

---

## 第一部分 · 上一版**错误**的结论（必须撤回）

### ❌ 错误 1：Orbis 第一关的控制流判错了

**上一版说**：Stage 1 完成 = 打 20 个云 → 把云碎片丢在 `(497,143)` → orchestrator 观察 NPC 2013001 出现。

**实际机制**（证据：`wz/Map.wz/Map/Map9/920010000.img.xml` + `wz/Reactor.wz/2006000.img.xml` + `scripts/reactor/2006000.js`）：

```
① 20 个云反应堆（dataId 2002001）打到 state 4
   → 2002001.js act() → rm.dropItems() → 掉 4001063 云碎片
② 把 20 个 4001063 丢到「祭坛反应堆」eak（dataId 2006000，坐标 377,66）
   —— 触发器：event type=100，0=4001063，1=20，面积 lt(-100,-100)/rb(100,100)
③ 引擎 activateItemReactors() → 5 秒后 ActivateItemReactor.run()
   → reactor.hitReactor(c) → 2006000.js act() → rm.spawnNpc(2013001)
④ 队长点 2013001 → statusStg0=1 + warpEventTeamToMapSpawnPoint(920010000, 2)
```

**两个必须记住的坐标/数值**：

| 量 | 值 | 出处 |
|---|---|---|
| 祭坛反应堆 eak | dataId `2006000`，坐标 `(377, 66)` | Map9/920010000.img.xml |
| 祭坛判定面积 | `x∈[277,477)` `y∈[-34,166)`（lt/rb 相对 reactor 位置） | Reactor.wz/2006000.img.xml |
| 祭坛触发条件 | `4001063` × **20** | 同上 `event/0` |
| 云反应堆 | dataId `2002001`，共 **20** 个 | Map9/920010000.img.xml |
| 掉落物 | `4001063`（云碎片），reactordrops chance=1 | V1.0.58__reactordrops_insert_data.sql:706 |

**现有 bot 的三处偏差**（都是真 bug）：

1. **丢物品的位置错了**。`OPQBot.handleStage1DropItems()` 把云丢在 `getChr().getPosition()`，而 `handleStage1Return()` 走到的点是 **`(497,143)`**。
   祭坛面积是 `x∈[277,477)`，**497 已经出界 20px** → `activateItemReactors` 的 `react.getArea().contains(drop.getPosition())` **永远不成立** → 祭坛永不触发 → **NPC 永不出现** → Stage 1 永远无法通关。
2. **靠"NPC 出现"当通关信号本身没错**（NPC 确实会出现），但它是**祭坛触发的副作用**，不是"丢完云就自动出现"。上一版说"orchestrator 靠观察 NPC 猜"——这个描述是错的，因为**根本到不了那一步**。
3. `STAGE_1_ALTAR_REACTOR_ID = 2006000` 常量**定义了但从未使用**（全仓仅此一处声明）。说明原作者的意图正确（知道有祭坛），但没接线。

### ❌ 错误 2：说"Stage 2 完成靠 bot 自报"——不完整

**实际**：Stage 2 的完成是真·双条件：
- 每张唱片（`4001056`-`4001062`）丢进**音乐盒反应堆**（dataId `2008006`，坐标 `(-1706,-240)`，面积 `x∈[-1758,-1666)` `y∈[-304,-161)`）；
- 正确的那张（按**星期几**决定，`OrbisPQ.js:163`：`setEventState(d.getDay())`）在 state 7 后触发 `2008006.js` → `setProperty("statusStg3","0")`。

而 bot 的 `handleStage2Return()` 走到 **`(-1588,-127)`** —— `x=-1588` 不在 `[-1758,-1666)`，`y=-127` 不在 `[-304,-161)`。**又一次出界**。

> ⚠️ 注意：`(-1588,-127)` 附近地面确实存在（foothold #20 x∈[-1665,-1575] y=-127），说明这是"站在音乐盒正前方的地面上"，但**不在反应堆的触发框里**。丢在这里的唱片永远不会被吞。

### ❌ 错误 3：把 `followLeaderWarp` 说成"与 EIM 进本重复冲突"——部分错

- EIM 进本（`registerParty → playerEntry → changeMap`）**确实存在且可用**；
- 但现有 OPQ 走 `followLeaderWarp` **不是纯冗余**：`OPQ_STAGE_1 = 920010000` 是 `entryMap`，EIM 进本只负责把人送进 **920010000**；
- **`920010000 → 920010100 → 920010400`（塔、Stage 2）的推进是"由队长和 NPC 对话触发"的**（`2013001.js`：`eim.warpEventTeam(920010800)` / `warpEventTeamToMapSpawnPoint(920010000,2)`），bot 不在这些 `warpEventTeam` 的玩家列表里？——**在**，但它们在 **实例地图**里，只有 `changeMap` 才认得 `getEventInstance()`。

结论修正：`followLeaderWarp` **对"EIM 不搬的部分"是必要的**；错的是它的**实现方式**（`warpBotToLocation → setMap` 旁路，见错误 4）。正确做法是让 bot 走 `changeMap`，这样 EIM 的 `changedMap` 钩子、party 快照、HP 同步全都自然生效。

---

## 第二部分 · 上一版**遗漏**的问题（新发现，全部已验证）

### 🔴 遗漏 1：`warpBotToLocation` 会污染 party 的地图快照 → 开不了本

**链路**：

```
PartyCharacter.mapid 只在两处刷新：
  (a) new PartyCharacter(chr) —— 入队那一刻的快照
  (b) Character.changeMapInternal() → mpc.setMapId(to.getId())
      （Character.java:1778；且同处发的 updateParty(..., SILENT_UPDATE, null) 的 target 是 null，
        所以不会走 World.updateParty → party.updateMember）

bot 的 warpBotToLocation → placeBotOnMap → fakechar.setMap(map)
  —— 绕开 changeMapInternal → (b) 不执行
```

**后果**：`getEligibleParty()` 里 `ch.getMapId() == recruitMap` 用的是**陈旧快照**。

真值表：

| 场景 | party 快照 mapid | `eligible` |
|---|---|---|
| bot 就在大厅被邀请入队（`joinParty` 建新快照） | = 大厅 ✓ | ✓ |
| bot 入队后经 `warpBotToLocation` 换图 | **不更新** ✗ | ✗ |
| bot 入队时不在大厅，事后才被搬过去 | 旧地图 ✗ | ✗ |

**现有实现为何侥幸能用**：`spawnOPQBotsInLobby()` 先在大厅生成、再入队，所以快照恰好等于 `recruitMap`。
**但它很脆**：任何"先组队、后移动"的流程（比如玩家在别的图先邀请 bot、再走回大厅；或多 PQ 泛化后）都会**开不了本**，而错误表现为玩家侧"你不能开始这个 PQ"——极难排查。

**修法**：bot 的换图一律走 `chr.changeMap(...)`（已有 `GCPortals` 这么做），或提供一个 `EimAccess.moveToInstance(chr, map)` 封装 `changeMap`。**不要**继续用 `warpBotToLocation` 做 PQ 内移动。

### 🔴 遗漏 2：`OPQBot` 打反应堆不触发脚本 → 掉落全靠手工补

`CustomReactor.hitReactor()` 只做 `state++` + 广播包，**不调用 `ReactorScriptManager.act()`**。
所以 `2002001.js`（`rm.dropItems()`）**不会执行** —— 这与观察一致（OPQBot 手工调 `dropItemAtReactor` 补掉落）。

**但这在泛化后是定时炸弹**：
- 不是所有 PQ 的反应堆都能"手工补掉落"（比如 LPQ 的箱子掉 `4001022`，Pirate 的箱子在 `grindMode` 下判定 `activatedAllReactorsOnMap`）；
- 更重要的是：`ActivateItemReactor` 依赖 `reactor.hitReactor(c)` → **`act()` 才会跑**（`2006000.js` 的 `spawnNpc` 就在 `act()` 里）。如果通用版继续用 `CustomReactor.hitReactor` 打祭坛，`act()` 永不触发 → NPC 永不出现。

**修法**：给 `CustomReactor` 加一个走真脚本的版本：绑定 `BotClientBinding` → 构造 `ReactorActionManager` → `ReactorScriptManager.act(client, reactor)`，或直接复刻 host 的 `hitReactor` 但把 `c.getPlayer()` 换成绑定后的 bot。**祭坛必须走真脚本**。

### 🟠 遗漏 3：`unregisterBot` 是死代码 → bot 终身泄漏在 orchestrator 里

`OPQOrchestrator.unregisterBot()` **零调用者**（全仓 grep 确认）。`registerBot` 在 `OPQBot` 构造里调用。

后果：
- 被 convert / 删除 / 掉线的 bot 永久留在 `registeredBots`（`CopyOnWriteArrayList`）；
- `allBotsLostParty()` 遍历时，僵尸 bot 的 `getParty()` 若为 null 尚可，但 `allRegisteredBotsComplete()` 会因僵尸 bot 的 `isMyTaskComplete` 恒 false 而**永远返回 false** → Stage 2 判完成失效；
- `resolveLeader()` 取 `registeredBots.get(0)` 作锚点 —— 若 **0 号是僵尸**（已换图/已下线），`getClient().getChannelServer()...getCharacterById()` 会查错频道甚至 NPE；
- 内存上，`OPQBot` 强引用 `Character`，**bot 永远无法 GC**。

**修法**：在 `BotSM.stopScheduledTask()` / `removeBotFromServer()` / `convertBotType()` 三条出口都调 `orchestrator.unregisterBot(this)`，或在 orchestrator 的 tick 里剔除 `!bot.getRunning()` 的条目。

### 🟠 遗漏 4：`isChamberlainSpawned()` 的 NPE 比上一版描述的更宽

```java
public boolean isChamberlainSpawned() {
    return isNpcPresent(resolveLeader().getMap(), CHAMBERLAIN_EAK);
}
```
`resolveLeader()` 在 `leaderId <= 0 || registeredBots.isEmpty()` 时返回 **null**；取 `.getMap()` 直接 NPE。
更隐蔽的是：`getMap()` 可能非 null 但**不是实例地图**（若 leader 用别的途径换了图），此时 `isNpcPresent` 在错的地图上找 NPC → 恒 false → Stage 1 卡死直到超时。

**同类问题**：`OPQBot.getPartyLeader()` 直接 `getChr().getParty().getLeader().getPlayer()`，三层解引用全无保护，**party 一解散就 NPE**。`handleStage1Wait/handleStage1Transition/leaderLeftStage2/followLeaderOut` 共 6+ 处调用。

### 🟠 遗漏 5：`OPQBot.processMessages()` 是死代码

`BotSM.tickRunnable` 只调 `updateState()`；`BotSM.processMessages()` 是空方法（还带一句 `System.out.println`）。
`OPQBot` override 了 `processMessages()` 并**阻塞 1 秒**取消息队列（`getMessageWithTimeout("secondary", 1, SECONDS)`），但**从未被调用**。删掉即可，不要以为是活跃代码。

### 🟠 遗漏 6：`debugLogf` 硬编码关闭 → PQ 全流程无日志

```java
private void debugLogf(String msg) {
    boolean opqDebug = false;   // ← 硬编码
    if(!opqDebug) return;
```
于是 `handleStage1Navigate` / `handleStage1Loot` / `handleStage2HitBox` … **所有诊断日志全是空操作**。
`transitionTo()` 里还有一条 `log(...)`（走 `BotLogger`），所以只能看到状态切换，看不到**为什么**切换。
泛化后必须给出**可开关的、按 PQ/按 stage 分级的**日志，否则线上排障无门。

### 🟡 遗漏 7：`OPQBot` 剩了一堆"规划过但没接"的接口

| 符号 | 状态 |
|---|---|
| `STAGE_1_ALTAR_REACTOR_ID = 2006000` | 定义未用（本应指向祭坛） |
| `assignPlatformTarget()` | **零调用**（Stage 2 曾打算按平台分配，后改为按反应堆分配） |
| `getMusicBoxPosition()` | **零调用**（Stage 2 的落点应基于它算，实际是硬编码 `-1588,-127`） |
| `STAGE_2_BOX_PLATFORMS` / `getMyPlatformAssignment` | 仅被 `assignPlatformTarget`（死代码）和 `allAssignedBotsComplete`（自己也是死代码，零调用）使用 |
| `resetForNewRun()` / `shutdownRun()` | 零调用 |
| `allAssignedBotsComplete(StagePhase)` | 零调用 |

**含义**：`OPQOrchestrator` 实际只有一半在跑。"平台分配"整套机制是半成品——这也解释了为什么 Stage 2 的落点是拍脑袋的硬编码坐标。

### 🟡 遗漏 8：`OPQConstants` 的坐标注释与 WZ 实际不符

`STAGE_1_ENTRY_TP = new Point(266, 143)`：数值本身在 WZ 里**确实存在**（portal `sp` 在 `(266,96)`、foothold 在 y=143），所以这个点没错。
但 `STAGE_1_COMPLETE_TP = (165,-1270)` 对不上 `2013001.js` 的真实逻辑——脚本用的是 `warpEventTeamToMapSpawnPoint(920010000, 2)`（传**传送门 id=2**，不是坐标），而 portal 2 是 `(487,92)`。**用坐标硬编码去猜"传送门 2 的位置"是脆的**。
`REACTOR_HIT_RANGE_PX = 200`：对 `2002001`（宽 106px）够用，但对更宽的反应堆（Magatia 的大型装置）会误判。

### 🟡 遗漏 9：`OPQBot` 的爆炸半径比记录的小，但耦合更脏

`codegraph` 的 blast radius 显示 `OPQBot` 被 6 处引用，但其中：
- `BotTypeManager.OPQ_BOT` 枚举（正常入口）
- `EnvironmentManager.spawnOPQBotsInLobby`（正常入口）
- `ArtificialPlayerCommand:280`（GM 命令）
- 其余是**同包内的 orchestrator/context**。

**没有外部系统依赖它** → 替换成本比预期低。**但反过来**：它依赖 `CustomReactor`（唯一调用方）、`BotTiming.chain`、`BotPartyLogic`、`EnvironmentManager`（平台）、`Wz` 坐标…… **要泛化就不能只搬 `OPQBot` 一个文件**，得把 `CustomReactor` 的"走脚本"能力一并补上（见遗漏 2）。

### 🟡 遗漏 10：`BotType.OPQ_BOT` 与 `getEligibleParty` 的等级门槛有边界不一致

`EnvironmentManager.setBotsLevelRange(allBotIds, 50, 70)` 生成的是 **50–70**，
而 `OrbisPQ.js` 的 `minLevel = 51`。**level 50 的 bot 进不了本**，但它的 `recruitMap` 判断会通过（`ch.getMapId()==recruitMap` ✓）、`getLevel()` 判断失败 ✗ → 它会被静默排除在 `eligible` 之外。

**后果**：如果队伍里真人 + level50 bot 恰好凑够 minPlayers=5，但 bot 没进本，**玩家侧表现为"启动成功但队友没进来"**。
修法：spec 里 `botLevelRange` 必须**内缩**于 host 的 `[minLevel,maxLevel]`，或干脆取中段。

---

## 第三部分 · 上一版**正确**的部分（保留）

| # | 结论 | 复核 |
|:--:|---|---|
| 1 | `OPQBot` 是单体硬编码（1012 行 / 20 状态），换 PQ 要重写 | ✅ |
| 2 | 应抽成 `PQSpec`（数据）+ `Task`（原语）+ `Orchestrator`（黑板） | ✅ 架构方向对 |
| 3 | 没有 NPC 交互能力（全插件零 `NPCScriptManager`） | ✅ 全仓 grep 确认 |
| 4 | 应读 EIM 属性判通关（`statusStgN` / `Nstageclear`） | ✅ `EventInstanceManager.getProperty/getIntProperty` 是 public |
| 5 | P1–P12 原语提取（爬绳/站台/打怪/破反应堆/交道具/组合谜题） | ✅ 与 NPC 脚本逐条对得上 |
| 6 | bot 绝不能当队长；卡点人数必须精确；掉落归属要补 | ✅ |
| 7 | `changePath` 的 `createPath` 对未录制地图返回空路由 | ✅ `PathFinder.createPath` 已处理（返回 emptyList 而非抛异常） |
| 8 | 反应堆要打前 guard `state>=4` | ✅（且 host 的 `hitReactor` 本身有 `hitLock`） |

---

## 第四部分 · 修正后的落地顺序（v2）

> 原则不变（最小改动 / 先跑通一个再铺开），但**先修 3 个致命 bug，再谈架构**。

### 阶段 0 · 先让现有 Orbis 能通关（不重构）
1. **修丢物品坐标**：`handleStage1Return` 的落点改为祭坛面积内（如 `(377, 143)` 附近的 foothold 上），`handleStage2Return` 改为音乐盒面积内。
2. **修祭坛触发**：让 bot 走**真脚本路径**打祭坛（`ReactorScriptManager.act`），或直接 `spawnNpc` 兜底——但**推荐前者**，因为 `2006000.js` 还有 `mapMessage`。
3. **修 `unregisterBot` 调用**：三条出口都接上。
4. **修 `getPartyLeader()` / `resolveLeader()` 的 NPE**。
5. **打开 `debugLogf`**（默认 debug 可开关）。
   - **验收**：真玩家 + 4 个 OPQ bot 能通关 Orbis 全流程。

### 阶段 A · 抽引擎（行为对齐后再删旧代码）
6. `PartyQuestBot` + `PQOrchestrator` + `PQSharedContext` + `PQSpec`（含 `OrbisPQSpec`，坐标**从 WZ 读**而不是硬编码）。
7. 通用 `PqTask`：先只实现 Orbis 用到的 `Navigate / HitReactor / Loot / DropAt / TalkNpc`。
8. **换图统一走 `changeMap`**（新 `EimAccess.moveToInstance`），废弃 PQ 内的 `warpBotToLocation`。
   - **验收**：与阶段 0 行为一致，且 `party.mapid` 快照正确。

### 阶段 B · 补能力
9. `NPCInteraction`（绑定 client + 逐屏推进 + 节流 `canClickNPC` 500ms）。
10. `EimAccess`（`stageCleared` / `intProp` / `moveToInstance`）。
    - **验收**：能对 NPC 交道具；Stage 判定改读 EIM 属性。

### 阶段 C · 经典四连
11. Henesys / Kerning / Ludi 三份 spec（重点：KPQ 的 `Rectangle` 站位、LPQ 的 9 关混合）。
    - **验收**：真人 + N bot 通关。

### 阶段 D · 其它家族
12. Magatia / Ellin / Pirate → Amoria / Guild / CWK / Cafe。

---

## 第五部分 · 修正后的坑点清单

### 🔴 致命
1. **祭坛/音乐盒的触发面积**：一定是 `reactor.getPosition() + lt/rb`，不是"反应堆脚下的地面"。**落点必须在框内**（KPQ 的 `Rectangle`、Orbis 的两处都是）。
2. **`warpBotToLocation` 污染 party 快照** → `eligible=[]` → 开不了本。PQ 内一律 `changeMap`。
3. **`CustomReactor.hitReactor` 不走脚本** → `act()` 不执行 → 反应堆的掉宝/NPC 生成/`statusStg` 全都不会发生。
4. **bot 不是 EIM 成员**：必须在 `recruitMap` 上、`eligible` 命中，且**宿主放行**——
   `registerPlayer` 的 `isLoggedInWorld()` 对模板克隆 bot 恒为 false（`Character.loggedIn`
   在 `loadCharFromDB(...,false)` 的早退里不会被置位；`markPresentInWorld()` 只管
   `awayFromWorld`）。已由宿主 `EventInstanceManager` 按 `HostHooks.isArtificial` 放行修复，
   见 `docs/PARTY_QUEST_ALL_PLAN.md` 第十部分第 3 条。
5. **bot 当了队长** → KPQ 等 `isEventLeader` 分支全错。
6. **`changeMap` 里 `getChannelServer().getPlayerStorage().getCharacterById(id) == null` 时会 `client.disconnect(true,false)`**（Character.java:1791）。
   模板 bot **不在 channel storage 的常规索引里**时，这一步会触发**共享 BotClient 的 disconnect** → 掉线/`saveCharToDB`/清缓存。
   `BotGeneration.addBotToServer` 已把 bot 塞进 channel storage，但**要确认在 `changeMap` 前**；companion 走的是私有 client 路径，风险不同。

### 🟠 行为错误
7. **`unregisterBot` 泄漏** → 完成判定失效 + `resolveLeader` 锚点漂移 + 内存泄漏。
8. **NPE**：`getPartyLeader()`（6+ 处）、`resolveLeader().getMap()`。
9. **等级门槛**：bot 等级区间必须内缩于 host 的 `[minLevel, maxLevel]`（如 51–70 而非 50–70）。
10. **掉落归属**：clientless bot 的 drop 要 `CustomReactor.dropItemAtReactor` / `spawnItemDrop(owner=bot)`，且 bot 捡拾走 `BotClientBinding`。
11. **`getEligibleParty` 额外条件**：EllinPQ 要冒险家（`job/1000==0`）、GuildQuest 要比 guildId、CWKPQ 要 6 职业。
12. **反应堆 `state>=4` 越界崩溃**：打前 guard 必须保留。

### 🟡 细节
13. `canClickNPC` 500ms 节流；NPC 交互要独占 client monitor。
14. **死亡**：PQ 内 bot 被打死会被 `BotDeath.carryHome` 搬回城 → 破坏 PQ，需要 PQ 态豁免。
15. **超时**：每个 Task 都要有超时 → STUCK → 降级；每关的总超时对齐 host 的 `eventTime`。
16. **`OPQConstants` 的坐标**：`(266,143)`、`(497,143)`、`(-1588,-127)` 等要改成**从 WZ/脚本常量推导**，不要复刻魔法数。
17. **i18n**：`opq.*` 已有 zh-CN 条目；新 spec 的喊话继续走 `BotMessages`。

---

## 附录 · 本次复查新坐实的关键事实速查

| 事实 | 证据位置 |
|---|---|
| 祭坛 eak = `2006000` @ `(377,66)`，触发 `4001063`×20，面积 `±100` | `WZ Map9/920010000.img.xml`、`Reactor.wz/2006000.img.xml` |
| 祭坛命中后 `spawnNpc(2013001)` | `scripts/reactor/2006000.js` |
| 云反应堆 = `2002001`，共 20 个，掉 `4001063` | `Map9/920010000.img.xml`、`V1.0.58__reactordrops_insert_data.sql:706` |
| 音乐盒 = `2008006` @ `(-1706,-240)`，面积 `x∈[-1758,-1666) y∈[-304,-161)`，按星期几定唱片 | `Reactor.wz/2008006.img.xml`、`OrbisPQ.js:163` |
| 音乐盒正确唱片 → `statusStg3 = 0` | `scripts/reactor/2008006.js` |
| Stage 0 完成 → `statusStg0=1` + `warpEventTeamToMapSpawnPoint(920010000,2)` | `scripts/npc/2013001.js` |
| `PartyCharacter.mapid` 只在入队快照 + `changeMapInternal` 更新 | `PartyCharacter.java:38`、`Character.java:1778` |
| `warpBotToLocation` 用 `setMap` 旁路 `changeMapInternal` | `BotGeneration.placeBotOnMap` |
| `CustomReactor.hitReactor` 不调 `ReactorScriptManager.act` | `MapVFX/CustomReactor.java:96` |
| `unregisterBot` 零调用 | 全仓 grep |
| `debugLogf` 硬编码 `false` | `OPQBot.java:1005` |
| `assignPlatformTarget` / `getMusicBoxPosition` / `resetForNewRun` / `shutdownRun` / `allAssignedBotsComplete` 均零调用 | 全仓 grep |
| `OPQBot.processMessages` 从未被调用（`BotSM.tickRunnable` 只调 `updateState`） | `BotSM.java:80-114` |
| `changeMap` 在 channel storage 查不到角色时会 `client.disconnect` | `Character.java:1791` |
| `ActivateItemReactor` 要求 `shouldCollect==true`；`MapFactory.loadReactor` 调 `resetReactorActions(0)` 置 true | `MapleMap.java:3965-4010`、`MapFactory.java:378` |
| 反应堆 item-trigger 走 `activateItemReactors → 5s → ActivateItemReactor.run → hitReactor` | `MapleMap.java:2511`、`3953` |
