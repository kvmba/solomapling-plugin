# 全组队任务 Bot 陪玩方案（v4 · 实施总纲）

> 前四份文档解决"想清楚"（PLAN / REVIEW / TAXONOMY / IMPLEMENTATION）。
> 本文解决"**做到全**"：把 8 类组队内容逐个实现、验证、提交。
>
> 与前三份的关系：本文是**执行清单**，冲突时以本文 + IMPLEMENTATION 为准。

---

## 第一部分 · 现状与目标（诚实基线）

### 已实现（本次工作前）

| 能力 | 状态 |
|---|---|
| `OPQ_BOT` 一种 PQ bot | ✅ 存在 |
| Orbis Stage 0（打云 → 丢祭坛 → Eak） | ✅ 已修好（落点/单叠/真脚本/私有 client） |
| Orbis Stage 3（音乐盒 + 当天唱片） | ✅ 已修好 |
| Orbis 其余 7 关 | ❌ **完全未实现** |
| 其余 43 个 A 类 PQ | ❌ 未实现 |
| C/D/E/F/G/H 类 | ❌ 未实现 |

### 目标

**真人玩家当队长 + bot 当队友 → 完整跑通每一个组队任务。**

### 支撑本方案的三个已验证事实

| 事实 | 证据 | 意义 |
|---|---|---|
| bot 能造成**真实伤害** | `BotAttackEffects.applyDamageAndLoot` → `target.damage(bot,dmg)` → `map.killMonster` | 打怪关卡可做 |
| 队伍中掉落归属为**队伍**（`droptype=1`） | `dropFromMonster`: `chr.getParty() != null ? 1 : 0` | **bot 打怪、玩家拾取可行** |
| bot 能拿到 EIM，读关卡属性 | `WarpCommands:64` 已有 `fakechar.getEventInstance().getMapInstance()` | 可读 `statusStgN` / 谜题答案 |

### 四个已确认的硬约束

1. **`cm.haveItem` 检查说话者的背包** → 交物必须由**持有者本人**去点 NPC。若道具在 bot 身上，交不了。
   → **分工原则：让 bot 只做"可被观察的公共动作"（打怪/站位/打反应堆/丢物），交物类动作留给玩家；或让 bot 把道具丢地上给玩家。**

2. **`getPlayerCount()` 计入 bot** → bot 进队会**禁用** `use_enable_stage_skip` 的单人跳过。
   → 凡是靠"单人跳过"绕过的谜题，**bot 组队时必须真解**。

3. **bot 的 `basicSwing` 不造成伤害**（仅动画）。真实伤害走 `BotAttackDriver.botAttack`。
   → 关卡实现必须调用后者。

4. **物品类触发要"单叠精确数量"**（`getQuantity() == N`）。
   → 复用 IMPLEMENTATION 的结论。

---

## 第二部分 · 通用引擎设计

### 2.1 分层

```
PartyQuestBot (抽象基类)
  ├─ 通用能力：EIM 访问、阶段等待、队长跟随、NPC 交互、交物、站位、打怪、丢物
  └─ 子类：OrbisPQBot / HenesysPQBot / KerningPQBot / LudiPQBot / ...
       └─ 只声明"每关做什么"，不碰引擎细节
```

### 2.2 八种通用能力（N1–N8）

| # | 能力 | 实现要点 |
|:--:|---|---|
| **N1** | `readEimInt(key)` / `readEimString(key)` | `chr.getEventInstance().getIntProperty(...)`；EIM 为 null 时返回 -1 并记日志 |
| **N2** | `warpToInstanceMap(mapId, portal)` | 必须走 `EIM.getMapInstance(mapId)` 拿**副本内**地图，不能用 `mapFactory`（会拿到别的频道/副本） |
| **N3** | `hitReactorWithScript(oid)` | 已有（`CustomReactor`） |
| **N4** | `dropSingleStack(itemId, qty, pos)` | 已有（`botThrowItemQty`），须精确 qty |
| **N5** | `standOnPlatform(points, index)` | 走到区域中心并**停留**（谜题判定按坐标矩形） |
| **N6** | `huntCollect(mobFilter, itemId, target)` | 打怪 + 扫地板 + 拾取（掉落在队伍内可捡） |
| **N7** | `talkToNpc(npcId, selections[])` | `NPCScriptManager.start("npcId", client, npcId, null)` → `action(client, mode, type, selection)` |
| **N8** | `followLeaderOrWarp()` | 队长换图时跟随；已在 `OPQOrchestrator` |

### 2.3 N7（NPC 交互）是本轮的关键新增

关卡推进**几乎全靠点 NPC**（`2013001.js` 等）；没有 N7 就只能做"打怪"类关卡。

```java
// 伪代码
public static void talkToNpc(Character bot, int npcId, int[] selections) {
    Npc npc = bot.getMap().getNpcById(npcId);
    if (npc == null) return;
    NPCScriptManager mgr = NPCScriptManager.getInstance();
    mgr.start(String.valueOf(npcId), bot.getClient(), npc.getObjectId(), null);
    for (int sel : selections) {
        mgr.action(bot.getClient(), (byte) 1, (byte) 0, sel);   // mode=1 表示点击
    }
}
```

⚠️ 注意：`NPCScriptManager.start` 用的是 `client.getPlayer()`，所以**必须用私有 client**（PQ bot 已具备）。

---

## 第三部分 · 实施顺序（按"可验证的完整闭环"排序）

| # | 内容 | 关卡 | 为什么这个顺序 |
|:--:|---|---|---|
| **1** | **通用引擎**（N1–N7） | — | 后面全部依赖 |
| **2** | **Orbis 补全** | 9 关 | 骨架已有，补 7 关即可；且它覆盖了**全部 8 种能力**（打怪/集物/站位/打反应堆/丢物/找隐藏件） |
| **3** | **Henesys PQ** | 2 关 | 最简单；`minLevel=10` 覆盖新人；验证"跨 PQ 复用" |
| **4** | **Kerning PQ** | 5 关 | 组合谜题；验证"读 EIM 拿答案"这类新能力 |
| **5** | **Ludi PQ** | 9 关 | 与 Orbis 高度相似，验证复用度 |
| **6** | 其余 A 类（El Nath / Pirate / Magatia / Amoria / Ellin / BossRush / Cafe …） | — | 逐个铺开 |
| **7** | B 类（MK / Delli / Elemental …） | — | 结构同 A |
| **8** | C 类 CPQ | — | 对抗型，语义全新 |
| **9** | E 金字塔 / G 道场 / D 竞技场 / F 远征 / H 家族 | — | 独立入口机制 |

---

## 第四部分 · 每关的"完成定义"（DoD）

一个组队任务算"完成"，必须同时满足：

1. **bot 能进本**（被 `registerPlayer` 注册，`getPlayerCount` 增加）
2. **每一关都能推进**（不留死锁；有超时兜底）
3. **玩家全程只需做"人该做的事"**（点 NPC 交物、走位），不需要为 bot 擦屁股
4. **异常有出口**（队长掉线 / 团灭 / 超时 → bot 回到大厅并可重新开始）
5. **有测试**（可纯逻辑验证的部分必须有单测；地图相关的用 `!env reactorboxes` 式的自检命令）

---

## 第五部分 · 逐 PQ 机制档案（实施用）

### 5.1 Orbis PQ —— 9 关全表

| Stg | 地图 | 过关条件 | 归属能力 |
|:--:|---|---|---|
| 0 | 920010000 | 丢**单叠 20** 个 `4001063` 到祭坛 → 出 Eak | N3+N4 |
| 1 | 920010200 | 集 **30** 个 `4001050` 交 Eak | N6+N7 |
| 2 | 920010300 | 找**隐藏**的 2nd Statue Piece（点容器反应堆） | N3+N7 |
| 3 | 920010400 | 丢**当天星期**唱片到音乐盒 | N4 |
| 4 | 920010500 | **3 平台谜题**（读 `stage4_0..2`，人数精确匹配） | N5+N1 |
| 5 | 920010600 | 集 **40** 个 `4001052` 交 Eak | N6+N7 |
| 6 | 920010700 | **2 拉杆组合**（读 `statusStg6_c`） | N3+N1 |
| 7 | 920010800 | 打 6 个 `scar1..6` 反应堆 | N3 |
| 8 | 920010100 | 丢 `4001055` 到雕像底座 | N4 |

### 5.2 Kerning PQ —— 5 关

| Stg | 过关条件 | 能力 |
|:--:|---|---|
| 1 | 队伍答题 → 集 `partySize-1` 张 `4001007` → 换 `4001008` | N6 |
| 2/3/4 | **组合谜题**（4绳选3 / 5平台选3 / 6桶选3）→ 读 `stgNProperty` 拿答案 | N5+N1 |
| 5 | 打 Boss + 集 10 张 `4001008` | N6 |

### 5.3 Henesys PQ —— 2 关

| Stg | 过关条件 | 能力 |
|:--:|---|---|
| 0 | 采种子 → 丢到对应 footing | N4 |
| 1 | 保护 Moon Bunny，集 **10** 个 `4001101` 交 Growlie | N6+N7 |

---

## 第六部分 · 风险与对策

| 风险 | 对策 |
|---|---|
| `NPCScriptManager.start` 的 `client.getPlayer()` 必须是 bot | PQ bot 已用私有 client ✓ |
| 关卡判定依赖站位，bot 走动会破坏 | 站位后 `waitFor` 锁住，不做其他动作 |
| 交物关卡 bot 持有道具 → 玩家交不了 | **让 bot 把道具丢地上**，或只让 bot 打工不拾取 |
| `use_enable_stage_skip` 失效后谜题必须真解 | 读 EIM 答案，不猜 |
| 队长掉线 | 已有 `isChamberlainSpawned` 式守卫 + 超时回大厅 |
| 每关都可能有"玩家的道具" | 优先让 bot 做公共动作，交物留给玩家 |

---

## 第七部分 · 交付节奏

每个 PQ 一个提交，提交信息格式：

```
feat(pq): <PQ 名> — bot 可陪玩全流程

<关卡数> 关全部实现：<能力清单>
验证：<单测 / 自检命令>
```

**未跑通不允许提交。** 无法实机验证的，必须给出可复现的自检命令。

---

## 第八部分 · 实施进度（实时更新）

### 已完成并推送

| # | 内容 | 提交 | 状态 |
|:--:|---|---|---|
| 1 | 通用能力层 `PqActions`（N1–N7） | `555b092` | ✅ |
| 2 | 通用骨架 `PartyQuestBot` | `b562df4` | ✅ |
| 3 | **Orbis PQ** 全 9 关 | `555b092` | ✅ 实现完成 |
| 4 | **Henesys PQ** 2 关 | `b562df4` | ✅ 实现完成 |
| 5 | **Kerning PQ** 5 关 | `bfd9f9a` | ✅ 实现完成 |
| 6 | **Ludi PQ** 9 关 | `d975418` | ✅ 实现完成 |
| 7 | **Boss Rush PQ** | `ae91ebc` | ✅ 实现完成 |
| 8 | **Pirate PQ** 5 关 | `c871c39` | ✅ 实现完成 |
| 9 | **Amoria PQ** 6 关 | `948a6a4` | ✅ 实现完成 |
| 10 | **Ellin PQ** 8 关 | `d9f9208` | ✅ 实现完成 |
| 11 | **Zakum PQ**（矿区） | `766bfa0` | ✅ 实现完成 |
| 12 | **Horntail PQ** 5 关 | `7fb0ccb` | ✅ 实现完成 |
| 13 | **Magatia PQ**（A/Z 双版本） | `6756b49` | ✅ 实现完成 |
| 14 | **Monster Carnival (CPQ)**（陪练型） | `3f01f63` | ✅ 实现完成 |
| 15 | **Nett's Pyramid**（独立入口） | `dd02def` | ✅ 实现完成 |
| 16 | **Mu Lung Dojo**（组队道场） | `04ce3a7` | ✅ 实现完成（含等级差规则） |
| 17 | **Ariant Coliseum**（捕捉竞赛） | `b49d845` | ✅ 实现完成 |
| 18 | **Expedition 表**（Boss 远征准入） | `a55d9c6` | ✅ 实现完成 |

对应的 bot 类型与命令：

| PQ | BotType | 命令 |
|---|---|---|
| Orbis | `OPQ_BOT` | `!bot opqbot` |
| Henesys | `HENESYS_PQ_BOT` | `!bot hpqbot` |
| Kerning | `KERNING_PQ_BOT` | `!bot kpqbot` |
| Ludi | `LUDI_PQ_BOT` | `!bot lpqbot` |
| Boss Rush | `BOSS_RUSH_PQ_BOT` | `!bot brpqbot` |
| Pirate | `PIRATE_PQ_BOT` | `!bot ppqbot` |
| Amoria | `AMORIA_PQ_BOT` | `!bot apqbot` |
| Ellin | `ELLIN_PQ_BOT` | `!bot epqbot` |
| Zakum | `ZAKUM_PQ_BOT` | `!bot zpqbot` |
| Horntail | `HORNTAIL_PQ_BOT` | `!bot htpqbot` |
| Magatia | `MAGATIA_PQ_BOT` | `!bot mpqbot` |
| Monster Carnival | `CARNIVAL_PQ_BOT` | `!bot cpqbot` |
| Pyramid | `PYRAMID_PQ_BOT` | `!bot pyramidbot` |
| Mu Lung Dojo | `DOJO_PQ_BOT` | `!bot dojobot` |
| Ariant Coliseum | `ARIANT_PQ_BOT` | `!bot arpqbot` |

### 尚未实现

| 类别 | 内容 | 状态 |
|---|---|---|
| A 类 | 经典 9 个已实现；剩余：Treasure（服务端死局，见第九部分）、Awakening/Cafe/Holiday 等节日向 | 大部分完成 |
| B 类 | MK_PrimeMinister(3)、DelliBattle(2)、ElementalBattle(2) | 结构同 A，未逐个实现 |
| C 类 | Monster Carnival | ✅ 已实现（陪练型） |
| D 类 | Ariant Coliseum | ✅ 已实现 |
| E 类 | Nett's Pyramid | ✅ 已实现 |
| F 类 | Expedition Boss（13 种准入表已实现；Boss 战本体的 bot 行为未做） | 部分 |
| G 类 | Mu Lung Dojo（组队） | ✅ 已实现 |
| H 类 | Guild Quest | 未实现（需同公会） |

### 已验证 / 未验证

| 项 | 状态 |
|---|---|
| 编译 | ✅ 每次提交前通过 |
| 单元测试 | ✅ 931 个通过（含 50 个本次新增） |
| 数据正确性 | ✅ 全部从脚本/WZ/db 读出，并有测试锁定 |
| **实机跑通** | ❌ **未验证** —— 需要真实客户端进本测试 |

### 实机验证清单（下一步）

1. **Orbis**：`!bot opqbot` 后先 `!env reactorboxes` 核对落点，再跑全 9 关
2. **Henesys**：1 玩家 + 2 bot 进本，观察种花是否 6 朵全开
3. **Kerning**：观察 bot 是否站到正确平台、是否播报点位
4. **Ludi**：观察 Stage 8 bot 是否占 crate 且不占满 5 个
5. **Boss Rush**：单人 + 1 bot，验证 bot 攻击真能造成伤害

**共同风险**：`PqActions` 的 NPC 交互（`talkTo`）与 `holdArea` 的站位精度尚未在真机验证。

### 各 PQ 的实机验证要点

| PQ | 首个要看的东西 |
|---|---|
| Orbis | `!env reactorboxes` 核对落点是否为 `(377,99)` |
| Henesys | 6 朵花是否全开（`stage` 属性递增到 6） |
| Kerning | bot 是否播报点位、是否站对平台 |
| Ludi | Stage 8 是否只占 1 个 crate（给玩家留位） |
| Boss Rush | bot 攻击是否真造成伤害 |
| Pirate | 箱子是否被打破、怪是否被清 |
| Amoria | Stage 2 是否读到 combo；Stage 3 是否正确切换行为 |
| Ellin | spine 反应堆是否被打破 |
| Zakum | 箱子是否掉火矿石 |
| Horntail | 是否按房间读到正确的钥匙 |
| Magatia | Stage 6 是否播报 combo |
| CPQ | 是否成功召唤（CP 扣减 + 怪出现） |
| Pyramid | 是否在禁用怪出现时停手 |
| Dojo | 等级差是否放行（1 玩家 + bot 差值 ≤30） |
| Ariant | 捕捉是否真的加分（Spirit Jewel 进背包） |

---

## 第九部分 · 服务端脚本缺陷（实现过程中发现，非 bot 问题）

以下问题在**服务端脚本本身**，任何玩家（含真人）都无法完成。记录在此以免误判为 bot 实现不全。

### TreasurePQ（`674030000` 系列）—— 关卡门禁永不打开

| 事实 | 证据 |
|---|---|
| 初始化把 `statusStg1` 设为 `"0"` | `event/TreasurePQ.js:119` |
| 出口门禁要求 `statusStg1 == 1` | `portal/guyfawkes0_esc.js:21` |
| **全仓无任何脚本把它置 1** | 全局搜索确认 |

屋子里的箱子（`6741015`）只 `rm.dropItems()`，不掉落任何能推进关卡的东西；
`6741001`（guyfawkesRock）只 `spawnMonster(9400589)`。
→ **该 PQ 在服务端就是死局**，bot 无法"陪着跑通"。

### 结论

实现 bot 之前必须先确认"这个 PQ 在服务端能通"。建议的检查方法：

```bash
# 1. 关卡门禁脚本引用的属性
grep -o 'getIntProperty("[a-zA-Z0-9_]*"' scripts/portal/*.js scripts/npc/*.js | sort -u
# 2. 该属性在哪被赋值（尤其是置 1 / "1" 的地方）
grep -rn 'setProperty("statusStg1", *1\|setIntProperty("statusStg1", *1' scripts/
# 3. 若无 → 该 PQ 不通
```
