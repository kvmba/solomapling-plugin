# Bot Debuff（异常状态）系统（规划）

> 状态：**规划，待实机验证**。目标：让会接触怪的 bot 被怪物异常状态影响，且真人玩家**看得见**。
> 承载方式：**纯插件状态**（复用宿主 `MobSkill`/`Disease`/`PacketCreator` 只做视觉与取值，**不改宿主 GMS083**）。
> 范围：**全量**（STUN/SEDUCE/SEAL/SLOW/WEAKEN/DARKNESS/POISON/CURSE）。

## 1. 现状（已核实的代码事实）

| 事实 | 位置 |
|---|---|
| bot 掉血走独立通道，只扣 HP + 击退，无任何状态效果 | `GCMoveSystem/BotContactDamage` `applyMobHit:162` / `resolveMobHitDamage:208` |
| bot **在** `map.characters` 里（走 `map.addPlayer`），故 mob 技能 AoE 判定会算上它 | `BotGeneration:383/517/534` → `MapleMap.addPlayer:2660` |
| 但 mob 技能/普攻只在怪物 controller 发 `MOVE_LIFE`/`TAKE_DAMAGE` 时触发；bot 被显式排除出 controller | `MoveLifeHandler:95`；`Monster.getNextControllerCandidate`（`!HostHooks.isArtificial`）/ `Monster.aggroSwitchController:2062` |
| 宿主真实 debuff = `MobSkill.applyEffect` → `Character.giveDebuff(Disease)`，只经上述三条客户端路径到达 | `MobSkill.applyEffect:195` / `applyDisease:384` |
| 插件侧**零**引用 `Disease`/`hasDisease`/`giveDebuff` | `grep src/main src/test` 无命中 |
| 宿主可复用 API：`MobSkill.makeChanceResult()/getType()/getDuration()/getX()`、`Disease.getBySkill`、`PacketCreator.giveForeignDebuff/giveForeignSlowDebuff/cancelForeignDebuff/cancelForeignSlowDebuff`、`Monster.hasAnySkill()/getSkills()` | 见各文件 |
| 死亡总闸与"尸体"语义已存在，debuff 需与之对齐 | `BotHealthSystem/BotDeath`；`BotSM.tickRunnable:94` |

**结论**：bot 目前对 debuff 实质免疫（无人消费该状态），且无法靠宿主原链路触达——必须在插件侧新增一条链路。

## 2. 目标语义

| 异常 | 行为效果（插件侧） | 视觉 |
|---|---|---|
| STUN | 完全冻结：不移动、不攻击 | 真人对 bot 的 foreign debuff 包 |
| SEDUCE | 冻结且被拉向来源怪（面向） | 同上 |
| SEAL | 停止攻击，仍可移动 | 同上 |
| SLOW | 移速 × 系数 | `giveForeignSlowDebuff` 专用包 |
| WEAKEN | 输出 × 系数 **且** 受伤 × 系数 | 同 STUN 包 |
| DARKNESS | 挥空概率（伤害行置 0 → 客户端显示 MISS） | 同 STUN 包 |
| POISON | 每 N 秒 `safeAddHP(-dmg)`（走 5% 下限，**不绕过** `BotDeath` 的致命判定） | 同 STUN 包（中毒可见） |
| CURSE | 仅视觉 | 同 STUN 包 |

- **只对有真人在场的图发包并生效**（复用 `GCMovement.isMapObserved` LOD 门），无观测时零开销。
- **仅会接触怪的 bot**（打怪/伴生/PQ）；纯站街/交易类不受影响。

## 3. 架构

新增 `soloMapling/ArtificialPlayer/BotStatusSystem/`：

### 3.1 `BotDebuffState`（每 bot 一份，挂 `BotSM`，类比 `BotDeath`）

```java
final class BotDebuffState {
    private final Map<Disease, Entry> active;   // disease -> {expiresAtMs, MobSkill source}
    // Entry = { long expiresAtMs; MobSkill skill; long nextPoisonTickAtMs; }

    boolean isFrozen();          // STUN || SEDUCE
    boolean blocksAttack();      // FROZEN || SEAL
    double  moveFactor();        // SLOW -> 系数(如 0.6)，否则 1.0
    double  outFactor();         // WEAKEN -> 系数(如 0.5)，否则 1.0
    double  takenFactor();       // WEAKEN -> 受伤放大
    boolean robsHit();           // DARKNESS -> 本次挥空
    void    tick(Character chr, boolean observed); // 过期发 cancel 包并清除；POISON 结算
    void    apply(Character chr, Disease d, MobSkill skill); // 去重 + 写表 + 发 packet
    void    clearAll(Character chr); // 换图/死亡/交易中断时清
}
```

- 挂在 `BotSM`，与 `death` 并列暴露 `status()`。
- `apply(...)` **只取** `Disease` 与 `skill.getX()/getDuration()`，广播 `PacketCreator.giveForeignDebuff(bot.id, [Pair(d, x)], skill)`；SLOW 走 `giveForeignSlowDebuff`。bot 自身 `sendPacket` 被 headless 丢弃，符合既有做法。

### 3.2 `BotDebuffApplier`（检测 + 施加）

- **检测位置复用** `BotContactDamage.tickMobDamage` 里现成的近邻敌对怪扫描（已有 LOD 门、每移动 tick 一次），避免新增扫描。
- 对「有技能（`hasAnySkill()`）」且近邻的怪：遍历 `getSkills()`，取异常类（STUN/SEAL/DARKNESS/WEAKEN/CURSE/POISON/SLOW/SEDUCE），按 `MobSkill.makeChanceResult()` 判命中，按技能 `coolTime` 滚动，命中即 `BotDebuffState.apply`。
- 不调 `MobSkill.applyEffect`（它会调 `Character.giveDebuff` 挂宿主真实 disease，无人消费且会与纯插件状态双份）——只读它的类型/数值。

### 3.3 行为门控（注入点最小化）

| 异常 | 注入点 | 改动 |
|---|---|---|
| STUN/SEDUCE 冻结 | `GCMovementDriver.tick` 早退（仿 `hp<=0` 分支，`BotContactDamage` 之前） | 冻结姿态 + 提前 return |
| SEAL/STUN 禁攻击 | `BotAttackDriver.attack` 开头早退 | 一行 gate |
| SLOW 减速 | `BotMovementProfile.speedMultiplier()` 运行时叠加 `status.moveFactor()` | 不改等级/缓存 key |
| WEAKEN 输出 | `BotAttackDriver.attack` 伤害循环（`:259`）乘 `outFactor()` | 乘子 |
| WEAKEN 受伤 | `BotContactDamage.rollMobDamage` 乘 `takenFactor()` | 乘子 |
| DARKNESS 挥空 | `BotAttackDriver.attack` 伤害行置 0（客户端显示 MISS） | 复用现有 pipeline |
| POISON | `BotDebuffState.tick()` 定时 `safeAddHP(-dmg)` + `updatePartyMemberHP()` | 走 5% 下限 |
| CURSE | 仅视觉 | 无 |

> 所有乘子默认 1.0，未中 debuff 时是零成本直通。

### 3.4 边界与生命周期

- **死亡**：`BotDeath` 期间不重复结算/发包；`carryHome` 后 `clearAll`。
- **换图**：`onMapChange` 清空（沿用宿主的"状态不跨图"语义）。
- **交易中**：`TRADING` 不打断（与 `BotSM` 死亡总闸同约定）。
- **挂载/动作**：与 `BotMount.cancelForAction` 一致——中招动作前先落地。

## 4. 改动清单

| 文件 | 改动 | 量级 |
|---|---|---|
| `BotStatusSystem/BotDebuffState.java` | **新建** 状态表 + 门控查询 + tick | ~120 行 |
| `BotStatusSystem/BotDebuffApplier.java` | **新建** 检测 + 施加 + 视觉 | ~90 行 |
| `BotStatusSystem/BotDebuffTable.java` | **新建** 各 Disease 的系数常量（纯数据，可测） | ~40 行 |
| `BotSM.java` | `status` 字段 + `status()` 访问器 + tick 门（dead 时跳过 tick） | ~12 行 |
| `GCMoveSystem/BotContactDamage.java` | 近邻怪扫描里调 `BotDebuffApplier`；`rollMobDamage` 乘 `takenFactor` | ~15 行 |
| `GCMoveSystem/GCMovementDriver.java` | 冻结分支 + 换图清状态 | ~10 行 |
| `GCMoveSystem/BotMovementProfile.java` | `speedMultiplier()` 叠加 `moveFactor` | ~5 行 |
| `BotAttackSystem/BotAttackDriver.java` | 禁攻击早退 + 输出/挥空乘子 | ~15 行 |
| 测试：`BotDebuffStateTest`、`BotDebuffTableTest`、`BotDebuffApplierTest` | 纯逻辑（不启服） | 测试 |

## 5. 测试计划

`BotDebuffTableTest`
- 各 Disease → (冻结/禁攻击/移速/输出/受伤/挥空) 映射正确；未知/无关 Disease 直通。

`BotDebuffStateTest`
- apply 去重；到期 → cancel 包 + 清除；POISON 定时结算且不越过 5% 下限（致命仍交 `BotDeath`）。
- `clearAll` 覆盖换图/死亡/交易。

`BotDebuffApplierTest`
- 从 mob `getSkills()` 仅选异常类；`prop` 命中判定；未知/无技能怪跳过。

门控集成（纯逻辑）
- STUN → `BotAttackDriver` 早退、`GCMovementDriver` 冻结；SEAL → 仅禁攻击仍可走。
- DARKNESS → 伤害行置 0；WEAKEN → 输出/受伤系数生效。

## 6. 风险

| # | 风险 | 处理 |
|---|---|---|
| 1 | 性能：新增每 tick 检测 | 复用现成 mob 扫描 + 已有 LOD 门；门控查询 O(状态数)，通常 0–2 项 |
| 2 | 与死亡双写 | 死亡期间跳过 tick/发包；`adoptIfZeroHp` 优先级不变 |
| 3 | 视觉不一致（真人看到的包） | 采用宿主同款 `giveForeignDebuff/SlowDebuff`；bot 自身包被丢弃属预期 |
| 4 | 与 `TRADING`/`GCTravel` 冲突 | 沿用 `BotDeath` 已有的例外与 `isCorpse` 口径 |
| 5 | 换图残留 | `onMapChange` / `carryHome` 统一 `clearAll` |

## 7. 阶段

- **Phase 1**：`BotDebuffState` + `BotDebuffApplier` + 视觉 + 到期 cancel；STUN/SEDUCE 冻结、SLOW 减速。
- **Phase 2**：SEAL/WEAKEN/DARKNESS/POISON/CURSE 全部行为效果。
- **Phase 3**：CPQ 手动 debuff 走同一 `BotDebuffState`；清理边界对齐。

> 本次目标：直接做到 Phase 2 完整（全量）。
