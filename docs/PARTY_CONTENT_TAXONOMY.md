# 组队内容类型总表（Taxonomy）——补 v1 的重大缺口

> **结论先行：`PQ_BOT_PLAN.md` v1 只覆盖了 8 类组队内容里的 1 类半。**
> 那份文档通篇假设"组队内容 = `scripts/event/*.js` 里 `isPq=true` 的 44 个事件脚本"，
> 但服务器的组队内容**有 8 种互不相同的入口机制**，其中 5 种**完全不经过 event 脚本**。
>
> 若按 v1 的 `PQSpec` 架构直接开工，会做出一套**只能吃 A 类**的引擎，且对 B/C/F 类的关键差异一无所知。
>
> 本文是那次遗漏的补全。所有结论已在本仓源码坐实。
>
> 配套文档：[PQ_BOT_PLAN.md](PQ_BOT_PLAN.md)（主方案，已标注勘误）·
> [PQ_BOT_PLAN_REVIEW.md](PQ_BOT_PLAN_REVIEW.md)（逐条复核与 bug 清单）·
> [PQ_BOT_IMPLEMENTATION.md](PQ_BOT_IMPLEMENTATION.md)（实施级细节：机制坐实 + 修复设计）

---

## 第一部分 · 8 类组队内容

| # | 类型 | 入口机制 | 触发入口 | 数量 | v1 覆盖 |
|:--:|---|---|---|---|:--:|
| **A** | **经典 PQ**（event 脚本，`isPq=true`） | `NPC脚本 → em.startInstance(party, map, diff)` | `scripts/npc/*.js` | **44** | ✅ |
| **B** | **其他 event 脚本**（含组队型） | 同上（但很多不要求队伍） | `scripts/npc/*.js` | ~64 | ⚠️ 未按组队筛 |
| **C** | **Monster Carnival**（CPQ / MCPQ） | `NPCConversationManager` Java 方法（**无 event 脚本**） | `startCPQ` / `startCPQ2` | **2**（CPQ1 / CPQ2） | ❌ |
| **D** | **Ariant Coliseum**（阿里安特竞技场） | `createExpedition` + `startAriantBattle` | `2101017.js` | **1** | ❌ |
| **E** | **Nett's Pyramid**（金字塔） | `cm.createPyramid()` | `2103013.js` | **1** | ❌ |
| **F** | **Expedition Boss**（远征队） | `cm.createExpedition(type,…)` | 9 个 NPC 脚本 | **13 种** | ❌ |
| **G** | **Mu Lung Dojo**（武陵道场·组队） | `channel.ingressDojo(party)` + `cm.warpParty()` | `2091005.js` | **1** | ❌ |
| **H** | **Guild Quest**（家族对抗赛） | event 脚本（但走 Guild 队列） | `GuildQuest.js` | **1** | ⚠️ 提及未细查 |

> 另外还有 **Marriage（结婚）**：`Marriage extends EventInstanceManager`，属 A/B 的变体，
> 是**双人**内容而非队伍内容，本方案不覆盖。

---

## 第二部分 · 各类型的关键差异（决定能否复用同一引擎）

### A. 经典 PQ（44 个）——v1 已详述

入口：`em.getEligibleParty(party)` → `em.startInstance(party, map, difficulty)`
资格判定：**`PartyCharacter` 的快照** `ch.getMapId() == recruitMap && level 在区间`（见 REVIEW 遗漏 1）
人数：`minPlayers`–`maxPlayers`
推进：`playerEntry` → `changeMap(instanceMap)`

### B. 其他 event 脚本（~64 个）

同一套机制，但**大量脚本 `minPlayers=1`**（单人可进：`Cygnus_Magic_Library`、`GuardianNex`、
`KingPepeAndYetis`、`NineSpirit`、`Puppeteer`、`RockSpirit`、`RescueGaga`、`s4aWorld`…）。
**这类不需要 bot 陪玩**，bot 只需能自己玩（或干脆不做）。
**真正要陪的是 `minPlayers ≥ 2` 的**——本表 A 类 + 下列这些 B 类：
`MK_PrimeMinister(3)`、`MK_PrimeMinister2(3)`、`DelliBattle(2)`、`ElementalBattle(2)`、
`ElnathPQ(4)`、`ZakumPQ(6)`、`HorntailPQ(6)`、`TreasurePQ(6)`、`BossRushPQ(6)`、
`TD_Battle1-5(6)`、`LatanicaBattle(6)`、`PapulatusBattle(6)`、`Balrog*/Scarga/Showa/Horntail/PinkBean/Zakum Battle(30)`。

> ⚠️ 注意 `BalrogBattle` 等"Battle"类的 `recruitMap == exitMap`（如 `105100100`），
> 是**在城里直接开打**而不是进副本——路线与 PQ 不同。

### C. Monster Carnival（CPQ / MCPQ）——**2 队对抗**

| 维度 | 值 | 出处 |
|---|---|---|
| 入口 | `NPCConversationManager.startCPQ(challenger, field)` | `NPCConversationManager.java:807/857` |
| 队伍 | **两个 Party 互相对抗**（`p1.setEnemy(p2)`） | `MonsterCarnival.java:48` |
| 人数 | 每队 1–6（`new MonsterCarnivalParty`）；`ExpeditionType` 无此项 |        |
| 等级 | CPQ1 `30–50`，CPQ2 `51–70`（由 `lobby.isCPQLobby()` 决定） | `isCPQParty()` |
| 地图 | `980000xxx`（CPQ1）/ `980030xxx`（CPQ2），大厅 `980000000`/`980030000` | `9010022.js` |
| **资格判定** | **`lobby.getCharacterById(pchr.getId())`——实时查地图，不是快照！** | `isCPQParty()` |
| 进本 | `mc.forceChangeMap(map, portal)` —— 走**真 `changeMap`** | `MonsterCarnival.java:64` |
| 时长 | 10 分钟（`startTime = now + 10min`），可加时 | `MonsterCarnival.java:51` |
| 胜负 | `MonsterCarnivalParty.winner`，胜/负分别 warpout 到 `980000003/4 + room*100` | `MonsterCarnivalParty.java:59-65` |
| 机制 | 每队 8 次召唤（`summons`），抢 Festival Points | `MonsterCarnivalParty.java:18` |

**对 bot 的含义（与 A 类完全不同）**：
1. **bot 要"陪玩"就得当对手**——玩家在 CPQ1，bot 得组**第二个队**去挑战。这是"对抗"不是"协作"。
2. 资格用**实时 `getCharacterById`**，所以 REVIEW 遗漏 1 的"快照污染"问题**对 CPQ 不存在**。
3. `forceChangeMap` 走真 `changeMap` → 不会污染快照。
4. **匹配是对手驱动的**：`startCPQ(challenger, field)` 需要**另一个队的队长**先发起挑战。

### D. Ariant Coliseum —— **Expedition 架构 + PvP 抢分**

| 维度 | 值 |
|---|---|
| 入口 | `2101017.js` → `createExpedition(ARIANT…)` → `startAriantBattle(exped, mapId)` |
| 架构 | **`Expedition`（远征队），不是 Party** |
| 人数 | `ExpeditionType.ARIANT = (2, 7, 20, 30, 5)` → 2–7 人，**20–30 级** |
| 时长 | 10 分钟（`MINUTES.toMillis(10)`），9分50秒出记分板 |
| 机制 | 打怪抢分（`updateAriantScore`），分 3 档奖励 `rewardTier` |
| 结束 | `enterKingsRoom()` → 国王房领奖 |

**对 bot 的含义**：bot 需支持 **Expedition 名册**（`exped.addMember`）而不是 Party 邀请。

### E. Nett's Pyramid —— **`PartyQuest` 基类的唯一子类**

| 维度 | 值 |
|---|---|
| 入口 | `2103013.js` → `cm.createPyramid()` → `new Pyramid(party, mode, mapid)` |
| 继承 | `Pyramid extends PartyQuest` —— 全仓**唯一**子类 |
| 难度 | `EASY/NORMAL/HARD/HELL`（0–3），影响 `coolAdd/missSub/decrease` |
| 机制 | **Act Gauge**：`kill()` +1、`cool()` +coolAdd、`miss()` -missSub；归零则 `warp(NETTS_PYRAMID)` 失败 |
| 规模 | party（**无人数下限？** 需再查 `2103013.js` 的 `party.size()` 校验） |
| 勋章 | `PROTECTOR_OF_PHARAOH`（击杀 50000 只） |

**对 bot 的含义**：`createPyramid` 是**独立 API**，且 `Pyramid` 的资格用 `PartyQuest` 构造函数——
它按 `pchr.getChannel() == channel && pchr.getMapId() == mapid` 筛选（**又是快照坑**）。

### F. Expedition Boss（13 种）——**报名 + 倒计时 + 多人 Boss**

`ExpeditionType`：`BALROG_EASY/NORMAL`、`SCARGA`、`SHOWA`、`ZAKUM`、`HORNTAIL`、
`CHAOS_ZAKUM`、`CHAOS_HORNTAIL`、`ARIANT/1/2`、`PINKBEAN`、`CWKPQ`
规模：**最多 30 人**（`maxSize=30`）；等级多为 `50–255` 或 `100/120–255`
流程：`createExpedition` → 报名（`registrationMinutes=5`）→ 满员/超时 → 进图 → 打 Boss

**对 bot 的含义**：如果做这个，bot 是**填人头**的角色。但**风险高**（30 人 Boss 战、bot 会被秒、
掉落分配复杂），**优先级应最后**。

### G. Mu Lung Dojo（组队模式）——**通道级资源池**

| 维度 | 值 |
|---|---|
| 入口 | `2091005.js` → `channel.ingressDojo(true, party, 0)` |
| 资源 | 通道维护 `usedDojo` 位图，**组队 5 个槽、单人 15 个** |
| 等级差 | `isBetween(party, 30)` → **队伍内等级差 ≤ 30** |
| 进图 | `cm.warpParty(925030100 + slot)` —— 走 `warpParty` |
| 地图 | 组队道场 `925030100+`；单人 `925020010+` |

**对 bot 的含义**：
- **等级差限制**是个新约束（A 类没有）——bot 等级必须与玩家**相差 ≤ 30**。
- `warpParty` 是 host 提供的批量传送，bot 若不在 `party.getMembers()` 里就不会被带走（**又是 A 类同款问题**）。
- 组队道场是**5 个并发槽的共享资源**，bot 占用会挤掉其他玩家。

### H. Guild Quest（家族对抗赛）——**唯一按公会组队**

| 维度 | 值 |
|---|---|
| 入口 | `GuildQuest.js`，但由 **Guild 队列**驱动（`addGuildToQueue`） |
| 规模 | `minPlayers=6, maxPlayers=30` |
| 资格 | `getEligibleParty` 额外要求 `ch.getGuildId() == guildId` |
| 特殊 | `reopenGuildQuest()`、`canJoin` 标记、`entryTimestamp` |

**对 bot 的含义**：bot 必须**属于同一个公会**——这是所有类型里最强的资格约束。
v1 只提了一句"GuildQuest 要比对 guildId"，没展开。

---

## 第三部分 · 对 v1 架构的影响（必须改动的地方）

### 3.1 `PQSpec` 不够 —— 需要 `ContentSpec` 家族

v1 假设所有内容都能塞进"事件脚本 + `EventInstanceManager`"。实际上：

```java
// v1（不足）
record PQSpec(String eventName, int lobbyMap, ..., List<StageSpec> stages) {}
//   ↑ 只能表达 A 类

// 修正：按入口机制分化
sealed interface PartyContentSpec permits
        EventScriptSpec,     // A/B：em.startInstance
        CarnivalSpec,        // C：对手队 + startCPQ
        ExpeditionSpec,      // D/F：createExpedition
        PyramidSpec,         // E：createPyramid
        DojoSpec             // G：ingressDojo + warpParty
{}
```

**关键**：bot 的**进场方式**和**资格条件**由入口机制决定，不能统一。

| 类型 | bot 如何进入 | 资格判定用 |
|---|---|---|
| A/B | `playerEntry`（EIM 注册） | **快照** `PartyCharacter.mapid` ⚠️ |
| C | `forceChangeMap`（MonsterCarnival 构造） | **实时** `getCharacterById` ✓ |
| D/F | `exped.addMember` → 进场 | `Expedition.getActiveMembers()` |
| E | `new Pyramid` 构造 → warp | **快照** `pchr.getMapId()` ⚠️ |
| G | `cm.warpParty` | `party.getMembers()` + 等级差 |

### 3.2 `Orchestrator` 的"队长"概念要放宽

v1 的核心假设是"真人玩家当队长，bot 跟随"。但：
- **C（CPQ）**：bot 是**另一队的队长**（或成员），不存在"跟随"；
- **D（Ariant）**：全部走 Expedition，没有"Party 队长"；
- **F（Expedition）**：同 D。

→ `PQOrchestrator` 需要抽象成 **"run 的锚点"**（可能是 Party 队长、可能是对手队队长、可能是 Expedition 队长）。

### 3.3 优先级必须重排

v1 说"优先经典四连"。**这个结论依然成立**，但现在有了更完整的依据：

| 优先级 | 类型 | 理由 |
|:--:|---|---|
| **P0** | A（经典 PQ：Henesys/Kerning/Ludi/Orbis） | 玩家最常跑、协作型、机制最全、bot 只需跟随 |
| **P1** | G（Mu Lung Dojo 组队） | 机制极简（进图打怪），只需补"等级差 ≤30"+"warpParty" |
| **P2** | B 类里 `minPlayers≥2` 的（TD_Battle、ZakumPQ、HorntailPQ…） | 与 A 同构，补 spec 即可 |
| **P3** | C（CPQ） | **对抗型**，语义全新，但玩家很爱玩 |
| **P4** | E（Pyramid） | 独立 API，机制简单（Gauge） |
| **P5** | D（Ariant） | Expedition 架构 |
| **P6** | F（Expedition Boss） | 30 人 Boss，风险最高 |
| **P7** | H（Guild Quest） | 需同公会，约束最强 |

### 3.4 "陪玩" vs "陪跑" —— 目标要分清楚

- **陪玩（协作）**：A/B/E/G —— bot 帮玩家做事（打怪、破反应堆、站位、交道具）。
- **陪练（对抗）**：C/D —— bot **当对手**，故意输或者打得像人。
- **凑数（填人头）**：F/H —— bot 只负责把人数凑够，行为可最简。

> **v1 只考虑了"陪玩"。** C/D 的"机器人当对手"是完全不同的产品需求
> （要能输得像样、要有对抗节奏），不该共用同一套 `PqTask`。

---

## 第四部分 · 补漏清单（v1 之外需新增的调研/设计）

| # | 待办 | 状态 |
|:--:|---|---|
| 1 | 确认 `2103013.js` 的 Pyramid 人数下限 | ✅ **已查明**：组队模式要求 `partyMembersInMap() >= 2`、队长申请、**等级 40–60**（`selection<3 && level>60` 拒绝） |
| 2 | 列出**所有** `minPlayers ≥ 2` 的 event 脚本 | ✅ **已查明**：**44 个**（42 个 `isPq=true` + 2 个 `MK_PrimeMinister*`）。见附录二。⚠️ **注意 `ElnathPQ` 实际是 `minPlayers=1`**，v1 把它归入"要陪玩"是错的 |
| 3 | CPQ 的 `field` 与地图映射 | ✅ **已查明**：`980000100 + field*100` = 红队大厅；`+1` = 蓝队大厅；`+2` = 第三厅；比赛图 = 大厅 id+1。CPQ2 用 `980030xxx` 段 |
| 4 | Expedition 的 `addMember` 与 `ExpeditionBossLog` | ⏳ P6 再查 |
| 5 | Dojo 的 `warpParty` 实现 | ✅ **已查明**：`AbstractPlayerInteraction.warpParty` → 遍历 `getPlayer().getPartyMembersOnline()` + `mc.changeMap(id, portalId)`。**走真 changeMap ✓**，bot 只要在 party 里就会被带走 |
| 6 | Guild Quest 的 bot 入会流程 | ⏳ P7 再查 |
| 7 | `Marriage`（双人）是否需要 bot 支持 | ⏳ 待定 |

---

## 附录二 · 全部"需要 ≥2 人"的 event 脚本（44 个）

> 判定标准：`var minPlayers >= 2`（脚本首行的声明值）。`▲` = `isPq=false`。

| 档位 | 脚本 |
|---|---|
| **30 人**（9 个） | `BalrogBattle` `BalrogBattle_Easy` `CWKPQ` `GuildQuest` `HorntailBattle` `PinkBeanBattle` `ScargaBattle` `ShowaBattle` `ZakumBattle` |
| **6 人**（28 个） | `AmoriaPQ` `BossRushPQ` `CafePQ_1..6` `EllinPQ` `HenesysPQ` `HolidayPQ_1..3` `HorntailPQ` `LatanicaBattle` `LudiMazePQ` `LudiPQ` `OrbisPQ` `PapulatusBattle` `PiratePQ` `TD_Battle1..5` `TreasurePQ` `ZakumPQ` |
| **4 人**（4 个） | `KerningPQ` `MagatiaPQ_A` `MagatiaPQ_Z` `ElnathPQ`※ |
| **3 人**（2 个 `▲`） | `MK_PrimeMinister` `MK_PrimeMinister2` |
| **2 人**（2 个） | `DelliBattle` `ElementalBattle` |

> ※ **勘误**：`ElnathPQ` 脚本首行实际是 `var minPlayers = 1, maxPlayers = 4`。
> 上面按"≥2 人"的**名义档位**归类只是便于查阅；它**不需要陪玩**。
> 所以**真正需要 bot 陪玩的是 43 个**（44 − ElnathPQ）。

<details><summary>完整可复制清单（44 行，格式：名称|minPlayers|isPq）</summary>

```
DelliBattle|2|1
ElementalBattle|2|1
MK_PrimeMinister|3|0
MK_PrimeMinister2|3|0
ElnathPQ|4|1        ← 注意：实际声明 minPlayers=1
KerningPQ|4|1
MagatiaPQ_A|4|1
MagatiaPQ_Z|4|1
AmoriaPQ|6|1
BossRushPQ|6|1
CafePQ_1|6|1
CafePQ_2|6|1
CafePQ_3|6|1
CafePQ_4|6|1
CafePQ_5|6|1
CafePQ_6|6|1
EllinPQ|6|1
HenesysPQ|6|1
HolidayPQ_1|6|1
HolidayPQ_2|6|1
HolidayPQ_3|6|1
HorntailPQ|6|1
LatanicaBattle|6|1
LudiMazePQ|6|1
LudiPQ|6|1
OrbisPQ|6|1
PapulatusBattle|6|1
PiratePQ|6|1
TD_Battle1|6|1
TD_Battle2|6|1
TD_Battle3|6|1
TD_Battle4|6|1
TD_Battle5|6|1
TreasurePQ|6|1
ZakumPQ|6|1
BalrogBattle|30|1
BalrogBattle_Easy|30|1
CWKPQ|30|1
GuildQuest|30|1
HorntailBattle|30|1
PinkBeanBattle|30|1
ScargaBattle|30|1
ShowaBattle|30|1
ZakumBattle|30|1
```
</details>

---

## 附录三 · 关键 API → 是否走真 `changeMap`（决定快照会不会被污染）

| 入口 API | 是否真 `changeMap` | 对 bot 影响 |
|---|:--:|---|
| `EventInstanceManager.playerEntry` | ✅ `player.changeMap(map, portal0)` | 安全 |
| `MonsterCarnival` 构造 | ✅ `mc.forceChangeMap(map, portal)` | 安全 |
| `AbstractPlayerInteraction.warpParty` | ✅ `mc.changeMap(id, portalId)` | 安全 |
| `EventInstanceManager.warpEventTeam` | ✅ `chr.changeMap(...)` | 安全 |
| `Pyramid` 构造 | ⚠️ 需再查（`PartyQuest` 构造函数只读快照） | 待确认 |
| **插件的 `warpBotToLocation`** | ❌ `fakechar.setMap(map)` | **污染快照**（REVIEW 遗漏 1） |

> **结论**：host 提供的所有入队/进场 API 都走真 `changeMap`，
> **唯一会污染快照的是插件自己的 `warpBotToLocation`**。
> 所以修复面很小：只要 PQ 内移动改用它，快照问题就消失。

---

## 附录 · 类型速查（入口 API → 归属类型）

| 入口 API | 类型 | 出现次数 |
|---|---|---|
| `cm.getEventInstance()` | A/B | 73 处 NPC 脚本 |
| `cm.getExpedition()` | D/F | 32 处 |
| `cm.createExpedition()` | D/F | 8 处 |
| `cm.endExpedition()` | D/F | 8 处 |
| `cm.getExpeditionMemberNames()` | F | 5 处 |
| `cm.startAriantBattle()` | D | 1 处（`2101017.js`） |
| `cm.createPyramid()` | E | 1 处（`2103013.js`） |
| `cm.getPyramid()` | E | 1 处 |
| `startCPQ` / `startCPQ2`（Java 方法） | C | `NPCConversationManager` |
| `channel.ingressDojo()` | G | `2091005.js` |
| `addGuildToQueue()` | H | `EventManager` |

**一句话总结**：v1 的 A 类分析（44 个 event 脚本 + 12 种判定原语）是**正确且扎实**的，
但**它只是一类**。真正的引擎要能表达 **5 种入口机制**，并且要认识到
**"陪玩 / 陪练 / 凑数"是三种不同的产品需求**。
