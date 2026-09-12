# Bot 死亡状态 + HP 下限 5%（规划，待确认）

## 1. 目标语义

| 规则 | 定义 |
|---|---|
| 活着 | `hp >= max(1, ceil(maxHp * 5%))`——血条不再看起来是空的 |
| 死亡 | `hp < 1`（即 0）。不可移动、不可被击退、允许说话 |
| 死亡触发 | ① 单次伤害 `>= 当前总血量上限`（秒杀）② hp 已在 5% 下限、怪还在啃 → 每记实击 50% 终结 |
| 死亡表现 | 倒地（宿主已有 DEAD 姿态）+ 吐槽/骂街，仅当地图有真人时才说话 |
| 复活 | 随机 30–60s 后 → **先 HP 恢复到 100%，再直接 `changeMap` 传送**到安全区（不走路） |
| 安全区仍有怪 | 到达后若该图仍有敌对怪 → 按正常规则继续（可能再死）；**次数不限** |
| 城镇内死亡 | 同样适用。语料不同：骂把怪弄进城的人 |
| 掉经验 | **保留**（宿主 `playerDead()` 的 10% 经验惩罚）。死亡有代价，合理 |

## 2. 现状（已核实的代码事实）

| 事实 | 位置 |
|---|---|
| 接触伤害永远留 1 HP：`hpDamage = min(dmg, currentHp - 1)`，走 `safeAddHP`（内部再夹 ≥1） | `BotContactDamage.resolveMobHitDamage:184` / `applyMobHit:160` |
| `hp <= 0` 时物理层**已经**会摆 DEAD 姿态 | `BotPhysicsEngine.resolveStance:1593` → `resolveDeadStance` |
| 所有 bot 的宏 tick 唯一入口 `tickRunnable` | `BotSM:74` |
| 训练 bot 战斗 tick 是第二入口（共享 250ms ticker） | `TrainingBot.grindTick:191` |
| 地图安全区 = `getReturnMapId()`；`MapId.NONE = 999999999` 表示无 | `MapleMap.getReturnMapId` |
| 遍历活体敌对怪的现成写法 | `map.getAllMonsters()` + `SpotFinder.isHostile(m)` |
| 模板 bot 模拟喝药已有 `isAlive()` 门 → **hp=0 时自动停**，天然满足"死亡期间不回血" | `BotPotionSim.appliesTo:140` |
| 伴侣喝药同样有 `isAlive()` 门 | `CompanionSurvivalController.useNeededPotion:106` |
| 坐椅回血**无死亡门**（host 定时任务）→ 死亡进入时必须清椅子 | `Character.startChairTask:2439` |
| `getCurrentMaxHp()` 是客户端血条用的生效上限 | `AbstractCharacterObject` |

## 3. 改动 A：HP 下限 1 → 5%

新建 `BotHealthSystem/BotHealthFloor.java`（~25 行，一个纯函数，两处共用）：

```java
public static final double HP_FLOOR_RATIO = 0.05;

public static int floorFor(int maxHp) {
    if (maxHp <= 0) {
        return 1;               // 上限未知时不放大伤害
    }
    return Math.max(1, (int) Math.ceil(maxHp * HP_FLOOR_RATIO));
}
```

- `BotContactDamage.resolveMobHitDamage(currentHp, rolledDamage)` → 增加 `maxHp` 参数，`hpDamage = min(broadcastDamage, max(0, currentHp - BotHealthFloor.floorFor(maxHp)))`
- 上限口径统一用 `getCurrentMaxHp()`（与 `BotPotionSim` 一致，也与玩家看到的血条比例一致）
- 因为 floor ≥ 1，`safeAddHP` 内部的 `hp + delta <= 0` 夹紧不会被触发，无需改宿主

**不回血的三道防线**（死亡期间）：
1. `BotPotionSim.appliesTo` 的 `isAlive()` —— 已有，自动生效
2. 死亡进入时调 `botCancelChair(chr)` —— 新增，堵住坐椅回血
3. `CompanionSurvivalController.useNeededPotion` 的 `isAlive()` —— 已有，自动生效

## 4. 改动 B：死亡状态机

新建 `BotHealthSystem/BotDeath.java`（~150 行），每 bot 一份，挂在 `BotSM` 上。

### 4.1 判定（在 `BotContactDamage.applyMobHit` 内）

**为什么需要第二条**：bot 的 maxHp 是 `50 + 22*(level-1)`（`BotPotionSim`），70 级 ≈ **1568**；
接触伤害是 `max(1, mob.getPADamage()) * 0.5`，典型 **100–200**。
即"单次伤害 ≥ 总血量上限"在真实数值下**几乎永不成立**，只有条件①会导致整套死亡逻辑成为死代码。

因此两条判定（命中任一即死）：

```java
// ① 秒杀：一击打穿整个血池
boolean lethal = broadcastDamage >= maxHp;
// ② 来不及加血：血已经见底（5% 下限）且这只怪还在啃 —— 半概率终结
if (!lethal && currentHp <= floor && broadcastDamage > 0 && rng.nextDouble() < 0.50) {
    lethal = true;
}
```

走 `BotDeath.kill(bot)`（内部 `updateHp(0)` + `updatePartyMemberHP()`），**不走** `safeAddHP`
（那句 `hp + delta <= 0` 会把 hp 夹回 1，永远死不掉）。

致命一击后 hp 从 ≥5% 直接归零，血条空掉——死亡本该如此，与需求 A 的"活着不要空血条"不冲突。

### 4.2 死亡期间——总闸在 `BotSM.tickRunnable`

```java
if (death != null && death.isDead()) {
    death.tick();          // 计时 / 吐槽 / 回城 / 复活
    return;                // 整个 updateState 让位 → 所有 bot 类型自动遵守
}
```

例外：`state == TRADING` 时不拦截（不打断正在进行的交易）。

### 4.3 不动——`GCMovementDriver.tick` 早退

在 `maybeRefreshProfile` / map-change 之后、`resting` 与 `BotContactDamage.tickMobDamage` 之前插入：

```java
if (bot.getHp() <= 0) {
    BotPhysicsEngine.idleOnGround(entry, bot);   // resolveStance 因 hp<=0 自动给 DEAD 姿态
    broadcastIfObserved(entry);
    return;
}
```

死亡姿态不需要任何新代码——`resolveStance` 已经处理了 `hp <= 0`。

### 4.4 时机与回城

**核心原则：死亡只能由伤害判定触发。** 传送回安全区后 bot 是**站着满血**的，
不是"发现有怪就再死一次"——它得像正常 bot 一样，被那张图的怪**真的打死**才算再次死亡。
`BotDeath` 不监听地图、不做怪物计数来判死，那属于 `BotContactDamage`。

```
DEAD  ──随机 30~60s──►  选安全区
                          │
       ┌──────────────────┼──────────────────┐
  安全区有效且≠当前图    安全区==当前图       安全区无效(≤0 / NONE)
       │                 且当地有怪            │
  先 updateHp(100%)     保持 hp=0 躺着        同右：原地等怪消失
  再 changeMap 直接传   每 tick 复查怪物
  送到安全区
       │
  落地后 = 普通满血存活 bot，交还 FSM 按职责行动
       │
  ├─ 被安全区的怪打死 ──► 新的一轮死亡（语料按**新地图**重新判定 A/B，次数不限）
  └─ 没被打死 ──► 正常继续
```

- **不走路**：`chr.changeMap(safeMapId)` 直接落地（`GCTravel` 那一套门/船/出租车全跳过）
- 唯一保留"位置检测"的地方是**传不动**的两种情况（安全区 == 当前图 / 无效）：
  那里站着复活等于原地再被围殴，所以躺着等怪消失——这正是需求里"城镇内也可能出现怪物"的那条规则
- 计时**不受观察门影响**（没玩家也在推进）；只有**说话**受 `GCMovement.isMapObserved` 门控制
- 吐槽节流：8–15s 一句
- 安全区解析顺序：`map.getReturnMapId()`（有效且非 NONE）→ `BotSM.safeReturnMapId()` 钩子（默认 -1；`TrainingBot` 覆写为 `homeMapId`）→ 当前图

### 4.5 复活（先满血，再 warp）

```java
BotClientBinding.runWithBoundPlayer(bot, () -> bot.updateHp(bot.getCurrentMaxHp()));
BotClientBinding.runWithBoundPlayer(bot, () -> bot.changeMap(safeMapId));
```

**顺序不能反**：先满血再传送，落地才是满血的；传送成功后清死亡态、交还 FSM。

## 5. 改动 C：吐槽语料（A/B 两类，各 ≥100 条）

新建 `BotDialoguePack/BotDeathDialogue.yaml` + `BotDialoguePack-zh-CN/BotDeathDialogue.yaml`，
**不动现有 20+ 个对话文件**——死亡是通用行为，不是某个 bot 类型的台词。

| 节点 | 场景 | 判定 | 语气 |
|---|---|---|---|
| `FieldDeath` | A：野外有怪死亡 | `MapMobIndex.level(mapId) >= 0`（这张图本来就该有怪） | 骂那只怪、骂自己走神、骂掉率、喊冤、假装没事 |
| `TownDeath` | B：城镇意外死亡 | `MapMobIndex.level(mapId) < 0`（WZ 里本无怪 → 有人把怪弄进来了） | 骂召唤/引怪的玩家、骂 GM、骂运气、报警、要求赔偿 |

判定用 WZ 静态数据（`MapMobIndex` 已有缓存），**不猜、不数活体怪**——"城镇"的定义是
"这张图本来没有怪"，所以无论是玩家引来的、召唤的、GM 刷的，都归 B。

每条语料池进 5 类情绪分层，避免 200 条听起来像一个人在念：
1. 骂怪 / 骂人（30%）
2. 自嘲 / 认栽（25%）
3. 喊冤 / 不服（20%）
4. 躺平 / 摆烂（15%）
5. 威胁 / 放狠话（10%）

走 `BotDialogueHandler.getRandomResolvedLine(...)` 复用现成的 token 解析与
"解析不出就退回无 token 行"策略。`{MOB}` `{MAP}` 各池混用，token 解析失败自动退回纯文本行。

## 6. 改动清单

| 文件 | 改动 | 量级 |
|---|---|---|
| `BotHealthSystem/BotHealthFloor.java` | **新建** 5% 下限纯函数 | ~25 行 |
| `BotHealthSystem/BotDeath.java` | **新建** 死亡状态机 | ~150 行 |
| `BotDialoguePack/BotDeathDialogue.yaml` + zh-CN | **新建** A/B 语料，各池 ≥100 条 × 2 语言 | ~500 行 |
| `BotSM.java` | `death` 字段；`tickRunnable` 总闸；死亡时保持 2–6s 节奏；`safeReturnMapId()` 钩子 | ~15 行 |
| `BotContactDamage.java` | 致命判定 + `updateHp(0)`；`resolveMobHitDamage` 加 `maxHp` 参数 + 5% 下限 | ~12 行 |
| `GCMovementDriver.java` | tick 开头 `hp<=0` 早退 | ~6 行 |
| `TrainingBot.java` | `grindTick` 死亡 gate；覆写 `safeReturnMapId()` | ~8 行 |
| `BotContactDamageTest.java` | 跟进新签名 + 致命/下限用例 | 测试 |
| `BotDeathTest.java` | **新建** 下限、致命判定、安全区选择、有怪循环 | 测试 |

## 7. 测试计划（纯逻辑，不启服）

`BotHealthFloorTest`
- 5% 取整：`floorFor(1000) == 50`、`floorFor(50) == 3`、`floorFor(1) == 1`、`floorFor(0) == 1`

`BotContactDamageTest`（改签名后）
- 满血小伤害：hp 正常扣
- 打到下限：hp 停在 5%，不再往下（`hpDamage == currentHp - floor`）
- 已在下限：再挨打 `hpDamage == 0`
- 致命一击（`dmg >= maxHp`）：走死亡路径（返回致命标记，hp 归 0）

`BotDeathTest`
- 安全区解析：returnMap 有效且 ≠ 当前图 → 传送；== 当前图且有怪 → 保持死亡；NONE → 保持死亡
- 传送目标正确 + **先满血后传送**的顺序
- 安全区无怪 → 满血后直接复活（不等待），交还 FSM
- 死亡态下 `updateState` 被让位（总闸生效），`TRADING` 例外放行
- 死亡计数：多次在安全区被杀 → 次数累加，无上限

`BotDeathDialogueTest`（防语料回归）
- 两语言、两节点各 ≥100 条
- 无重复行
- 每条 ≤ 60 字符（游戏内聊天气泡不折行）

## 8. 已拍板 / 风险

| # | 项 | 结论 |
|---|---|---|
| 1 | 掉经验 | **保留**。宿主 `playerDead()` 的 10% 惩罚，死亡有代价，合理。bot 的 buff 只是可视化包，被清无感 |
| 2 | 致命门槛 | **已加第二条**：hp 在 5% 下限 + 怪还在啃 → 每记实击 50% 终结。否则 `dmg >= maxHp` 在真实数值下几乎永不成立，整套逻辑是死代码（见 4.1 的数值推算） |
| 3 | 回城方式 | `changeMap` 直接传送（不走 GCTravel 的门/船/出租车），先满血 |
| 4 | 再次死亡 | **只由伤害判定触发**。传送落地后是普通满血 bot；只有真被安全区的怪打死才算新一轮。不做"发现有怪就再死" |

剩余风险
- **a. 死亡总闸吞掉 `updateState` 全部行为**：已对 `TRADING` 放行；扫过 20+ 个 `updateState`，
  未见"必须在 tick 里做"的副作用。个别 bot 若有长链异步回调（如 TrainingBot 的桑拿行程），
  死亡期间会被挂起——可接受（人死了还去泡桑拿不合理）。
- **b. 死亡计数无上限**：一张图连死要打 debug 日志，便于观察是否有 bot 卡在死亡循环。
- **c. `changeMap` 到未加载地图**：宿主 `getMap(id, true)` 找不到会静默 return，
  死亡态会一直挂着。兜底：传送后校验 `getMapId() != safeMapId` → 退回原地等怪消失分支。

## 9. 补丁：宿主地图扣血（decHP）绕过了死亡入口

伤害层不是唯一的掉血来源：地图 WZ 带 `decHP` 的图（水下世界 2300xxxxx 呼吸伤害 6/跳、
El Nath 寒冰区 10、Orbis 塔 B2）由宿主 `Character.doHurtHp()` → `addHP(-decHP)` 直接扣，
既没有 5% 下限也不经过 `BotContactDamage`。被它扣到 0 的 bot 没有死亡态：

- `BotSM` 死亡总闸读 `isDead()`（false）→ FSM 照常跑，看门狗/换图会把尸体传到别的图；
- `GCMovementDriver` 只把 0 血 bot 摆成 DEAD 姿态冻住并广播（看起来就是"死了但还在被移动"）；
- `BotPotionSim` 的 `isAlive()` 门 → 0 血后永不回血。

**修复**（`BotDeath.adoptIfZeroHp` + 两处轮询点）：

| 位置 | 作用 |
|---|---|
| `BotDeath.adoptIfZeroHp()` | `hp<=0 && !down && 模板bot && 非伴生 && 有地图` → 直接 `markDown()` 进入既有死亡流程，并 `nudgeSoon` 唤醒宏 tick（未观察 grinder 否则等 4–8 分钟） |
| `GCMovementDriver` 0 血分支 | 每 50ms/1s 跑一次的最快认领点（覆盖所有启用移动的 bot） |
| `BotSM.tickRunnable` 门之前 | 兜底（覆盖无 movement 的 bot），保证本 tick 就按死亡处理 |
| `BotDeath.abandon()` | 由 `isDead()` 改为 `isCorpse()`，转换类型时也不会把 0 血未认领的 bot 交出去 |
| `GCTravel.isDead()` | 由 `isDead()` 改为 `isCorpse()`，未认领窗口内也不会把尸体传送走 |

伴生（companion）不认领：它们是真实角色、有自己的生存循环与宿主复活路径（与 `kill()` 一致）。

