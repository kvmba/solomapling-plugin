# 组队任务（PQ）专用 Bot：通用化重构与完整实施方案

> 一份文档说清：现有 PQ bot 为什么不够、所有组队任务的通用模式是什么、怎么让一个专用 bot
> 完美陪玩家跑完全部 PQ。
>
> 现状代码：`soloMapling/ArtificialPlayer/BotTypes/OPQ/**`（仅 Orbis PQ，硬编码 FSM）
> 参考文档：[HOST_BOUNDARY.md](HOST_BOUNDARY.md) · [BOT_TRANSIT_MASTER_PLAN.md](BOT_TRANSIT_MASTER_PLAN.md)
>
> ⚠️ **本文已被 [PQ_BOT_PLAN_REVIEW.md](PQ_BOT_PLAN_REVIEW.md) 复查并勘误，且被
> [PARTY_CONTENT_TAXONOMY.md](PARTY_CONTENT_TAXONOMY.md) 补全了类型覆盖、
> [PQ_BOT_IMPLEMENTATION.md](PQ_BOT_IMPLEMENTATION.md) 补齐了机制细节。**
> 若四份冲突，以 **IMPLEMENTATION > TAXONOMY > REVIEW > 本文** 为准。被推翻的关键点：
> - **类型覆盖**：本文只覆盖了 8 类组队内容里的 1 类（event 脚本型 PQ）。
>   另 7 类（Monster Carnival、Ariant Coliseum、Pyramid、Expedition Boss、Mu Lung Dojo、
>   Guild Quest…）**入口机制完全不同**，见 TAXONOMY。
> - **Orbis Stage 1/2 的真实完成机制**（是"把物品丢进反应堆触发框"，不是"观察 NPC/位置猜测"）；
> - **P6 / `HitReactorTask` 的"打 4 下"只对普通反应堆成立** ——
>   祭坛/音乐盒是 `type==100`（物品触发），**打它完全不推进状态**，必须直接调 `act()`，见 IMPLEMENTATION §修复 1；
> - **丢物品必须是"一叠恰好 N 个"**（`getQuantity()==N`），不是"N 个散落"，见 IMPLEMENTATION §发现 1；
> - `followLeaderWarp` 的定位、§2.2 里 P2/P6 的动作描述。
>
> **动手前先读 IMPLEMENTATION（实施级细节）与 REVIEW（必须撤回的结论）。**

---

## 第一部分 · 现状与问题

### 1.1 现有实现盘点

| 件 | 文件 | 行数 | 职责 |
|---|---|---:|---|
| `OPQBot` | `BotTypes/OPQ/OPQBot.java` | 1012 | Orbis PQ 的**单体硬编码状态机**（20 个 `OPQBotState`） |
| `OPQOrchestrator` | `BotTypes/OPQ/OPQOrchestrator.java` | 532 | 运行协调器 + tick + 反应堆分配 + 通关判定 |
| `OPQSharedContext` | `BotTypes/OPQ/OPQSharedContext.java` | 123 | 黑板（phase / 分配 / 任务完成位） |
| `OPQConstants` | `BotTypes/OPQ/OPQConstants.java` | 116 | 全部魔法数：地图/物品/反应堆/坐标 |
| `OPQRecruitMessages` | `BotTypes/OPQ/OPQRecruitMessages.java` | 65 | 大厅喊话 |

### 1.2 具体问题（都是"只服务一个 PQ、且脆弱"）

1. **单体硬编码**：地图 id、物品 id、反应堆 dataId、**坐标全部写死在 `OPQConstants` 与 `OPQBot` 方法体里**（`new Point(497,143)`、`Point(-1588,-127)`、`STAGE_1_ENTRY_TP`…）。换一个 PQ 得复制 1000 行。
2. **靠"跟随队长"猜进本**：注释自述"bots have no real client, so the OPQ NPC script does not warp them"。于是 `OPQOrchestrator.followLeaderWarp` 用 `warpBotToLocation` 手动搬运。这跟 EIM 的真实进本机制（`registerParty → playerEntry → changeMap`）**重复且冲突**，也解释了 `createPath` 注释里的"OPQ portal-placement freeze"。
3. **通关判定靠猜**：Stage 1 完成 = `isChamberlainSpawned()`（NPC 出现）；Stage 2 完成 = 所有 bot 上报 done。都不读取 EIM 的真实 `statusStgN` / `stageclear` 属性。
4. **NPE 隐患**：`isChamberlainSpawned()` → `resolveLeader().getMap()`，`resolveLeader()` 可返回 `null`（leaderId≤0 / bot 全部退出）；`getPartyLeader()` 在 party 解散瞬间也可能拿到 null。
5. **只有"跟随型"角色**：假设队长=真人玩家、bot 只做辅助。**没覆盖"多 bot 填满人数槽 / bot 承担特定角色"** 的 PQ（KPQ 要求 3 人分站绳/台）。
6. **没有 NPC 交互能力**：整个插件找不到 `NPCScriptManager` / `npcTalk` 调用。而 **所有 PQ 的卡点判定都在 NPC 脚本里**（交道具、答题换 pass、查组合）。
7. **没有关卡元数据**：`OPQBotDialogue.yaml` 注释 `// TODO: add YAML or fall back gracefully`，实际不存在（缺 i18n 节点）。

### 1.3 结论

> **现有 OPQ bot 不能"扩展"，只能"替换成引擎"。** 保留它的三个正确设计（黑板 + 反应堆分配 + 感知驱动），
> 把硬编码抽成**每 PQ 一份数据规格（PQSpec）**，把重复动作抽成**通用任务原语（Task）**，
> 并补齐它缺的两块能力：**EIM 一等公民进本** 与 **NPC 交互**。

---

## 第二部分 · 所有组队任务的通用模式（提取结果）

### 2.1 全部 PQ 清单（`scripts/event/*.js`，`isPq=true` 共 44 个）

| 家族 | 事件脚本 | 人数 | 关卡 |
|---|---|---|---|
| **Henesys** | HenesysPQ | 3–6 | 种种子组合 + 保护月兔 |
| **Kerning** | KerningPQ | 3–4 | 答题换 pass → 站绳组合 → 站台组合 → 站桶组合 → 打怪集 pass |
| **Ludi** | LudiPQ | 5–6 | 9 关：捡 pass / 破箱 / 打怪 / 组合 / 破反应堆 / Boss |
| **LudiMaze** | LudiMazePQ | 3–6 | 迷宫里按顺序通关 |
| **Orbis** | OrbisPQ | 5–6 | 云碎块(反应堆) → 塔 → 唱片(反应堆) → 音乐盒 → Boss |
| **Magatia** | MagatiaPQ_A / _Z | 4 | 反应堆 + 打怪 + 谜题组合 + Frankenroid |
| **Ellin** | EllinPQ | 4–6 | 清怪 → 毒傀儡 Boss |
| **Pirate** | PiratePQ | 3–6 | 打怪 / 破反应堆(grindMode) / Lord Pirate |
| **Amoria** | AmoriaPQ | 6 | 结婚主题多谜题 |
| **Guild / CWK** | GuildQuest / CWKPQ | 6–30 | 大型多关卡 + 职业分工 |
| **Cafe / 节日 / BossRush / 其它** | CafePQ_*, HolidayPQ_*, BossRushPQ, TreasurePQ… | 1–6 | 多为打怪/Boss，部分单人可过 |

> 本方案优先落地**经典低阶四连**（Henesys → Kerning → Ludi → Orbis），因为它们是 bot 段位（10–70 级）
> 覆盖的、玩家最常跑、机制最全的四个；引擎设计成可直接吃下其余家族。

### 2.2 通用模式（从 NPC/事件脚本里抽出的**判定原语**）

| # | 模式 | 真实判定（host 脚本） | 覆盖 PQ |
|:--:|---|---|---|
| P1 | **招募/入队** | `InviteCoordinator` + `PartyInviteEvent` | 全部 |
| P2 | **进本** | `EventManager.startInstance` → `registerParty` → `playerEntry`→`changeMap` | 全部 |
| P3 | **走位/爬绳** | `Rectangle.contains(pos)` 竖条 / 绳中点 | KPQ st2, Orbis st4 |
| P4 | **站台/站箱** | `Rectangle.contains(pos)` 横台 / 组合计数 | KPQ st3/st4, LPQ st8, Orbis st4 |
| P5 | **打怪收集** | `monsterKilled` + `haveItem(id,n)` | KPQ st5, LPQ, Orbis st1/st5, Ellin, Pirate, Magatia |
| P6 | **破反应堆** | `Reactor.state→4` + `act()` 脚本触发掉落/NPC/** | LPQ st2/4/7, Orbis st1/st2, Pirate st5 |
| P7 | **交道具给 NPC** | 队长点击 + `haveItem` 扣减 | KPQ st1, LPQ 每关, Orbis st1/st5, Magatia |
| P8 | **组合谜题** | 位置/拉杆组合，错误 `showWrongEffect` | KPQ st2-4, Orbis st4/st6, LPQ st8 |
| P9 | **保护 NPC** | `friendlyDamaged` / mob 计数 | HenesysPQ |
| P10 | **击杀/Boss** | `monsterKilled` → `showClearEffect` | Ellin, Pirate, Magatia, 各 Boss |
| P11 | **卡点/人数** | "exactly 3 members on these platforms" | 全部含组队的关 |
| P12 | **通关/离场** | `clearPQ` / 队长离开 → `changedLeader` | 全部 |

**关键观察**：P3/P4 是同一件事——**"把 N 个 bot 摆到指定矩形区域里"**；P5/P6 都收敛到
**"在目标点做动作 → 地上出现物品 → 捡起来"**。所以原语可以做得很薄。

> ⚠️ **P6 必须补一句**：反应堆的掉落/NPC 生成/`statusStg` 置位**全部发生在 `act()` 脚本里**。
> 而插件的 `CustomReactor.hitReactor` **不执行脚本**——直接用它打反应堆等于"只放动画、不产生任何效果"。
> 泛化时这个原语必须以**真脚本路径**为默认实现（见 REVIEW 遗漏 2）。

### 2.3 每种模式 Bot 需要执行的"动作"

| 模式 | Bot 动作序列 |
|---|---|
| P1 | `BotPartyLogic.checkPartyQueue()` 自动接受；非本人邀请则拒 |
| P2 | 待在 `recruitMap` → 被 EIM 注册 → `playerEntry` 自动 `changeMap` |
| P3 | `pathFinderBeta` 到绳 x → 上/下绳到目标 y（`GCMovement`/绳碰撞）→ 定住 |
| P4 | `pathFinderBeta` 到区域中心点 → `waitFor` 定住 |
| P5 | `BotAttackDriver.attack` 打最近的怪 → `DropCommands.lootItemListOnFloor` 捡指定物品 → 循环到数量达标 |
| P6 | `pathFinderBetaAerial` 到反应堆 → **走真脚本的 hit** ×4 → 捡（**不是** `CustomReactor.hitReactor`）。⚠️ **仅适用于 `type!=100` 的普通反应堆**；`type==100`（祭坛/音乐盒）**打它不推进**，须直接调 `act()`，见 IMPLEMENTATION §修复 1 |
| P7 | 收集达标 → `NPCInteraction.talk(npcId)`（绑定 client + 走脚本 `action` 序列）→ 交道具 |
| P8 | 按 `PQSpec` 给的区域/拉杆组合 → 站位/拉杆 → 失败重试（读 `showWrongEffect` 无法读，用超时+换组合） |
| P9 | 打兔子周围的怪（`MapMobIndex` 找最近 mob） |
| P10 | `BotAttackDriver` 打 Boss → 捡 drop |
| P11 | 由 Orchestrator 分配唯一不重复的位置/反应堆/箱；bot 只执行自己那份 |
| P12 | 跟随队长 `GCMovement.follow` 或 EIM `warpEventTeam` 带走 |

---

## 第三部分 · 目标架构

### 3.1 一句话设计

> **把 `OPQBot` 泛化成 `PartyQuestBot` + `PQOrchestrator`，把每个 PQ 硬编码抽成一份
> `PQSpec`（数据），把 P1–P12 抽成可组合的 `Task` 原语（行为）。引擎不认 PQ，只认 Spec。**

```
                    ┌────────────────────────────┐
   玩家(队长) ──组队→ │  PQOrchestrator (每 run 一个) │ ←── 黑板书 phase/角色/分配/完成位
                    │  · 注册 bot / 认领 run      │
                    │  · 分配唯一角色&位置         │
                    │  · 读 EIM 属性判 stage 完成  │
                    └──────────┬─────────────────┘
                               │ 黑板
                    ┌──────────▼─────────────────┐
   EIM 一等公民进本 → │  PartyQuestBot extends BotSM │ → 每 tick：读 Spec → 选 Task → 执行
                    │  · 通用 FSM: LOBBY→ENTER     │
                    │    →STAGE(n)→CLEAR→EXIT      │
                    └──────────┬─────────────────┘
                               │ 组合
        ┌──────────────────────▼───────────────────────┐
        │  Task 原语：Navigate/Rope/StandArea/Hunt/      │
        │  HitReactor/Loot/DropAt/TalkNpc/ProtectNpc/    │
        │  GateSignal/FollowExit                        │
        └───────────────────────────────────────────────┘
```

### 3.2 包结构（新增 `BotTypes/PartyQuest`）

```
soloMapling.ArtificialPlayer.BotTypes.PartyQuest
├── PartyQuestBot.java          // 唯一 bot 类型；通用 phase FSM（取代 OPQBot）
├── PQOrchestrator.java         // 泛化自 OPQOrchestrator；按 leader/eventInstance 认领 run
├── PQSharedContext.java        // 泛化自 OPQSharedContext（phase/assignments/taskComplete）
├── spec/
│   ├── PQSpec.java             // 一个 PQ 的全部元数据（地图/等级/人数/stages）
│   ├── StageSpec.java          // 单关：任务列表 + 完成判定 + 是否需组队卡点
│   ├── PQSpecRegistry.java     // 按 lobby mapId / 事件名 索引
│   └── specs/
│       ├── HenesysPQSpec.java  ├── KerningPQSpec.java
│       ├── LudiPQSpec.java     ├── OrbisPQSpec.java   // 迁移自现 OPQConstants
│       └── ...
├── task/
│   ├── PqTask.java             // interface: Status run(PqContext ctx)
│   ├── TaskStatus.java         // RUNNING / DONE / FAILED / STUCK
│   ├── NavigateTask.java  RopeTask.java  StandAreaTask.java
│   ├── HuntCollectTask.java    HitReactorTask.java
│   ├── LootTask.java      DropAtTask.java  TalkNpcTask.java
│   ├── ProtectNpcTask.java GateSignalTask.java FollowExitTask.java
│   └── WaitTask.java
└── util/
    ├── NPCInteraction.java     // 绑定 BotClient → NPCScriptManager.start/action 序列
    ├── EimAccess.java          // 读 EIM 属性 / 注册 bot / 包好 NPE 守卫
    └── PqRoleAssigner.java     // 组队卡点：唯一位置/反应堆/箱子分配
```

### 3.3 核心接口（骨架）

```java
// 一个 PQ 的元数据
public record PQSpec(
        String eventName,          // "KerningPQ"（用于查 EventManager）
        int lobbyMap, int entryMap, int clearMap, int exitMap,
        int[] insideMaps,          // minMapId..maxMapId 等价
        int minPlayers, int maxPlayers, int minLevel, int maxLevel,
        List<StageSpec> stages) {}

// 单关
public record StageSpec(
        StageKind kind,            // HUNT_COLLECT / BREAK_REACTOR / STAND_AREA / NPC_HANDIN / PROTECT / BOSS / MOVE
        boolean coOp,              // 是否需要把 N 个 bot 摆到不同位置（P11）
        boolean leaderOnly,        // 只有队长点 NPC（bot 只需喂队长道具）
        int[] rewardItemIds,       // 需要收集的物品（P5/P7）
        int requiredQty,           // 需要的数量（如 25、30、40）
        Integer onHandInNpc,       // 交道具的 NPC id
        Point[] targetAreas,       // 站位/破反应堆的目标点
        java.awt.Rectangle[] rects // 精确区域（KPQ 用 Rectangle.contains 判定）
) {}

// 原语
public interface PqTask {
    TaskStatus run(PqContext ctx);   // 幂等；每次 tick 调用一次
    default void reset() {}
}
```

### 3.4 通用 phase FSM（`PartyQuestBot.updateState`）

```
RESET → RECRUIT (大厅喊话 + 自动接受邀请)
      → JOINED   (已入队，等队长开本)
      → ENTER    (被 EIM 注册 → playerEntry 自动进本；读到 inside map 即确认)
      → STAGE(0..N)   每关由 spec.stages[i] 决定跑哪些 Task
      → CLEAR    (EIM 置位 / 队长离场)
      → EXIT     (跟队长回大厅，或 EIM warpEventTeam)
      → LOOP     (队伍还在 → STAGE0 重开；散了 → RECRUIT)
```
> 与现状对比：`detectPhaseFromMap()` 的"地图反推重定位"保留（它对 EIM 强搬很鲁棒），
> 但判定来源从"猜 NPC/位置"改成**读 `eim.getProperty("statusStgN"/"Nstageclear")`**（见 §4.2）。
>
> ⚠️ **更正**：上一句在 v1 里写错了方向——现有代码**恰恰不是**"猜 NPC/位置"，
> 而是"观察 NPC 是否出现"（`isChamberlainSpawned()`）。这个信号本身**没错**（NPC 确实是祭坛触发的副作用），
> 真正的问题是 **bot 丢物品的坐标落在触发框外**，祭坛永远不会被触发（见 REVIEW 错误 1）。
> 另外：**进本之后的换图（进塔、进 Stage 2）必须走 `changeMap`**，
> 不能用 `warpBotToLocation`——后者绕开 `changeMapInternal`，会污染 party 的地图快照（见 REVIEW 遗漏 1）。

---

## 第四部分 · 需要补齐的两块新能力

### 4.1 EIM 一等公民进本（取代 followLeaderWarp）

**机制**：`EventManager.startInstance(party, map)` 会遍历 `party.getEligibleMembers()`，
对 `map.getCharacterById(id)` 命中的角色调用 `registerPlayer → playerEntry`。
`playerEntry` 直接 `player.changeMap(instanceMap, portal0)` —— **不依赖 client socket**。

**结论**：只要 bot ① 在 `recruitMap` 上 ② `getEligibleParty` 的等级/地图条件命中
③ 队伍里有真人队长点 NPC —— **bot 会被自动注册并传进本**，无需 orchestrator 手动 warp。

**要做的**：
- `EimAccess.registerIfEligible(bot)`：进本前把 bot 拉到 `recruitMap`（已有 `warpBotToLocation`）；
  确保 bot 在 party eligible 里（等级用 `BotDecorator` 造到范围内）。
- 若某 PQ 的 `getEligibleParty` 额外条件（EllinPQ 要 `job/1000==0`；GuildQuest 要比对 guildId）不满足，
  用 spec 声明"降级自 warp"开关。
- **验收**：bot 被 `playerEntry` 搬进 920010000，而不是被 `followLeaderWarp` 搬。

> ⚠️ 待验证：模板 bot 共用同一个 headless `BotClient`，`changeMap` 内部有 per-client 状态
> （`closePlayerInteractions` 等）。需实测并可能给每个进本 bot 绑独立 client（`CompanionBot`
> 的做法：`loadPersistentBot` 给私有 `BotClient`）。

### 4.2 读 EIM 属性判通关（取代猜 NPC/位置）

`EventInstanceManager` 暴露 `getProperty(String)` / `getIntProperty(String)`（public）。
各 PQ 脚本会写：`setProperty(stage+"stageclear","true")`、`setProperty("statusStg"+stage,"1")`、
`setProperty("statusStg8","1")` 等。

**要做的**：`EimAccess.stageCleared(eim, i)` / `EimAccess.intProp(eim, key)` 薄封装，
`PQOrchestrator` 用它做**真实完成判定**，取代 `isChamberlainSpawned()` 与"所有 bot 自报"。
所有读取都过 NPE 守卫（`eim==null` / leader 离线返回 false）。

### 4.3 NPC 交互（全新）

**机制**：`NPCScriptManager.start(Client c, int npc, Character chr)` → 脚本 `start()` → `action(mode,type,sel)`。
- `c.canClickNPC()` 需 `lastNpcClick+500 < now`；连续交互要节流。
- 脚本用 `cm.getPlayer()`，模板 bot 必须 `BotClientBinding.runWithBoundPlayer` + **独占 client monitor**。
- `action` 首参 `mode`：`1`=下一步/确认，`0`=取消。
- 脚本按 `status` 逐屏推进，bot 需按"读懂当前屏 → 选对 mode/selection"推进——
  但脚本文本在 host 侧，插件**无法读屏幕**。

**要做的**（由易到难）：
1. **无选择交互**：多数交道具屏是 `sendNext`/`sendOk`（唯一合法回应是 `mode=1`）。
   `NPCInteraction.confirm(npcId, maxSteps)`：循环 `action(1,0,0)` 到脚本 `dispose`。
2. **带选择**：spec 里为每屏声明 `steps[]`（如 KPQ st1：`sendSimple`→选 0 进本；
   `sendNext(question)`→`sendNext`；然后 `haveItem` 分支自动走）。
3. **答题类（KPQ st1）**：脚本从 `stage1Answers` 随机问答，bot 端要"按答案收集对应数量 coupon"。
   spec 里带**问题→答案表**（照抄 host 脚本常量），bot 自己算数量。
4. **确保 bot 不是 leader**：`cm.isEventLeader()` 为真会走队长分支。bot 永远不做队长。

> ⚠️ 这套依赖 host 脚本常量，属于"契约耦合"。必须在 spec 头部注释引用 host 脚本文件与行号，
> 并在 `docs` 记"host 脚本改动会破坏 bot"。

---

## 第五部分 · 通用任务原语（P3–P12 落地）

| Task | 复用的现有能力 | 关键实现点 |
|---|---|---|
| `NavigateTask(point)` | `MovementCommands.pathFinderBeta` | 到点判 `isPointNear`；超时 STUCK |
| `RopeTask(ropeX, targetY)` | `GCMovement`（绳抓取/攀爬）+ `ClimbRecovery` | 绳中点容差；`GCMovement.isClimbing` 校验 |
| `StandAreaTask(rect/point, idx)` | `PathFinder.snapToGround` + `PlatformPlacement` | 站位后 `waitFor` 定住；组合由 Orchestrator 分配 idx |
| `HuntCollectTask(mobIds, itemId, qty)` | `BotAttackDriver` + `MapMobIndex` + `DropCommands.lootItemListOnFloor` | 打最近怪→扫脚下地面→捡；达 qty 完成 |
| `HitReactorTask(reactorFilter, hits=4)` | `CustomReactor.hitReactor` + 分配器 | 复用现 OPQ 的 `pathFinderBetaAerial` + `dropItemAtReactor`。⚠️ **只对 `type!=100` 成立**；物品触发的反应堆（`type==100`）需要用 `forceActivateItemReactor`（见 IMPLEMENTATION §修复 1、§4.2） |
| `LootTask(itemFilter, range)` | `BotLogic.checkForItemsOnFloor` + `DropCommands` | 现 OPQ `handleStage1Loot` 的"脚下一步步下探"逻辑抽出来即通用 |
| `DropAtTask(itemId, point)` | `DropCommands.botThrowItem` | 现 OPQ `handleStage1DropItems` 通用化 |
| `TalkNpcTask(npcId, steps)` | 新 `NPCInteraction` | 见 §4.3 |
| `ProtectNpcTask(npcId, radius)` | `BotAttackDriver` + `MapMobIndex` | 打 NPC 半径内的怪；HenesysPQ |
| `GateSignalTask` | 读 EIM 属性 | 等 host 置位；超时降级 Continue |
| `FollowExitTask` | `GCMovement.follow` / `EimAccess` | 跟队长回大厅；EIM `warpEventTeam` 优先 |

**Reactor 分配器**（从 `OPQOrchestrator` 抽出，泛化）：
`PqRoleAssigner.assign(targets, taken)` —— 最近未认领 + 存活校验 + 失效自动回收。
云碎块/唱片盒/Pirate 反应堆全用它。

---

## 第六部分 · 落地步骤（阶段计划）

> 原则（AGENTS.md）：最小改动、不复制 1000 行、先跑通一个 PQ 再横向铺开。**不一次性写 10 个 spec**。

### 阶段 A — 抽引擎（不改行为，先让 Orbis 用新骨架跑）
1. 新建 `PartyQuest` 包；`PQSharedContext` = 现 `OPQSharedContext` 泛化（phase 枚举改为数据驱动）。
2. `PQOrchestrator` = 现 `OPQOrchestrator` 泛化：run 按 `eventInstance` 认领、`resolveLeader` 加 NPE 守卫。
3. `PQSpec` + `StageSpec` + `OrbisPQSpec`（把 `OPQConstants` 内容搬进 spec）。
4. `PartyQuestBot` 的通用 FSM + Task 组合，**先只实现 Orbis 用到的 Task**（Navigate/HitReactor/Loot/DropAt/StandArea）。
5. 新增 `BotType.PQ_BOT`；`OPQ_BOT` 暂留为别名指向 `PartyQuestBot(OrbisPQSpec)`，跑平后删 `OPQBot`/`OPQConstants`/`OPQOrchestrator`/`OPQSharedContext`。
   - **验收**：Orbis 全流程通关与旧版一致，日志可见"EIM 注册进本"而非 followLeaderWarp。

### 阶段 B — 补两块新能力
6. `EimAccess`（注册/读属性）+ `NPCInteraction`（绑定 client + 逐屏推进）。
7. `PQOrchestrator` 完成判定切到读 EIM 属性；删 `isChamberlainSpawned`。
   - **验收**：Stage 完成由 `statusStgN` 驱动；NPC 交道具成功。

### 阶段 C — 经典四连 spec
8. `HenesysPQSpec`（保护月兔 + 种种子组合）。
9. `KerningPQSpec`（P8 组合：3 人绳/台/桶；P5 打怪集 pass；P7 答题）。
10. `LudiPQSpec`（9 关混合：P5/P6/P7/P8/P10）。
    - **验收**：玩家 + N 个 bot 能通关这三个 PQ；bot 承担非队长角色。

### 阶段 D — 横向铺开（按收益排序）
11. Magatia / Ellin / Pirate（同构于已实现原语，主要补 spec + 少量 Boss 逻辑）。
12. 其余家族（Amoria / Guild / CWK / Cafe / 节日）按引子逐个补 spec；单人可过的（BossRush/RescueGaga）用降级模式。

---

## 第七部分 · 坑点（按严重程度）

### 🔴 会导致功能完全失效
1. **Bot 不是 EIM 成员**：若 `playerEntry`/`changeMap` 在模板 bot 上不工作，一切依赖 EIM 的判定全废。→ 先用独立 `BotClient` 验证。
2. **NPC 交互需独占 client**：多个 bot 并发 `NPCScriptManager.start` 会串 `c.getPlayer()`（`BotClientBinding` 注释明确警告）。→ 交互走单线程队列或 per-bot client。
3. **bot 当了队长**：所有 `isEventLeader()` 分支变味（如 KPQ 要 bot 收集 N-1 个 pass 却没人给它）。→ 组队时保证真人队长；bot 收到 lead 转让必须拒绝或退出。
4. **卡点人数不对**：KPQ st2-4 是"恰好 3 人在这些区域"，party 里真人+bot 的数量必须匹配 spec 期望。→ `PqRoleAssigner` 按 spec 的 `coOp` 精确占位；人不够时降级（`use_enable_stage_skip`）。

### 🟠 会导致行为不正确
5. **反应堆 state 越界**：现 OPQ 有"打前 guard state>=4"，通用版必须保留（真人同 tick 打死会崩地图）。
6. **掉落归属**：clientless bot 不触发正常掉落管线，OPQ 用 `CustomReactor.dropItemAtReactor` 手动补。通用版对所有"bot 最后一击破反应堆"都要补。
7. **绳/台容差**：脚本用 `Rectangle.contains(pos)`，站位点必须落在矩形内（不是边缘）。spec 的点要取矩形中心。
8. **`getEligibleParty` 额外条件**：EllinPQ 只要冒险家（`job/1000==0`）、GuildQuest 要比对 guildId。bot 选错职业会进不了本。
9. **语言**：`opq.*` 等 i18n 节点、NPC 对话都要双语；新 spec 的喊话走 `BotMessages`。

### 🟡 细节
10. **节流**：`canClickNPC` 有 500ms 冷却；交互之间要 `waitFor`。
11. **死亡**：PQ 里 bot 会被打死 → `BotDeath` 的 `tickDelayMs`/`carryHome` 会把它搬回城，破坏 PQ。需要在 PQ 态下抑制 carryHome（跳过 re-home，等 `playerRevive`）。
12. **看门狗/超时**：每 stage 已有 `STAGE_WAIT_TIMEOUT_MS` 思路，通用版每 Task 都要有超时→STUCK→降级。
13. **`PQSpec` 坐标易漂**：所有坐标/物品 id 必须像现 `OPQConstants` 注释那样标注来源（WZ 文件/脚本行号），便于核对。

---

## 第八部分 · 验收标准

| # | 标准 |
|:--:|---|
| 1 | Orbis PQ 在新引擎上全流程通关，行为与旧 `OPQBot` 等价或更好 |
| 2 | bot 经 **EIM `playerEntry`** 进本（日志可证），无 `followLeaderWarp` |
| 3 | Stage 完成由 **EIM 属性** 驱动（`statusStgN`/`Nstageclear`/`clearPQ`） |
| 4 | bot 能对 NPC 完成一次交道具（如 LPQ 交 25 pass） |
| 5 | 真人玩家 + bot 组合通关 Henesys / Kerning / Ludi PQ |
| 6 | bot 在 KPQ st2-4 能被分配到**唯一的**绳/台/桶并正确站位 |
| 7 | bot 在 Orbis/Ludi 能正确破反应堆并捡起掉落 |
| 8 | 任一 Task 卡死 ≤ 设定超时进入 STUCK 并降级，不停摆 |
| 9 | `PQSpec` 无魔法数裸值：每个 id/坐标都有来源注释 |

---

## 第九部分 · 明确不做（YAGNI）

- **不做通用"读脚本屏幕"的 OCR/NLP**：NPC 交互按 spec 声明的"屏序列"推进，不解析脚本文本。
- **不追求 bot 像人一样解谜**：组合谜题由 `PqRoleAssigner` 直接算对组合（读 spec），不靠暴力试错（除非 spec 要求真实感）。
- **不为一次性 PQ 建复杂框架**：BossRush/单人 PQ 用降级"打怪+Boss"模式即可。
- **不迁移 host 的 PQ 脚本**：host 脚本是契约，插件只读 EIM 属性 + 调 NPC 脚本，绝不改 host 逻辑。

---

## 附录 · 关键机制速查

| 事项 | 代码位置 |
|---|---|
| PQ 事件脚本（44 个） | `gms-server/scripts/event/*.js`、中文覆盖 `scripts-zh-CN/event/*.js` |
| 关卡 NPC 判定 | `gms-server/scripts/npc/{9020001 KPQ, 2040036-2040044 LPQ, 2013001 Orbis, 1012112 Henesys…}.js` |
| 反应堆脚本 | `gms-server/scripts/reactor/*.js`（如 `2202001` dropItems、`2202004` sprayItems） |
| EIM 属性/注册/传送 | `EventInstanceManager.getProperty/registerPlayer/registerParty/warpEventTeam/linkToNextStage` |
| 事件启动（抓 party） | `EventManager.startInstance(Party,MapleMap,difficulty)` |
| NPC 脚本驱动 | `NPCScriptManager.start/action/dispose` + `NPCConversationManager` |
| 反应堆手动触发/掉落 | `soloMapling/MapVFX/CustomReactor.hitReactor/dropItemAtReactor` |
| 移动/爬绳 | `MovementCommands.pathFinderBeta/pathFinderBetaAerial/moveToPortal`、`GCMovement` |
| 掉宝/捡取 | `DropCommands.botThrowItem/lootItemListOnFloor`、`BotLogic.checkForItemsOnFloor` |
| 打怪 | `BotAttackSystem/BotAttackDriver` + `BotGrindSystem/MapMobIndex` |
| 队伍邀请 | `BotPartySystem/BotPartyQueue` + `BotPartyInviteBridge` |
| bot 绑 client | `BotClientBinding.withBoundPlayer`（NPC 交互必需） |
