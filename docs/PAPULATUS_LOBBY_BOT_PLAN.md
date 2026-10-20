# 玩具城闹钟大厅（时间塔的隐秘场所）Bot 方案 —— PapulatusBattle

> 目标：让单人玩家在 `220080000`（时间塔的隐秘场所）能像玩具塔 LPQ 大厅一样招到 bot 队友，
> 组队进 `220080001` 打帕普拉图斯（PapulatusBattle 事件）。
> 宿主：`GMS083`（脚本/WZ/引擎逐行核对），插件：本仓。
> 复用机制与 [PQ_BOT_IMPLEMENTATION.md](PQ_BOT_IMPLEMENTATION.md) 的既有结论；覆盖缺口见 [PQ_COVERAGE_AUDIT.md](PQ_COVERAGE_AUDIT.md) §四。
>
> **结论先行**：这是一个"标准 A 类单房间 Boss 事件"，用 LPQ/Horntail 的现成模式即可覆盖——
> 新增 1 个包（2 类）+ 3 个注册点 + 2 个语言包段落 + 1 个测试文件补断言；**不改宿主、不改脚本、不改引擎**。
> bot 在本任务里只有两件事：站在大厅被招募、进图后见怪就打；召唤与退场的钥匙都在玩家手里。

---

## 一、现状与证据（全部坐实）

### 1.1 事件骨架（`scripts/event/PapulatusBattle.js`）

| 项 | 值 | 证据 |
|---|---|---|
| 事件名 | `PapulatusBattle` | 脚本注册名，`Populatus00.js:27` 用它取 em |
| 人数 | `minPlayers=1, maxPlayers=6` | `PapulatusBattle.js:27` |
| 等级 | `minLevel=1, maxLevel=255`（成员间**无**互检） | `:28` + `getEligibleParty :89-104` 只做"同图+区间"过滤 |
| 大厅/入图/退图 | `recruitMap=exitMap=clearMap=220080000`，`entryMap=220080001` | `:29-32` |
| 战斗图范围 | `minMapId=maxMapId=220080001`（单图） | `:34-35` |
| 时限 | 45 分钟 | `:37` |
| 大厅数 | `maxLobbies=1`（每频道同时一场） | `:39` |
| 入场 | `playerEntry` → `changeMap(entryMap, portal 0)`，事件把**全队**逐个拉进图 | `:134-137` + EIM `registerParty → registerPlayer → playerEntry` |
| 通关 | `isPapulatus` 只认 `8500002`，死亡 → `showClearEffect + clearPQ`（**不退场**） | `:216-225` |
| 超时 | `scheduledTimeout → end(eim)` 全员送回 `220080000` 并 dispose | `:139-141` + `end :197-204` |
| 退场 | 战斗图**没有出口传送门**；各自与图内 NPC `2041025`（机械装置）对话 `warp(220080000)` | `scripts/npc/2041025.js` + WZ portal 段（仅 `sp`/`st00`，`tm` 均为哨兵） |

### 1.2 大厅图 `220080000`（时间塔的隐秘场所）

- 无怪、安全图（`life` 仅 NPC `2041024` 造型物；`fieldLimit=72`：无 DROP_LIMIT、无禁止药水/移动技能）。
- 三个门闸反应堆 `2208001/2/3` 由事件 `updateGateState` 强制翻转状态（开/关动画），与 bot 无关。
- 有 `in00` 脚本门（`tm=999999999`, `script=Populatus00`）：**只有队长**能踩——`Populatus00.js` 对非队长直接拒绝；bot 永远不需要、也走不进这扇门。

### 1.3 战斗图 `220080001`（时间塔的本源）与召唤链

| 段 | 内容 | 证据 |
|---|---|---|
| 召唤 | 反应堆 `2201004`（时间宝珠，图内唯一反应堆，位于 `-181,-439`）为 **type=100 物品触发**：`4031179`（裂开的次元碎片）×1 落入触发框 → `rm.spawnMonster(8500000, -410, -400)` | `Reactor.wz/2201004.img.xml` event 段 + `scripts/reactor/2201004.js` |
| 触发框 | 反应器区域仅 **x∈[-128,-120], y∈[-558,-523]**（约 8px 宽的窄条）——碎片必须贴着宝珠正上方丢落，`calcDropPos` 会把落点回坐到投掷点下方 | `Reactor.getArea()`（`lt+pos`, `rb+pos`） |
| 三段 Boss | `8500000` 时间球（100级/1.6万HP，undead）→ 死后 revive `8500001` 帕普拉图斯的座钟（125级/**2300万**HP）→ 死后 revive `8500002` 帕普拉图斯（125级/159万HP）。复活由引擎 `Monster` 的 `getRevives()` 链自动执行 | `Mob.wz/8500000/1/2.img.xml` revive 段 + `Monster.java:858+` |
| 计分 | 事件只认 `8500002` 的死亡 → `clearPQ`。三段全要打完 | `PapulatusBattle.js:216-225` |
| 伤害参考 | 座钟 PAD 630 / MAD 480；帕普拉图斯 PAD 800 / MAD 1000；acc 230-250 | `Mob.wz` info 段 |

### 1.4 碎片（4031179）的来源 —— 玩家的责任

- 任务 `7103`（`lvmin=80`，需先完成 7102/7104）：闵先生（`2041021`）在接取时发 1 个（`scripts/quest/7103.js:52-58`）。
- 商店兜底：NPC `9201101`（T-1337，`600000000`）的商店表里有 `4031179`（`V1.0.66__shopupdate.sql:200`）。
- 7103 完成后**不退碎片**（Act 奖励只收 `4031172`、发 `1102057`），所以打完一次任务后玩家通常要走 T-1337 补货。
- **bot 不参与召唤**：bot 拿不到碎片（任务链/商城是玩家行为），引擎的物品触发反应器由投放者 client 驱动（`activateItemReactors(mdrop, owner.getClient())`），玩家丢即可。bot 的职责从"进图"开始。

### 1.5 引擎前提（已具备，无需改宿主）

- `EventInstanceManager.admitsParticipant(loggedInWorld ‖ artificial)` 已放行 bot 注册（审计 §一 已复核），`registerParty → playerEntry` 会把同图同频道的 bot 一起拉进本。
- `playerUnregistered` 脚本函数在 `PapulatusBattle.js:181` 有空定义，插件侧 `unregisterPlayer` 不会因缺函数报错。

---

## 二、方案（完全照 LPQ 模式）

### 2.1 新增 `BotTypes/Papulatus/`（2 个类）

**`PapulatusPqData.java`** —— 纯常量 + 来源注释：

```java
public final class PapulatusPqData {
    public static final int LOBBY_MAP = 220080000;   // 时间塔的隐秘场所（招募大厅）
    public static final int BOSS_MAP  = 220080001;   // 时间塔的本源（唯一战斗图）
    public static final int MIN_PLAYERS = 1, MAX_PLAYERS = 6;   // PapulatusBattle.js:27
    public static final int SUMMON_ITEM = 4031179;   // 裂开的次元碎片（队长的责任）
    public static final int SUMMON_REACTOR = 2201004;
    public static final int EXIT_NPC = 2041025;      // 机械装置：图内唯一退场方式
    // Boss 链（Mob.wz revive 段）：8500000 → 8500001 → 8500002；事件只认 8500002 死亡
}
```

**`PapulatusPQBot.java`** —— 与 `BossRushPQBot` 同构，核心只有三个覆写：

```java
public class PapulatusPQBot extends PartyQuestBot {
    public PapulatusPQBot(Character character) {
        super(character);
        dialoguePath = "PapulatusPQBotDialogue.yaml";   // 可先不建文件：缺失时回落共享社交池（OPQBot 先例）
        botType = "PapulatusPQBot";
        questName = "PapulatusBattle";                  // = 事件脚本名；招募名/聊天池/刷怪表共用同一字符串
        BotGeneration.adoptPrivateClient(character);    // 事件脚本经 client.getPlayer() 解析角色
    }

    @Override protected boolean isInsideQuest(int mapId) { return mapId == PapulatusPqData.BOSS_MAP; }
    @Override protected int lobbyMapId() { return PapulatusPqData.LOBBY_MAP; }

    @Override protected boolean workStage() {
        Character leader = partyLeader();
        // 大厅不算任务内，所以走到这里必然已在 220080001。队长不在图 = 这场已经散了：
        // 他只能经 NPC 退场（无出口门，不存在"出去转一圈回来"）、或死亡回城（playerRevive 会注销他）、
        // 或掉线（PartyCharacter.setOnline(false) 置空 getPlayer()）。三种情况都不可逆，bot 随即收工。
        if (leader == null || leader == getChr() || leader.getMapId() != PapulatusPqData.BOSS_MAP) {
            return true;                                // 基类 returnToLobby：先 leaveInstance 再 changeMap
        }
        if (!getChr().getMap().getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());          // 见怪就打；打 Boss 与打小怪同一路径
        }
        // 图清空但队长还在：他可能在丢碎片召唤、或在捡装备 —— 原地等待，绝不能提前走人。
        return false;
    }
}
```

`updateState()` 与其余 12 个 PQ bot 一致（`super.updateState()` + `checkIfNotRunningOrPaused()` + `tickPartyQuest()`）。
战斗由 `PartyQuestBot` 已注册的 250ms 共享扫荡（`grindTick` → `seekAndAttack`）承担；宏 tick 内的 `workStage` 只做判定与兜底。

### 2.2 三个注册点

| 文件 | 改动 |
|---|---|
| `BotTypeManager.BotType` | 新增 `PAPULATUS_PQ_BOT` 枚举项（照 `LUDI_PQ_BOT` 抄：`new PapulatusPQBot(character)` + `addActiveBot`） |
| `PqRecruitPoints.ALL` | 新增一行 `new Point("PapulatusBattle", 220080000, 80, 160, 1, 6)` |
| `PqBotSpawner.botTypeFor` | `case "PapulatusBattle" -> PAPULATUS_PQ_BOT` |

之后启动波次 `late_arrivals.pq_lobbies`（`EnvironmentManager:292`）自动调 `spawnAllQuestLobbies`：
`botsToOffer(1) × 3 = 15` 只 bot 铺在 `220080000`（与 LPQ 大厅同密度），`levelRange` 抽级、按最终等级重穿装、`setAndStartBots` 全走既有链路。

### 2.3 语言包（两个 yaml 都要，缺一测试即红）

| 键 | en-US | zh-CN |
|---|---|---|
| `pq.recruit.name.PapulatusBattle` | `"Papulatus"` | `"闹钟王"`（CMS085 Mob.wz 官方译名"帕普拉图斯"的社区通称） |
| `pq.chat.PapulatusBattle.start.1..15` / `working.1..15` / `clear.1..10` / `door.1..10` | 50 行 | 50 行（同 key 同数量） |

50 行是 `PqStageChatTest.everySpawnedQuestShipsAnExclusivePoolOfFifty` 的硬性断言（形状照 `BossRushPQ`：15/15/10/10）。
`PqRecruitMessagesTest.everySpawnedQuestHasARecruitName` 与 `poolsAreLocalized` 会自动遍历 `PqRecruitPoints.ALL`，无需改测试逻辑。

### 2.4 测试补断言（`PqRecruitPointsTest` / `PqLobbyDensityTest`）

- `everyQuestWithABotHasARecruitPoint` 的名字数组加 `"PapulatusBattle"`；
- `theRecruitMapsAreTheOnesTheQuestsName`：`assertEquals(220080000, ...recruitMap())`；
- `theLevelBandsMatchTheQuestsOwnNumbers`：`min=80`（下限与上限按实际取值断言）；
- `PqLobbyDensityTest` 可加 `assertEquals(15, byName("PapulatusBattle").botsToOffer(1) * 3)`；
- 可选：`PapulatusPqDataTest`（BOSS_MAP≠LOBBY_MAP、人数 1~6 边界，仿 Magatia 数据测试）。

---

## 三、等级带 80 ~ 160 的论证（本任务的关键细节）

1. **不能照抄脚本的 1~255。** `levelRange` 从带内下三分之一抽级：若 min=1 会铺出 1~10 级 bot，而
   `BotRecruitManager.rollPartyAsk` 对 **<10 级的 bot 直接拒绝组队**（招募机制整体失效）；即便进队，
   125 级 Boss 一触即死，`playerRevive → unregisterPlayer` 会把它们逐个踢出实例。
2. **80 是任务链门槛**：7103 的 `lvmin=80`（Quest.wz Check.img），拿到碎片打这场的人至少 80 级。
   与既有 boss 行取法一致（ZakumPQ 50 = 扎昆入门、HorntailPQ 120 = 洞穴门槛）。
3. **成员间无等级互检**：本任务 `getEligibleParty` 只查"在大厅 + 1~255"，**不是** Dojo 那种 30 级 spread——
   200 级玩家可以直接带走这批 80~106 的 bot，不存在"bot 等级卡死全队"的阻断。
4. **80~160 经抽取实际落在 80~106**（`high = 80 + max(9, (160-80)/3) = 106`）。若想让大厅人群观感更贴近
   Boss 本体，可把行改成 `(80, 255)`（落点 80~138）——两档都能用，默认按用户选定 `80~160` 落 80~106。
5. **存活核算**：bot 触伤 = Boss PAD × 0.5 ≈ 315~400/跳，i-frame 2.5s；80+ 级 bot 血线 ≈ 1800~2400
   （`BotPotionSim.ensureMaxHp`），单跳 ≈ 17% 血，potionSim 急速/平缓双档回血覆盖；致死仅发生在
   "血线触底后再挨打 50% 掷"——80 级起步无站桩秒杀风险。

---

## 四、运行时序与边界场景

```
启动:  spawnAllQuestLobbies → 15 只 PapulatusPQBot 落在 220080000（ch1）→ tickLobby 招募/闲逛/喊话
组队:  玩家喊话/对话 → offerRecruit → rollPartyAsk(0.70) → 入队（≥80 级，无门槛冲突）
开本:  队长踩 in00（Populatus00 只放队长）→ getEligibleParty(大厅+1~255 全过) → startInstance
       → registerParty 按成员序同步逐个 registerPlayer→playerEntry，队长先落图 → 全队进 220080001
战斗:  bot tick: isInsideQuest=true → 队长在图 → 无怪=等待（等碎片召唤/捡装备），有怪=seekAndAttack
       250ms 扫荡持续输出；potionSim 回血；三段 Boss 链由引擎 revive 自动推进
通关:  8500002 死 → clearPQ（不退场）→ 队长捡完 → 队长与 NPC 2041025 对话退场
       → bot 下一 tick 判定"队长不在图" → workStage=true → returnToLobby（先 leaveInstance 再 changeMap）
散伙:  队长中途 NPC 退场/死亡回城/掉线 → 同上，bot 下一 tick 自行回家（掉线时 partyLeader()=null）
超时:  45 分钟 scheduledTimeout → end(eim) 把留在图内的 bot 一并 warp 回大厅 → dispose
死亡:  bot 在图内死亡 → BotDeath.carryHome → returnMap(220000000 玩具城) → tickPartyQuest 判定
       "任务外且不在大厅" → returnToLobby 自动回到 220080000 重新进招募池（自愈，无需新代码）
```

| 边界 | 行为 | 依据 |
|---|---|---|
| bot 提前退场会不会卡住事件 | 不会。`returnToLobby` 先 `eim.unregisterPlayer` 再 changeMap，bot 的退场 warp 不触发脚本 `changedMap`，不误触 `end(eim)`/门闸翻转 | `PartyQuestBot.returnToLobby:821-845` 注释即此语义 |
| 空实例占住唯一大厅 | 事件只有 `scheduledTimeout`/`end` 会 dispose；bot 侧保证计数不残留，最迟 45 分钟由超时收尾并 `updateGateState(0)` 复位门闸。真实玩家队伍弃团同样如此——脚本自身设计，非 bot 缺陷 | `PapulatusBattle.js:197-204, 238-242` |
| 进本竞态 | `registerParty` 同步逐人 `playerEntry`，队长（成员表首位）先落图；bot 落图时队长必已在内，"队长不在→撤"无误报窗口 | `EventInstanceManager.registerParty:435-447` |
| 通关后 leader 磨蹭 | 图清空 + 队长在 → bot 原地等（return false），与 BossRush"移不移动听队长的"同一纪律 | `BossRushPQBot.workStage` |
| 队长掉线但 party 还在 | `PartyCharacter.setOnline(false)` 置空 `character` → `partyLeader()==null` → bot 回家，不挂到超时 | `PartyCharacter.java:82-86` |
| 队伍解散 | 基类 `tickPartyQuest` 的 `bot.getParty()==null → returnToLobby` 已覆盖 | `PartyQuestBot:327-330` |

---

## 五、明确不做的事（避免过度设计）

1. **不改宿主 GMS083 任何代码**——EIM 准入已放行 bot；`registerParty` 链路是 13 个现有大厅验证过的。
2. **不改 `PapulatusBattle.js` / `Populatus00.js`**。
3. **不给 `BotScriptedWarp`/GCTravel 加 220080001 的边**——进本靠事件拉人；bot 走不进也不该走进 `in00`（脚本只放队长），`followLeaderIntoNextRoom` 在本图两个方向都找不到可走门（`tm` 均为哨兵），天然不误触。
4. **bot 不参与召唤**——碎片是玩家任务物品/商城货；触发反应器由投放者 client 驱动，bot 无从代劳。
5. **不写 bot 主动退出战斗图的捷径**——退场必须走 NPC（玩家）或 `returnToLobby`（bot 自身判定），两态之外没有第三种合法路径。
6. **不加"队长离开宽限期"**——已核实"队长不在 220080001"三种成因均不可逆且判定读的是活体 `Character.getMapId()`，无瞬态误报窗口；宽限期属过度防御。

---

## 六、已知约束（沿袭，非本方案引入）

- **单频道世界**：bot 全部生在 ch1（`BotChannelRouter.DEFAULT_CHANNEL=1`），`registerParty` 用队长所在频道的图查人——玩家在 ch1 才能带上 bot。与全部现有 PQ 大厅同一约束（[PQ_COVERAGE_AUDIT.md](PQ_COVERAGE_AUDIT.md) §5.1）。
- **每频道同时一场**（`maxLobbies=1`）：bot 常驻大厅不占场（bot 永不踩 `in00`），只有玩家队长开本才占。
- **实机零验证**：本文与全仓 PQ 结论一致，均为静态核对；实装后按审计 §六-4 的顺序实机验证（本任务建议插在 Horntail 之前：单房间、无门链、无换房，是最容易一次跑通的新增项）。

---

## 七、实施清单（Checklist）

- [ ] `BotTypes/Papulatus/PapulatusPqData.java`（常量+来源注释）
- [ ] `BotTypes/Papulatus/PapulatusPQBot.java`（isInsideQuest / lobbyMapId / workStage 三覆写）
- [ ] `BotTypeManager.BotType.PAPULATUS_PQ_BOT`
- [ ] `PqRecruitPoints.ALL` 加行 `("PapulatusBattle", 220080000, 80, 160, 1, 6)`
- [ ] `PqBotSpawner.botTypeFor` 加 case
- [ ] `BotMessages.yaml`：`pq.recruit.name.PapulatusBattle` + 50 行聊天池
- [ ] `BotMessages-zh-CN.yaml`：同上（zh，同 key 同数量）
- [ ] `PqRecruitPointsTest` / `PqLobbyDensityTest` 补断言
- [ ] `mvn test`（重点：PqStageChatTest / PqRecruitMessagesTest / PqRecruitPointsTest / PqLobbyDensityTest）
- [ ] 实机：lv100 玩家在 220080000 招 2 bot → 全队进图 → 丢碎片 → 三段 Boss → NPC 退场 → bot 回大厅

---

## 八、自查记录（2026-10-20 复核，方案定稿前）

对初版分析做了逐条对源复核，**发现并修正**：

| # | 初版说法 | 复核结论 | 修正 |
|---|---|---|---|
| 1 | Boss 链为 `8500000 → 8500002` 两段 | 实为**三段**：`8500000 时间球 → 8500001 座钟(2300万HP) → 8500002 帕普拉图斯(159万HP)`，事件只在 8500002 死亡时 clearPQ | §1.3 改为三段链；bot 无感知差异（见怪就打），但 HP 预算与"通关判定"表述已订正 |
| 2 | "队长先走后 bot 用 workStage 返回 true 收工"只考虑了退场一种成因 | 队长消失有**三种不可逆成因**：NPC 退场、死亡回城（`playerRevive` 注销 + respawn 到 returnMap）、掉线（`PartyCharacter.setOnline(false)` 置空 `getPlayer()`）。判定条件必须三态全覆盖：`leader == null ‖ leader == getChr() ‖ leader.getMapId() != BOSS_MAP` | §2.1 `workStage` 判定改为三条件；掉线场景由 `null` 分支自愈，不会挂到 45 分钟超时 |
| 3 | "碎片可召唤"默认 bot 也许能代劳 | 触发反应器由**投放者 client** 驱动（`activateItemReactors(mdrop, owner.getClient())`），且碎片只来自玩家任务链/商城——bot 结构上拿不到也不该拿 | §1.4/§五-4 明确划出玩家责任 |
| 4 | 触发框位置描述为"反应堆脚下" | 实测区域仅 **8px 宽窄条**（x[-128,-120] y[-558,-523]），`calcDropPos` 会回坐落点——玩家需贴宝珠正上丢落 | §1.3 补实测坐标，避免实机验证时误判为 bot 问题 |
| 5 | 初版未核对 bot 自身退场与事件回写的相互作用 | `returnToLobby` 先 `leaveInstance` 后 changeMap，bot 退场不触发脚本 `changedMap`、不误触 `end(eim)`/门闸翻转；空实例由 45 分钟超时兜底 dispose 并复位门闸 | §四 时序/边界表新增两行 |
| 6 | 初版默认加"队长离开宽限期"防误报 | 三种成因均不可逆、判定读活体 mapId、进本序保证队长先落图——无瞬态窗口，宽限期属过度防御 | §五-6 列为明确不做 |
| 7 | 初版等级带写"80~255" | 用户选定 **80~160**；经 `levelRange` 下三分之一抽取实际落 **80~106**；并核实本任务**无**成员间等级互检（区别于 Dojo spread），高等级玩家可直接带走 | §三 重写论证，两档取值均给出落点 |
| 8 | 初版未列语言包/测试的硬性联动 | `PqStageChatTest` 对 `PqRecruitPoints.ALL` 每名断言恰 50 行×2 语言；`PqRecruitMessagesTest` 断言招募名存在——漏一项测试即红 | §2.3/§2.4 固化为清单 |

复核方式：event/npc/portal/reactor 脚本、`Map.wz`/`Reactor.wz`/`Mob.wz`/`Quest.wz`(Check/Act/QuestInfo)、
`String.wz`(CMS085 中文名)、宿主 `EventInstanceManager`/`EventManager`/`MapleMap`/`Reactor`/`Monster`/
`Party`/`PartyCharacter`/`ChangeMapHandler`、迁移 SQL（商店表）、插件侧 `PartyQuestBot`/`PqActions`/
`BotDeath`/`BotContactDamage`/`BotPotionSim`/`PqBotSpawner`/测试三件套。全部为静态核对，未实机运行。
