# 组队任务 Bot 实现完整性审计（除玩具塔 LPQ 外）

分析日期：2026-10-20。宿主：相邻仓库 `GMS083`（脚本/WZ/Java 逐行核对），插件：本仓。
范围：**OrbisPQ（92001xxx）以外的全部组队内容**——经典 A 类 PQ、Pyramid、Dojo、Ariant、CPQ、Boss 远征、GuildQuest。
姊妹篇：[TOY_TOWER_ROPE_STALL_AUDIT.md](TOY_TOWER_ROPE_STALL_AUDIT.md)（LPQ/玩具塔攀爬专项，本文不复述）。

> 所有结论均坐实到文件与行号；文档里的旧结论（`PARTY_QUEST_ALL_PLAN.md` 第八/十部分）凡与本文冲突的，以本文为准。

---

## 一、支撑"流程可通"的公共底座（已具备）

| 机制 | 证据 | 状态 |
|---|---|---|
| bot 进本资格 | `EventInstanceManager.admitsParticipant(loggedInWorld ‖ artificial)`（gms-server `EventInstanceManager.java:282-311`），`registerParty → registerPlayer → playerEntry` 真正把 bot 注册进实例并 `changeMap` 进图 | ✅ 已修，`EventInstanceManagerAdmissionTest` 锁定 |
| 引擎回调身份 | 每个 PQ bot 构造时 `BotGeneration.adoptPrivateClient`（每 bot 私有 client），反应堆/机关 5 秒延迟回调、`c.getPlayer()` 均正确 | ✅ |
| 跨房间跟随 | `PartyQuestBot.followLeaderIntoNextRoom`：`portalTo(队长图)` → 无直连门时走 `next00` 追赶，脚本拒门则原地重试 | ✅ |
| 交物 | `PqActions.handItemsToLeader / handItemsToLeaderAfterStage`（丢在队长脚边 / 关卡 NPC 交接柱），30 秒无人捡自动回收 | ✅ |
| 读答案 | `readEimInt/readEimString` 直读 EIM（Kerning stg2-4、Amoria combo、Magatia stage6、Orbis stage6_c） | ✅ |
| 战斗存活 | `BotPotionSim.tick`（喝药）+ 超时 `returnToLobby`（无党/出本兜底） | ✅ |
| 招募大厅投放 | `PqBotSpawner.spawnAllQuestLobbies` ← `EnvironmentManager.environmentLoadStartup` 第 7 波 `late_arrivals.pq_lobbies`，等级取区间下三分之一 | ✅ |

---

## 二、逐任务判定总表

| 任务 | 资格/入口 | 关卡 bot 行为 | 判定 |
|---|---|---|---|
| HenesysPQ | ✅ 10-255，3-6 人，bot 落位 100000200 | ✅ 种花走 `feedReactor`（单叠精确 1 个），月兔守护 + 米糕交接给 Growlie | **可通**（入口无额外门槛，唯一零风险的经典 PQ） |
| OrbisPQ | ✅ 老牌 spawner，5-6 人 | ✅ 全 9 关 + OPQOrchestrator；祭坛落点 (377,99)、单叠 20 已坐实（见 [PQ_BOT_IMPLEMENTATION.md](PQ_BOT_IMPLEMENTATION.md)） | **可通**（成熟度最高；Stage4 三平台需 ≥3 bot 占位，编排器已分槽） |
| BossRushPQ | ✅ minPlayers=1，可单人 | ✅ 清怪→`raid_stage` 开门→下一房，最简单 | **可通** |
| ZakumPQ | ✅ 1-6 人，50+ | ✅ 破箱→捡火矿石(4001018)→丢给队长；出口门只认 eim 已清 | **可通** |
| HorntailPQ | ⚠️ 6/6 满编（玩家 + 5 bot 必须正好 6） | ✅ 5 间钥匙房"杀携带者→捡钥匙→交队长"，光暗分支留给队长 | **可通**（对人数要求苛刻） |
| Pyramid | ✅ Duarte `partyMembersInMap()>=2` 数的是地图上同队角色，bot 计入；`PartyQuest` 构造快照 + `Pyramid.warp()` 也带 bot（无 login 门槛） | ✅ 禁击怪守卫 + gauge 由引擎 `Monster.damage:427` 真实喂分（`attacker.getPartyQuest() instanceof Pyramid`） | **可通** |
| PiratePQ | ✅ 3-6 人 | ✅ 破箱 + 清怪 + 勋章交接 | ⚠️ **eventTime=4 分钟**（`PiratePQ.js:41`），全程极短、容错低；机制上可通 |
| MagatiaPQ(A/Z) | ✅ 4/4，71-85，双城各有 bot | ✅ 打怪破箱 + stage6 组合播报；Yulete 后置空 | ⚠️ 可通（交物/护送推进全靠玩家） |
| EllinPQ | ✅ 4-6 人 | ⚠️ 脊柱反应堆 / 迷宫 NPC（双击 warp 全队✓）正常；**蛙房 bot 刻意完全不干活**（杀蛙=毁捕捉），20 个 Monster Marble(4001169) 全靠玩家自己抓 | 可通，但玩家负担极重 |
| **KerningPQ** | ✅ 3-4 人，21-30 | ❌ **Stage 1 死锁**（见 §3.2）；Stage2-4 站位已核对（4/5/6 个点位全部落在 `9020001.js` 的 Rectangle 内），Stage5 打 boss | ❌ **流程走不通** |
| **AmoriaPQ** | ❌ **入口永不可满足**（见 §3.1） | （bot 代码本身写了镜像战/绳谜题/收集，逻辑完整） | ❌ **流程走不通** |
| **Dojo** | ⚠️ 资格判定 bot 计入（等级差 ≤30 读 `PartyCharacter.level` ✓）；但 `warpParty` 把 bot 丢下（见 §3.3） | ✅ 纯打怪（`DojoPQBot.workStage`） | ❌ **bot 进不了塔** |
| **Ariant** | ❌（见 §3.4） | （捕捉模拟 `AriantCatch.attempt` 写得完整：40% 血量门、50% 掷、成功才扣石头、`updateAriantScore` 计分） | ❌ **开不了赛** |
| CPQ | 特殊（对手队语义，非大厅投放，仅 `!bot cpqbot` 手动生成当陪练） | ✅ `CarnivalSummons.request` 复刻了 handler 的 CP/配额两道闸；`MonsterCarnival` 构造用 `forceChangeMap`（无 login 门槛，bot 能被带进场） | ⚠️ 理论上可打，未实机验证 |
| F 类 Boss 远征 | ❌ 仅有 `ExpeditionTypes` 数据表 | ❌ Boss 战本体行为未做 | 未实现 |
| GuildQuest | ❌ 需同公会（所有类型里最强资格约束） | ❌ | 未实现 |

---

## 三、阻断性缺陷（按因果链给出）

### 3.1 AmoriaPQ —— 入口判定永远为空

`scripts/event/AmoriaPQ.js:97-127` 的 `getEligibleParty` 要求**所有成员 `ch.getPlayer().isMarried()`**（外加性别掩码 `mask == 3`）。
bot 的 `Character.isMarried()` 恒 false（`marriageRing == null && partnerId == 0`，`Character.java:733`）；插件里也没有任何婚姻系统代码。
→ 只要队里有 bot，`hasNotMarried = true`，`eligible = []`，`startInstance` 直接报"人数/资格不符"。
**bot 逻辑写得再全也无法开始一局。**

### 3.2 KerningPQ Stage 1 —— 优惠券计数死锁

`KerningStages.workCoupons`（KerningStages.java:47-98）：

- 只有 `countItem(COUPON) == 0` 时才 `huntCoupons`；
- 手上 >0 时永远只 `answerCloto`。

而 Cloto 的判分是 `cm.itemQuantity(4001007) == stage1Answers[data-1]`（9020001.js:169-172），答案表 `{10,35,20,25,25,30,8}`。
bot 第一轮拾取必然是 1~3 张这种零头，之后**既不再猎券、也不会丢券凑数**，每 tick 对着同一错误数量重答 → Stage 1 永远清不了 → KPQ 全流程卡死。

佐证：
- `KerningPqData.QUESTION_ANSWERS`（KerningPqData.java:64）全仓只有声明处一个引用——死代码；
- `PqActions` 没有 `gridCheck` 读问题的原语（EIM 的 `gridCheck/gridInsert`，EventInstanceManager.java:1421-1453）——"按题号精确凑数"的设计意图没有落地。

修复方向（未实施）：bot 需要先 `talkTo(Cloto)` 拿题（`gridInsert` 后每 bot 一个题号），按 `QUESTION_ANSWERS[题号-1]` 猎券 / 丢券到精确数量，再答对领 pass 交队长。

### 3.3 Dojo —— `warpParty` 把 bot 丢下

`AbstractPlayerInteraction.warpParty`（:159-167）：

```java
for (Character mc : this.getPlayer().getPartyMembersOnline()) {
    if (mc.isLoggedInWorld()) {          // ← 这道门没有 artificial 豁免
        ... mc.changeMap(id, portalId);
    }
}
```

bot 的 `Character.loggedIn` 因 `loadCharFromDB(cid, client, false)` 早退而恒 false（`CharacterService.java:418-422`，只有 `newClient` 才置 true），且这里**没有 `HostHooks.isArtificial` 豁免**（与 EIM 的修复不同）。
→ 队长开塔后 bot 全部留在武馆大厅，`isPartyDojo(92502xxxx)` 永不满足，`DojoPQBot.workStage` 根本没机会跑。

### 3.4 Ariant —— 远征队名册同样被 `isLoggedInWorld` 过滤

`NPCConversationManager.startAriantBattle`（:1072-1109）→ `exped.getActiveMembers()`（Expedition.java:333-341：`chr.isLoggedInWorld()` 无豁免）：

1. 1 玩家 + N bot 时 `playersSize = 1 < minSize 2` → 直接"人数不符"拒绝开赛；
2. 即便过了人数关，`AriantColiseum` 构造（AriantColiseum.java:62-83）只对 activeMembers `changeMap + setAriantColiseum`，bot 进不了场；
3. `AriantCatch.attempt → updateAriantScore()` 依赖的 `getAriantColiseum()` 恒 null → 抓到了也不计分。

**整条链断在准入。** §3.3 与 §3.4 是同一个根因：`isLoggedInWorld` 类门槛在 EIM 之外还有两处没放行 artificial。

---

## 四、覆盖面：远不是"全"

`PqBotSpawner.botTypeFor` 只映射了 13 个大厅（+Orbis 走老路径）。以下 event 脚本**没有任何 bot**（大厅不会生成，单人玩家无法凑队开本）：

- CafePQ_1..6、HolidayPQ_1..3、LudiMazePQ、TreasurePQ（该脚本本身是服务端死局：`statusStg1` 无处置 1，`portal/guyfawkes0_esc.js:21` 门禁永不放行——`PARTY_QUEST_ALL_PLAN.md` 第九部分已记录）、TD_Battle1..5、DelliBattle、ElementalBattle、MK_PrimeMinister 1/2、PapulatusBattle、LatanicaBattle、ElnathPQ
- 30 人远征 Boss 全系（ZakumBattle / HorntailBattle / Balrog / Scarga / Showa / PinkBean / CWKPQ）：只有准入数据表，无 bot 行为

即：44 个"需要 ≥2 人"的脚本里，有 bot 的约 14 个，其中**能完整走通的大约 8~9 个**（Henesys / Orbis / BossRush / Zakum / Horntail / Pyramid，加上条件苛刻的 Pirate / Magatia / Ellin）。

---

## 五、全局性注意事项

1. **频道假设**：ambient bot 一律生在频道 1（`BotGeneration.createBot` 注释明示，`BotChannelRouter.DEFAULT_CHANNEL`）。`registerParty` 里 `map.getCharacterById(mpc.getId())` 用的是队长所在图（= 队长频道）——玩家不在频道 1 就找不到 bot，资格判定直接落空。整套流程隐含"单频道世界"。
2. **实机零验证**：`PARTY_QUEST_ALL_PLAN.md` 自认"全部实机未跑"，931 个单测全是纯逻辑锁数据。§3 各项均未在真机复现（推理链条已完整）。
3. 跟随机制对"走门推进"的任务（`kpq0..4` / `party6_*` / `jnr*` / `raid_stage`）靠追赶门重试，脚本拒门就原地重走——设计正确；对 `warpEventTeam` 类任务天然兼容（EIM chars 含 bot，`changeMap` 无 login 门槛，`changeMapInternal` 自动刷新 `mpc.mapid` 快照）。
4. PiratePQ 的 4 分钟 eventTime 是所有已实现任务里最紧的：bot 节奏（250ms 战斗拍 + 交接柱）理论上够用，但没有余量验证。

---

## 六、修复优先级建议

| # | 事项 | 类型 | 工作量预估 |
|---|---|---|---|
| 1 | `isLoggedInWorld` 门放行 artificial：`AbstractPlayerInteraction.warpParty`（修 Dojo）+ `Expedition.getActiveMembers`（修 Ariant），沿用 `admitsParticipant` 同款布尔函数 + 测试锁定 | 宿主两处小改 | 小 |
| 2 | KerningPQ Stage 1：`PqActions` 增加 `gridCheck` 原语，`workCoupons` 改为"拿题 → 按答案凑券（不足去猎 / 超出丢回）→ 答对领 pass" | 插件逻辑重写 | 中 |
| 3 | AmoriaPQ：要么宿主 `isMarried` 对 artificial 放行（语义弱化），要么明确宣布"Amoria 不支持 bot"并在投放表摘除该行（避免玩家在大厅看见永远无法开本的 APQ bot） | 决策类 | 小（摘除）/ 大（婚姻） |
| 4 | 逐项实机验证：Henesys（1 玩家 + 2 bot 种花 6 朵全开）→ Orbis → BossRush → Zakum → Horntail → Kerning（修复后） | 验证 | — |

---

## 七、一句话结论

底座（进本 / 回调 / 跟随 / 交物）已经修到"纸面可通"；但 **KerningPQ 死在 Stage 1 计数、AmoriaPQ 死在婚姻资格、Dojo 死在 `warpParty` 的 `isLoggedInWorld`、Ariant 死在 `getActiveMembers` 的同一道门槛**——这四个是"实现了却走不通"；另有约 20 个脚本完全没有 bot。真正能完整陪跑的只有 Henesys / Orbis / BossRush / Zakum / Horntail（+ 条件苛刻的 Pyramid / Pirate / Magatia / Ellin）。
