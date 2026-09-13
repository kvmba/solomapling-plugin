# Bot 称号（勋章）投放方案（已定稿）

> 状态：**已实现**（阶段 1+2 已落地并通过测试；阶段 3 的可选精修未做）。目标：让 bot **出生即按等级佩戴称号（勋章）**，形成"老玩家有称号、新手无称号"的层次感。实现文件：`soloMapling/ArtificialPlayer/BotMedalSystem/{BotMedalPool,BotMedalAssigner,BotMedal}.java`，测试 `BotMedalAssignerTest`。

## 0. 术语与最终结论

**"称号类装备"在本服 = 勋章（Medal）**，已用宿主源码逐条核实：

| 结论 | 证据 |
|---|---|
| 勋章前缀 `114xxxx`，`isMedal` = `1140000 ≤ id < 1143000` | 宿主 `ItemConstants.isMedal` |
| 佩戴槽 `Me`，编码槽 **`-49`**（`BodyPart.MEDAL=49`） | 宿主 `BodyPart` / `EquipSlot.MEDAL("Me",-49)` |
| 头顶称号发包来源：`addCharInfo` 读装备槽 `(short)-49`，非空即 `writeInt(itemId)` | 宿主 `PacketCreator.addCharInfo` |
| 全库**无**独立 "title" 物品类型 | `grep -rni title` 仅命中 `earnTitleMessage`，非物品 |
| 插件现有代码**没有任何** bot 勋章逻辑 | 唯一命中是调试方法 `ArtificialPlayerCommand.testEqBySlot` |

**佩戴勋章 = 纯库存态**：放进槽 `-49` 即随角色生成包自动广播（新观察者）＋ `equipChanged()` 即时刷新（同图观察者）。**无需新增任何发包代码，无需改宿主**。

### 0.1 资源事实（已实测）

- **本服 GMS083 已汉化**：`gms-server/wz-zh-CN/` 存在，`application.yml` 设 `gms.service.language: zh-CN`；宿主 `WZFiles.getFile()` 优先取语言目录。故 `ItemInformationProvider.getName(114xxxx)` 返回**中文名**。
- `Character.wz/Accessory/` 下共 **153 个** `0114xxxx`（`islot` 全为 `Me`，即全是勋章）。等级分布：**94 个 reqLevel 0**，其余 8/10/13/15/20/…/180。reqJob 仅 5 个非 0（`1142009-1142013`，reqJob=1/2/4/8/16，reqLevel=180）。**无任何勋章带 `cash`、带 `reqPOP`、带 `incPOP`、带宠物亲密度字段。**
- **中文名覆盖率**：153 个中 **7 个无中文名**（`1140000/1140001/1140002/1141000/1141001/1141002/1142188`）——半成品 WZ 数据，按"无中文名=非法道具"剔除。

## 1. 需求 → 决策（用户 6 条约束）

| 约束 | 落实决策 |
|---|---|
| ① 所有 bot 类型都可投放 | 唯一装饰定稿点 `BotDecorate.setBotVariables(...)` 内注入 → **全部 bot 类型**（含 companion、console、GM 测试 bot）统一生效，无按类型白名单 |
| ② 道具属性必须生效 | **勋章属性天然生效**：宿主 `recalcEquipStats` 累加 `EQUIPPED` 中每个 `Equip` 的 STR/DEX/INT/LUK/MHP/MMP/WATK/MATK…，勋章同槽位计入；`equipChanged()` 触发重算。无需改属性代码（见 §5） |
| ③ 道具名必须是中文名 | 复用既有 `BotHelpers.isUsableItem(id)`（zh-CN 下要求名字含汉字）作为**入池合法性门**；池在启动时一次性建好 |
| ④ 人气/宠物亲密度类不投放 | 池内**按名剔除**：`1142003 超人气王勋章`、`1142082 可爱宠物主人勋章`（勋章本就不带人气/亲密度属性，剔除是观感/语义问题） |
| ⑤ bot 必须满足穿戴要求 | 选池时硬过滤 `reqLevel ≤ botLevel` ∧ `reqJob` 兼容 ∧ `reqSTR/DEX/INT/LUK ≤ bot 实际值`（沿宿主 `canWearEquipment` 规则，见 §4.3） |
| ⑥ 上限 50% | 佩戴概率曲线 `p_max = 0.50`，`level<10` 恒 0 |

## 2. 合法性池（Legal Pool）—— 已算好

```
legal = { id ∈ [1140000,1142999) }
      ∧ 在 GMS083 Character.wz/Accessory 真实存在且 islot=Me
      ∧ getName(id) 非空且（zh-CN 下）含汉字        # 约束③
      ∧ 名称不含 人气/宠物/亲密/好友 关键词          # 约束④（剔除 1142003、1142082）
```

**结果：144 枚合法勋章**（153 − 7 无中文名 − 2 人气/宠物）。等级分布：

| reqLevel | 0 | 8 | 10 | 13 | 15 | 20 | 21 | 25 | 30 | 32 | 40 | 46 | 50 | 51 | 62 | 70 | 75 | 100 | 120 | 180 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 枚数 | 92 | 1 | 7 | 2 | 1 | 2 | 1 | 1 | 5 | 1 | 4 | 1 | 5 | 2 | 1 | 7 | 1 | 1 | 3 | 6 |

- **92 枚 lv0** 人人可戴 → 低等级 bot 有充足选择。
- 5 枚 `1142009-1142013` 是**职业专属**（战武神/圣贤者/狙击王/影飞侠/海盗王，reqJob=1/2/4/8/16，lv180）：仅当 bot 基数职业匹配时可选；**reqJob=16（海盗）在本服无对应 bot 职业 → 永久不可选**（死条目，无害）。

## 3. 总体设计

全落插件内（`soloMapling/**`），**零改宿主、零改共享缓存**：

```
A. 勋章池   BotMedalPool      —— 自带 WZ 小扫描建池（144 枚，含 reqLevel/reqJob/名称）
B. 分配策略 BotMedalAssigner  —— 纯函数：等级 → 佩戴概率 + 从"基础/进阶/职业"档选池
C. 应用     BotMedal          —— apply/reroll/force/remove + 开关 + GM 命令
```

**注入点（唯一）**：`BotDecorate.setBotVariables(...)` 的两个重载，紧跟 `BotFame.apply(bot)` 之后、`BotEquipStats.alignToEquipped(bot)` 之前（此时 level/job/gender/基础属性已定稿）。

**重掷点**：`EnvironmentManager` 两处等级覆写（`spawnAttackTestBots`、`setBotsLevelRange`）在 `BotFame.apply` 旁加 `BotMedal.reroll(bot)`，保证称号与最终等级一致。

## 4. 详细设计

### 4.1 勋章池 `BotMedalPool`

**为什么自带扫描**：宿主 `EquipType` 无 MEDAL，`EquipMetadataCache.classify(114xxxx)` 因宿主枚举落 `UNDEFINED` → 勋章**不在** `EquipMetadataCache` 里。故不自造缓存污染，改用**独立小扫描**：

- 新文件 `.../BotMedalSystem/BotMedalPool.java`（+ 可选 `BotMedalPool.yaml` 覆盖）。
- `load()`（启动时调用一次，仿 `GenericEquipPool`）：
  1. 复用 `EquipMetadataCache` 已验证的目录扫描写法：`WZFiles.CHARACTER.getFile()` → `Character.wz/Accessory/*.img.xml`，筛 `0114xxxx` 前缀；
  2. 逐个读 `info`：`reqLevel/reqJob`（`XMLDomMapleData` + `DataTool`，同 `EquipMetadataCache.scanEquipDirectory`）；
  3. 名称门：`BotHelpers.isUsableItem(id)`（约束③，`getName` 走语言目录 → 中文）；
  4. 关键词门：名称不含 `人气/宠物/亲密/好友`（约束④）；
  5. 仅收 `reqLevel≤180` 的真实勋章 → `PoolItem{int id; int reqLevel; int reqJob; String name}`。
- 复杂度：**153 个文件一次性扫描**（远优于 id 区间探测的 `getItemData` 线性扫描），后续全内存。

> 可选 `BotMedalPool.yaml`（阶段 3）：额外黑名单，用于排除个别"GM/敏感主题"勋章（如 `1142070 反外挂勋章`、`1142075 爱国者勋章`）。MVP 默认全收（仅 §2 硬剔除），黑名单留作可配项。

### 4.2 分配策略 `BotMedalAssigner`（纯函数，可单测）

**佩戴概率**（`level` 已定稿）：
```
p(level) = level < 10 ? 0
         : min( P_MAX * (level - 10) / (L_CAP - 10), P_MAX )
P_MAX = 0.50   L_CAP = 130
```
| 等级 | 10 | 30 | 50 | 70 | 100 | 130+ | <10 |
|---|---|---|---|---|---|---|---|
| 概率 | 0% | 8% | 17% | 25% | 38% | **50%** | 0% |

**档位（让高等级 bot 更可能戴"进阶"勋章）**：
- 基础档 = `reqLevel == 0`（92 枚，人人可戴）；
- 进阶档 = `reqLevel > 0`（52 枚，随等级解锁）；
- 进阶命中：`p_adv = clamp((level - 30) / 100, 0.05, 0.70)`；命中→进阶档，否则→基础档；
- 各自档内**按 id 升序偏向**（与 `selectWeightedRandom` 的"偏经典低 id"风格一致），减少每次都抽到同一批新勋章。

**职业门（约束⑤之一）**：`reqJob != 0` 的勋章仅在 `(reqJob & botBaseJobBitmask) != 0` 时可选；`botBaseJobBitmask` 由职业推导（战=1/法=2/弓=4/贼=8；新手=0 不匹配任何职业专属）。

**属性门（约束⑤之二）**：对候选勋章，逐一检查 `reqLevel/reqSTR/reqDEX/reqINT/reqLUK` 是否 ≤ bot 的实际值（沿宿主 `canWearEquipment`）。本服所有勋章 `reqSTR/DEX/INT/LUK=0`、`reqPOP=0`，故此门实际只需 `reqLevel`（但保留通用实现，防未来勋章数据变化）。

**随机源**：`ThreadLocalRandom`（同 `BotDecorateNX`/`BotFame`）。
> 与 `BotMount` 的确定性 cid 哈希不同：坐骑需跨重启稳定（持久伙伴），**勋章是纯外观、随每次装饰重掷即可**，用线程随机更贴合 `BotFame`。若日后要求持久伙伴稳定戴同一枚，再引入 cid 确定性 roll（见 §7）。

### 4.3 应用 `BotMedal`

```java
public final class BotMedal {
    public static final int MIN_LEVEL = 10;
    public static boolean ENABLED = true;                  // 仿 BotDecorateNX.ENABLED

    /** 出生定稿：紧跟 BotFame.apply(bot) 之后调用。 */
    public static void apply(Character bot) {
        if (!ENABLED || bot == null || bot.getMap() == null) return;
        if (bot.getLevel() < MIN_LEVEL) { remove(bot); return; }
        if (ThreadLocalRandom.current().nextDouble() >=
                BotMedalAssigner.wearChance(bot.getLevel())) return;   // 本次不戴
        Integer id = BotMedalAssigner.pick(bot);                        // 合法且可穿
        if (id != null) equip(bot, id);
    }

    /** 等级被覆写后重掷（与 BotFame.apply 同级）。 */
    public static void reroll(Character bot) { remove(bot); apply(bot); }

    public static void equip(Character bot, int medalId) {
        BotCustomization.EquipBot(bot, medalId);   // dst 自动 = -49；已占槽会先卸旧
    }
    public static void remove(Character bot) {
        if (bot.getInventory(InventoryType.EQUIPPED).getItem((short) -49) != null) {
            BotCustomization.UnequipBot(bot, (short) -49);   // 新增 3 行小方法
        }
    }
}
```

- **佩戴**：`BotCustomization.EquipBot(bot, id)` 已按前缀 `114 → BodyPart.MEDAL` 算出 `dst=-49`，且自动卸同槽旧物 → **无需改动**。
- **卸下**：新增 `BotCustomization.UnequipBot(Character, short slot)`（`EQUIPPED.removeSlot(slot)` + `equipChanged()`，~3 行），供 `remove`/`reroll` 使用。
- **可见性**：出生随 `addCharInfo` 的 `-49`；同图观察者经 `equipChanged()` 广播；切图/下线**随角色生成包自动带走**（无需 tick，这点比 `BotMount` 简单）。
- **属性生效**：`equipChanged()` → `updateLocalStats()` → `recalcEquipStats()` 累加勋章属性到 `localstr/localdex/…`，即**勋章加成真正生效**（约束②）。

### 4.4 配置与开关

- 常量集中在 `BotMedal`（`ENABLED`、`MIN_LEVEL`）与 `BotMedalAssigner`（`P_MAX=0.50`、`L_CAP=130`、档位参数）；可选 `BotMedalPool.yaml`（黑名单）。
- GM 命令（挂 `!bot`，仿 `mount`/`decoratenx`）：
  - `!bot medal <cid>` — 显示当前勋章（id/中文名/reqLevel）；
  - `!bot givemedal <cid> [itemId]` — 强制给（缺 id 则随机挑一枚可穿）；
  - `!bot removemedal <cid>` — 摘除；
  - `!bot rerollmedal <cid>` — 重掷。

### 4.5 测试

- **纯逻辑单测** `BotMedalAssignerTest`（无宿依赖，关键）：
  - `wearChance` 随等级单调不减、`≤0.50`、`level<10 == 0`；
  - `pick` 只用构造的假池：返回项 `reqLevel≤level`、`reqJob` 兼容、绝不含非法关键词；
  - 进阶档命中率随等级上升；空池/边界 `level=10`、`level=130` 不抛异常。
- **手动集成**：`!bot givemedal <cid>` + 肉眼确认头顶称号；抽查佩戴比例是否贴近曲线、是否 ≤50%。

## 5. 影响面与性能

- **宿主零改动**；**不改** `EquipMetadataCache` / `EquipType` / 宿主任何文件。
- **插件改动面**：
  1. 新增 `soloMapling/ArtificialPlayer/BotMedalSystem/`：`BotMedalPool` / `BotMedalAssigner` / `BotMedal`（+ 可选 yaml）；
  2. `BotCustomization`：新增 `UnequipBot`（~3 行）；
  3. `BotDecorate.setBotVariables(...)` 两个重载：`BotFame.apply` 后加 `BotMedal.apply(bot)`（各 1 行）；
  4. `EnvironmentManager` 两处等级覆写：`BotFame.apply` 旁加 `BotMedal.reroll(bot)`（各 1 行）；
  5. `ArtificialPlayerCommand`：新增 4 个 `!bot` 子命令；
  6. `SoloMaplingExtension.onLoad`：`BotMedalPool.load()`（1 行，随 `EquipMetadataCache.initialize()`）。
- **性能**：建池 = 启动时 153 文件一次性扫描；运行时 = 装饰期 O(1) 内存查询；**无每-tick 成本、无新增发包**。

## 6. 分阶段

| 阶段 | 内容 | 验收 | 状态 |
|---|---|---|---|
| 1 核心 | `BotMedalPool` + `BotMedalAssigner` + `BotMedal` + `UnequipBot`，接入 `setBotVariables` | 出生 bot 按曲线戴勋章；<10 不戴；上限 50%；仅中文名勋章；能力匹配 | ✅ 已完成 |
| 2 一致性 | 等级覆写重掷；companion 加载钩子；GM 命令（medal/givemedal/removemedal/rerollmedal） | 覆写后称号与等级相符；命令可用 | ✅ 已完成 |
| 3 打磨 | 可选 `BotMedalPool.yaml` 黑名单；可选 cid 确定性（持久伙伴稳定） | 可开关、可调参、无脏勋章 | ⬜ 未做（可选） |

### 6.1 已实现细节

- **池**：`BotMedalPool.load()` 启动时扫描 `Character.wz/Accessory/0114xxxx.img.xml`，读 7 个需求字段（reqLevel/reqJob/reqSTR/reqDEX/reqINT/reqLUK/reqPOP）缓存进 `Medal`；`BotHelpers.isUsableItem` 过中文名门；名称含 `人气/宠物/亲密/好友` 剔除。**未改** `EquipMetadataCache`/宿主。
- **分配**：`BotMedalAssigner.wearChance` 线性到 50%（L_CAP=130，<10 恒 0）；基础档(reqLevel 0)/进阶档(reqLevel>0)，进阶命中率随等级 5%→70%；档内按 id 衰减加权。
- **应用**：`BotMedal.apply/reroll/equip/remove/currentMedalId`；佩戴走 `BotCustomization.EquipBot`（自动 `-49`）；卸下走新增 `BotCustomization.UnequipBot`。
- **注入点**：`BotDecorate.setBotVariables` 两个重载（3 处 `BotMedal.apply`）+ `BotGeneration.loadPersistentBot`（companion）+ `EnvironmentManager` 两处等级覆写 `BotMedal.reroll`。
- **启动加载**：`SoloMaplingExtension.onLoad` 调 `BotMedalPool.load()`。
- **测试**：`BotMedalAssignerTest`（7 tests）锁死曲线单调/≤50%/<10=0、进阶倾斜、纯函数只返回合法池成员、空池安全。全套 **820 tests 全绿**。

## 7. 假设与遗留（已自行决断，如不接受可覆盖）

1. **曲线**：`p_max=0.50`、`L_CAP=130`、`<10=0`、进阶档 `p_adv` 随等级 5%→70%。如需精确分布可给表。
2. **黑名单**：MVP 仅按约束④剔除 2 枚人气/宠物勋章；其余 142 枚全收。是否再排除"GM/敏感主题"勋章（如 `1142070 反外挂勋章`、`1142075 爱国者勋章`）留作可选。
3. **持久伙伴 Companion**：默认每次装饰重掷（**不持久**）；若要求跨上线稳定，改 `BotMedalAssigner` 用 cid 确定性哈希（同 `BotMount`）。
4. **零属性勋章**：本服勋章带 `incSTR/DEX/INT/LUK/ACC/EVA/MHP/…` 但**无 reqSTR/DEX/INT/LUK/reqPOP**，故属性只增不卡穿戴；bot 战斗不依赖这些属性（同 `BotMount` 说明），效果纯展示 + 面板加成。
