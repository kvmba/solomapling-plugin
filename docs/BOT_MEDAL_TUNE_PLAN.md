# Bot 称号（勋章）投放调优 — 阶段 4（已实现）

> 状态：**已实现并测试通过**（全套 1022 tests 绿）。
> 改动文件：`BotMedalSystem/{BotMedalPool,BotMedalAssigner}.java` + 测试
> `BotMedalAssignerTest` / `BotMedalPoolTest`。基线见 `BOT_MEDAL_PLAN.md`。**宿主零改动。**

## 1. 需求
1. 整体佩戴率提到 **~60%**。
2. **价值不高的称号多配发**（低价值约占已发放的 60%）。
3. 剔除 **活动/赛季类**（`timeLimited=1`）与名字带 活动/赛季/`2010`/`遗物大会Top10`/`XX王` 的称号。

## 2. 已核实事实（本服 GMS083 / wz-zh-CN）
- 勋章文件 153 → 现有合法门（有名 + 非 人气/宠物/亲密/好友）150。
- `timeLimited=1` 活动/限时：**59 枚**。
- 本服 bot 等级 `generateBotLevel(tier, 10, 80)` → **10~80，均值≈48**（旧曲线 `L_CAP=130` 在此带内只发挥 ~35%，全服佩戴率仅 ~16%）。
- 旧选择逻辑只有 basic/advanced（按 reqLevel），**无价值概念**；`BotMedalPool` 旧实现**不读 inc***。

## 3. 最终池：**80 枚**（低 31 / 中 34 / 高 15）
入池门（`BotMedalPool`）：
1. 真实 `islot=Me` 勋章 + 中文名（`BotHelpers.isUsableItem`）；
2. 名字不含 `人气/宠物/亲密/好友`；
3. 名字不含 `2010 / 遗物 / 嘉年华 / 热爱冒险岛`（活动/赛季）；
4. WZ 标记 `timeLimited` 存在 → 剔除；
5. 名字（去"勋章"后缀）以 `王` 或 `王者` 结尾 → 剔除（"XX王"浮夸称号）；
   —— `暗黑龙王杀手勋章` 的"王"在 boss 名内、以"杀手"结尾，**保留**。

## 4. 佩戴曲线（整体 ~60%）
`BotMedalAssigner`：`P_MAX 0.50→0.65`，`L_CAP 130→20`（`MIN_LEVEL=10` 不变）。
`p(lv) = min(0.65, 0.65·(lv-10)/10)`：lv10=0，lv20 起 ~65%。

| | 现状 0.50/130 | 新 0.65/20 |
|---|---|---|
| 全服佩戴率 | **15.9%** | **60.9%** |

## 5. 价值分档（低价值占已发放 ~60%）
价值分（`BotMedalPool.valueScore`，由 inc* 折算）：
```
value = (incSTR+incDEX+incINT+incLUK) + (incMHP+incMMP)/20
      + 2*(incPAD+incMAD) + (incACC+incEVA)/4 + (incSpeed+incJump)/3
```
档位：低 `value<10`（31 枚）/ 中 `10–19`（34 枚）/ 高 `≥20`（15 枚）。
选择权重 `低:中:高 = 6:3:1`（`BotMedalAssigner`），档内保留 id 衰减。

**实测**：已发放中 **低 60% / 中 30% / 高 10%**。

## 6. 验收（端到端核算）
- 建池日志应为 `[BotMedalPool] Loaded 80 legal medals`。
- 整体佩戴率 ≈ **61%**；已发放中低价值 ≈ **60%**。
- 无 活动/限时/`XX王` 称号出现在 bot 头上。
- 全套 **1022 tests 绿**。

## 7. 备注 / 可调
- 曲线若要保留"越老越有"坡度，可改 `P_MAX=0.70, L_CAP=30`（全服仍 ~61%，lv20 仅 35%）。
- 低价值占比若要更高，改权重（如 `7:2:1`）。
- 价值分阈值/权重为自拟启发式（纯展示属性，bot 战斗不读勋章属性）。
