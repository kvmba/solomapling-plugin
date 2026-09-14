# Bot 携宠方案（规划 v3 → 已实现）

> 状态：**已实现**（`soloMapling.ArtificialPlayer.BotPetSystem`）。本文件保留为设计说明；实现与本文件的差异见文末「实现记录」。
>
> 目标：让 bot 按实力携带宠物，宠物跟随 bot 移动（含**游泳姿势+物理跟随**），进图、**死亡回城**随行；具备拾取装备的宠物可**替玩家/自己拾取**；**ambient bot 默认不写数据库**（反射直造），**持久化 bot（companion）走库持久化**。

本版依据累计 10 条决策：
1. 所有 bot 都支持携带，唯独**自由市场开店（雇佣商人/个人商店）的 bot 除外**。
2. 曲线与参数由实现方分析决定，合理即可。
3. 宠物要有**随机名字**（按概率）且**等级要低**；携带比例看 **bot 的实力**（等级 + 品级 tier）。
4. 支持宠物**拾取**（取决于宠物是否装了拾取装备）；投放比例由实现方决定。
5. 宠物**跟随 bot**；bot **死亡回城后宠物跟着回去**。
6. **注意游泳区域的表现**。
7. **可以不用 `createPet`、自己制造宠物以绕过数据库**（ambient bot 不写库）。
8. **持久化 bot 也需要支持**（companion）。
9. **真玩家在场时，支持拾物的宠物会替玩家去拾取**。
10. 其余由实现方决定；宠物是付费向内容，投放合理即可。

---

## 1. 现状调研（结论，决定方案的关键事实）

### 1.1 插件侧：零 bot 宠物代码
全库唯一与宠物实体相关的调用是 `DialogueContextResolver.petName(Character c)`，其中 `c` 是**被搭话的玩家**（社交 bot 夸玩家 `{PLAYER_PET}`）。其余 "pet" 命中都是地图名"宠物公园"、掉落注释或 `PetLoot*` IGN。

### 1.2 宿主 BeiDou 宠物系统完整，插件整体复用，**无需改宿主**

| 能力 | 宿主位置 | 可用性 |
|---|---|---|
| 宠物实体 | `org.gms.client.inventory.Pet`（extends `Item`） | public（**但构造器 private**） |
| 宠物现金道具 | `500xxxx`，`ItemConstants.isPet()` | public |
| 角色宠物槽 | `Character.pets[3]`、`addPet`、`getPet(0..2)`、`getPetIndex`、`removePet` | public |
| 生成宠物（**写库**） | `Pet.createPet(itemId[,level,tameness,fullness])` → `INSERT INTO pets` | public |
| 生成宠物（**不写库**） | 私有构造 `Pet(int id, short position, int uniqueid)` + 各 public setter | **需反射** |
| 召唤广播 | `PacketCreator.showPet(chr, pet, false, false)` → `SPAWN_PET` | public |
| **进图自带宠物** | `spawnPlayerMapObject`→`addCharInfo` 自动遍历 `chr.getPets()` | 自动 |
| 宠物移动广播 | `PacketCreator.movePet(cid, petId, slot, fragments)` → `MOVE_PET` | public |
| 宠物移动落点 | `Pet.updatePosition(...)` / `setPos/setFh/setStance` | public |
| 参考实现 | `SpawnPetProcessor.processSpawnPet` | 源码参考 |
| 宠物饥饿 | `World.registerPetHunger`（会 `saveToDb`）→ **不注册即无饥饿** | 宿主驱动 |
| **宠物拾取判定** | `PetLootHandler`：需 `isEquippedItemPouch(idx)`（拾物）/ `isEquippedMesoMagnet(idx)`（拾金币） | 源码 |
| 宠物装备槽 | `ItemConstants.PET_EQUIP_SLOTS.get(i)`：`equip/nameTag/chatBalloon/mesoMagnet/itemPouch/itemIgnore` | public |
| 宠物装备道具 | `1812000 磁铁`、`1812001 拾物`、`1812007 忽略` | `ItemId` |
| 引擎拾取入口 | `Character.pickupItem(MapObject ob, int petIndex)` | public |
| 泳图判定 | `MapleMap.isSwim()` / `setSwim`（由 WZ `swim` 决定） | public |
| 宠物升级曲线 | `ExpTable.getTamenessNeededForLevel` | public |

### 1.3 关键约束 / 陷阱

1. **宠物必须在 `CASH` 库存有 `500xxxx` 道具** —— 这是**走宿主正常流程**时的要求。若走内存直造（见 §3.4），可不要该道具，但要注意：`unEquipPet`/`runFullnessSchedule` 会 `saveToDb`，**必须绕开**。
2. **`Item(id, pos, qty, petid)` 构造会调 `Pet.loadFromDb` 读库** —— 想不写库，就别用带 `petid` 的 `Item` 构造；直接用反射造 `Pet` 再 `chr.addPet(pet)`。
3. **`Pet` 构造器是 private** —— 需反射（插件已有反射先例：`SingleMoveCommand`）。
4. **宠物装备（`1812xxx`）不能走 `BotCustomization.EquipBot`** —— 它的 `getEquipSlotType` 对 1812 前缀返回 0，会错槽。需要新的 `equipPetItem` 直接写 `PET_EQUIP_SLOTS` 槽位。
5. **泳图 `findBelow` 会返回海床/很远的地板或 null** —— 宠物贴地会"瞬移下沉"。泳图必须跳过贴地。
6. **`changeMap` 后宠物 pos 不会自动更新** —— 新图观察者收到 `spawnPlayerMapObject`（带 `getPets()`），但宠物坐标还是旧的 → 必须重新 snap + 重播。
7. **FM 开店发生在生成之后** —— 授予时无法预知类型，需在开店时回收（§3.7）。

### 1.4 插件内部可直接复用
- 资源：`PluginResources`（`override` → 源码树 → classpath）+ YAML（同 `NXItemPool`）。
- 调度：`ExecutorServiceManager.getScheduledExecutorService()`；`runAsync`。
- 观察者 LOD：`ObserverTracker.isActiveMap(int)` / `GCMovement.isMapObserved(int)`。
- 生成链路：`BotDecorate.setBotVariables(...)`（等级/职业/装备定稿后返回）；`BotGeneration.createBotOn(...)`。
- 持久化：`BotGeneration.loadPersistentBot` / `saveAndRemovePersistentBot`；`CompanionRoster.isCompanion(id)`。
- 移除链路：`BotGeneration.removeBotFromServer(...)`。
- 拾取：`BotClientBinding.withBoundPlayer(chr, () -> chr.pickupItem(ob, idx))`；`DropCommands.botCanLoot`。
- 品级：`Character.getTier()`（`BotTier` S/A/B/C/D）。
- 泳图：bot 物理引擎已有 `tickSwimming`/`applySwimMotion`（参考点，宠物不需要物理）。

### 1.5 需要排除的 bot（决策 1）
- **FM 开店 bot**：`ArtificialFreeMarket.createBotShopAtLocation` → `BotPlayerStorePermit(fakechar)` → `fakechar.setPlayerShop(ps)`（并建 `HiredMerchantArtificial`）。这是"自由市场雇佣商人/个人商店"的唯一标记。
- 纯货架角色（`spawnHiredMerchantStore` 的 `Character.getDefault`）不是 bot（无 `BotSM`），天然无宠物。

---

## 2. 总体设计

全部落在**插件**内，不新增宿主依赖：

```
A 宠物池      BotPetPool          宠物 itemId / 名字池 / 拾取装备池（YAML + 可选 WZ 补全）
B 分配策略    BotPetAssigner      实力(等级+tier) → (携带概率, 数量1..3, 宠物等级, 是否命名, 是否拾取)
C 生命周期    BotPetFactory       造宠物：默认【反射内存直造·不写库】；companion【走库持久化】
             BotPetController     授予 / 召唤 / 生成广播 / 回收（FM 开店）/ 清理
             BotPetFollower      跟随移动 + 泳图适配 + 宠物拾取 + 行为/说话驱动
             BotPetNames          随机宠物名
             BotPetGear           宠物拾取/名签装备（直写 PET_EQUIP_SLOTS）
             PetInteractionTable  读取 WZ 的宠物行为表（prob / 等级段 / act）
             PetCommandInterpreter 纯选择器：按 prob 加权抽一条可用行为
```

触发点：
- **授予+召唤**：`BotGeneration.createBotOn(...)` 里 `setBotVariables(...)` 之后、`playSpawnChoreography` 之前。
- **持久化 bot**：`BotGeneration.loadPersistentBot(...)` 加载成功后（幂等恢复，§3.9）。
- **回收**：`ArtificialFreeMarket.BotPlayerStorePermit(...)` 打开商店时。
- **跟随/拾取**：一个全局定时任务（`~250ms`），仿 `GrindTickRegistry`。
- **清理**：`BotGeneration.removeBotFromServer(...)`。

---

## 3. 详细设计

### 3.1 宠物池 `BotPetPool`
- 文件：`src/main/java/soloMapling/ArtificialPlayer/BotPetSystem/BotPetPool.java` + `BotPetPool.yaml`。
- YAML：
  ```yaml
  pets:
    - 5000000   # 蜗牛
    - 5000001
    - 5000007
    # ... 可从 WZ 的 Pet/、Cash/ 用 ItemConstants.isPet 自动补全
  pickupEquips:
    item_pouch: 1812001
    meso_magnet: 1812000
  ```
- 加载：`PluginResources`；缺省时可选从 WZ 枚举 `isPet` 的道具自动补全（仿 `NXItemPool.loadCashItemsFromCache`）。

### 3.2 分配策略 `BotPetAssigner`（纯函数，可单测）

输入：`level`、`BotTier tier`、`boolean persistent`、`Random`；输出 `List<PetSpec>`（0..3），
`PetSpec = {itemId, petLevel, named, pickupItem, pickupMeso}`。

**携带概率（看实力，上限 30%）**：
```
strength = 0.75 * (level / L_CAP) + 0.25 * tierScore      // tierScore: D0 C.25 B.5 A.75 S1
p(>=1)   = clamp(0.30 * strength^1.4, 0, 0.30)
level < 10 → 0（新手岛/初学者不带宠物）
L_CAP = 120
```
- 等级为 75% 权重、tier 为 25% 权重，体现"实力"。

**数量分布**（仅"有宠物"时抽样；实力越高越向 2–3 只倾斜）—— 权重 `[w1,w2,w3]`：

| 实力 strength | 1 只 | 2 只 | 3 只 |
|---|---|---|---|
| < 0.25 | 100% | 0% | 0% |
| 0.25–0.5 | 80% | 20% | 0% |
| 0.5–0.75 | 60% | 32% | 8% |
| ≥ 0.75 | 45% | 37% | 18% |

- 3 只封顶（`Character.pets[3]`）；种类**无放回**抽取。

**宠物等级（决策 3：低等级）**：
- `petLevel = 1 + floor(strength * 4)`（1–5）。即便 150 级 bot，宠物也只 1–5 级。

**随机概率命名（决策 3）**：
- `named = rng < NAMED_CHANCE`（默认 0.70）；命中则 `BotPetNames.random()`，否则留默认名。

### 3.3 随机名字 `BotPetNames`
- 新增 `BotPetNamePool.yaml`（或复用 `FMShopDescGen.getRandomCharacterIGN` 的 IGN 池思路）。
- 只在 `named` 命中时 `pet.setName(...)`。

### 3.4 造宠物：**默认不写库**（决策 7）`BotPetFactory`

**两条路径**，由 `persistent` 决定：

#### (A) Ambient bot（模板克隆，id ≥ 20000，从不 `saveCharToDB`）—— **反射内存直造，零 DB 写**
```java
// 1) 反射调用私有构造器，得到一个不属于任何 DB 行的 Pet
Constructor<Pet> c = Pet.class.getDeclaredConstructor(int.class, short.class, int.class);
c.setAccessible(true);
Pet pet = c.newInstance(itemId, (short) 0, uniqueId);   // uniqueId 由插件自增（见下）
// 2) 各字段用 public setter
pet.setName(name);              // 命中命名才设
pet.setLevel((byte) petLevel);  // 1..5
pet.setTameness(0);
pet.setFullness(100);           // 满食；且我们不注册 hunger → 永不掉食/消失
pet.setSummoned(true);
pet.setPos(...); pet.setFh(...); pet.setStance(0);      // 召唤时定位
// 3) 只进内存宠物槽，不进 CASH 库存、不 saveToDb
chr.addPet(pet);
```
- **uniqueId 生成**：用插件私有 `AtomicInteger`，从高位起（如 `1_000_000_000`）递增，避开 `CashIdGenerator` 的真实宠物 id，避免与真人/持久 bot 冲突，也免去 `freeCashId`。
- **不写 `pets` 表** → `removeBotFromServer` 时**无需清理 DB**（决策 7 的直接收益）。
- **进内存即可**：`addPet` 填 `pets[]`；`spawnPlayerMapObject.addCharInfo`、`showPet`、`movePet` 都只读 `pets[]`，**不依赖 CASH 道具**。
- **绝不调用**：`Pet.saveToDb`、`Pet.deleteFromDb`、`chr.unEquipPet`、`World.registerPetHunger`（这些都会碰库或需要 CASH 道具）。移除用 `chr.removePet(pet, true)` + 广播 `showPet(remove=true)`。

#### (B) 持久化 bot（companion，会 `saveCharToDB`）—— **走宿主正常持久化**
- 用 `Pet.createPet(itemId, level, tameness, fullness)` **写库** + `Item(..., petid)` 进 `CASH` + `pet.saveToDb()`；
- 这样宠物随 companion 的 `saveCharToDB(true)` 一起持久化，**重启后仍在**（满足决策 8）。
- **幂等**：加载 companion 时若 `chr.getPets()` 已非空，**跳过授予**（见 §3.9）。

> 结论：ambient bot 全内存、零库写；只有真实持久化的 companion 才写库。这既回答了"绕过数据库"，又满足"持久化 bot 也支持"。

### 3.5 授予 + 召唤 `BotPetController`

```
grantPets(bot):                                     // 非 FM 开店 bot 才授
  specs = BotPetAssigner.assign(level, tier, persistent, rng)
  for spec in specs:
    Pet pet = persistent
        ? BotPetFactory.createPersistent(bot, spec)     // createPet + CASH + DB
        : BotPetFactory.createInMemory(bot, spec)       // 反射直造，零 DB
    if pet != null: bot.addPet(pet)
  summonPets(bot)

summonPets(bot):
  for idx, pet in bot.getPets*():
    placeAtBot(bot, pet, idx)                        // 见下（含泳图适配）
    pet.setSummoned(true)
    if persistent: pet.saveToDb()
    bot.loadPetExcludedItems(pet.getUniqueId())      // persistent 才需要；ambient 为空跳过
    bot.getMap().broadcastMessage(bot, PacketCreator.showPet(bot, pet, false, false), true)
    maybeEquipPetGear(bot, idx, spec)                // 名签 / 拾物 / 磁铁
  bot.sendPacket(PacketCreator.petStatUpdate(bot))   // 可选
```

**`placeAtBot`（含泳图，决策 6）**：
```java
Point p = bot.getPosition(); p.y -= 12;
pet.setPos(p);
pet.setStance(0);
if (map.isSwim()) {
    pet.setFh(0);                 // 泳图不贴地；客户端自行让宠物浮游
} else {
    Foothold fh = map.getFootholds().findBelow(p);
    pet.setFh(fh == null ? 0 : fh.getId());   // 防 null
}
```

**宠物装备** `maybeEquipPetGear`（决策 4）：`equipPetItem(bot, idx, itemId, slot)`：造干净 `Equip` → `setPosition(slot)` → 写 `EQUIPPED` 库存 → `bot.equipChanged()`。槽位取 `ItemConstants.PET_EQUIP_SLOTS.get(idx)` 的 `itemPouch()/mesoMagnet()/nameTag()`（`-122/-133/-141` 等）。
- 投放比例（可配）：拾物 `~60%`、磁铁 `~30%`、名签 `~70%`，三者独立抽样，**严格由装备决定能力**。

### 3.6 跟随移动 `BotPetFollower`（核心，含泳图）

全局定时驱动，`TICK_MS ≈ 250ms`：
```
onTick():
  for botId in TRACKED:                       // 仅有宠物的 bot
    chr = SoloMaplingUtilities.getChr(botId)  // 用玩家存储解析（授予早于 BotSM 包装）
    if chr == null || chr.getMap() == null || chr.getNoPets() == 0: forget(botId); continue

    if !ObserverTracker.isActiveMap(chr.getMapId()): continue      // LOD

    // 跟随：身后错位；泳图在 bot 上方滑行
    for idx, pet in chr.getPets*():
      if map.isSwim(): followSwim(chr, pet, idx)   // 朝 (x, y-14*(idx+1)) 定速滑行，fh=0，stance 12/13
      else:            followLand(chr, pet, idx)   // 身后 40/80/120px，findBelow 贴地，stance WALK/STAND
```

**切图 / 死亡回城**：**无插件代码**。宿主 `MapleMap.addPlayer` 每次进图都会对 `getPets()` 逐只 `setPos(getGroundBelow(...))` 并 `showPet`；其它观察者经 `spawnPlayerMapObject`（含 `getPets()`）收到宠物。所以宠物天然随 bot 过传送门 / 传送 / 死亡回城。**注意：不要再额外发 showPet，否则客户端重复生成宠物。**

**泳图表现（决策 6）**：
- 泳图**跳过 `findBelow` 贴地**（海床/空列会返回错误地板或 null，导致宠物瞬移下沉）。
- 宠物位置 = bot 位置上方错位；`fh = 0`；发 `MOVE_PET` 且带速度，**stance = 12/13（游泳姿势）** → "游泳姿势 + 物理跟随"。
- 真实客户端里宠物本来就会随主人浮游/游泳，服务端只需给位置，不需宠物物理。

**宠物移动包**（推荐，复用宿主序列化）：
```java
List<LifeMovementFragment> moves = List.of(
    new AbsoluteLifeMovement(0/*NORMAL*/, target, TICK_MS, 0/*STAND*/) {{
        setPixelsPerSecond(new Point(0, 0));
        setFh(fh);
    }});
Packet p = PacketCreator.movePet(chr.getId(), pet.getUniqueId(), (byte) idx, moves);
chr.getMap().broadcastMessage(chr, p, false);
```
（备用：手搓单段字节，同 bot 自身 `sendMovementPacket` 布局，走 `movePacket`。）

**死亡仍在跟（决策 5）**：bot 死亡时 `chr` 不动，宠物停在旁边；`carryHome()` 触发 `changeMap` → 命中 (a) → 宠物自动跟到回城后的地图。**无需为死亡单独写逻辑**。

### 3.7 宠物拾取 `petTryLoot`（决策 4 + 9）

宿主拾取由客户端包驱动（`PetLootHandler`），bot 无客户端，由插件代驱动：
- **资格**：`chr.isEquippedItemPouch(idx)`（拾物）/ `chr.isEquippedMesoMagnet(idx)`（拾金币）—— 没装对应装备就**不拾取**（严格符合决策 4）。
- **触发**：扫描宠物附近（`pet.getPos()` 半径内）的 `MapItem`。
- **归属规则**：复用 `DropCommands.botCanLoot(chr, mapItem)`（自己掉落 + 自由拾取 + 过保护期的）。**不抢**受保护的他人掉落。
- **替玩家拾取（决策 9）**：
  - 当**真人玩家**在场且其掉落已进入自由拾取窗口、或宠物就在其附近时，**支持拾物的宠物会主动去拾取**（作为该 bot 的宠物替主人/替附近玩家完成拾取动作）。
  - 解释与边界：受 `canBePickedBy` 保护的掉落**不会**被抢；"替代玩家拾取"= 由宠物作为主动拾取者完成动作，而非绕过归属。**此语义是本版推断，见 §5 待确认**。
- **执行**：`BotClientBinding.withBoundPlayer(chr, () -> chr.pickupItem(mapItem, idx))` —— 走引擎权威路径。
- **节流**：每 tick 每宠最多 `N` 次；金币仅在装了磁铁时拾取。
- **与现有拾取的取舍**：装了拾取装备的宠物承担拾取（视觉更像真玩家），未装装备的 bot 仍可走原 `DropCommands.botLoot` 直接拾取（保持原行为不回退）。

### 3.8 客户端一致性

| 场景 | 覆盖方式 |
|---|---|
| bot 进图自带宠物 | `spawnPlayerMapObject.addCharInfo` 自动遍历 `getPets()` |
| 已在图观察者看到召唤 | 召唤时补 `showPet` |
| 宠物移动（含泳图） | `MOVE_PET` 包 |
| 切图 / 死亡回城 | (a) 分支先 snap + 重播，再由 `MOVE_PET` 跟上 |
| 名签 / 聊天气球 | `hasPetNameTag/hasPetChatballoon`（装备槽 `-121/-131/-139`、`-129/-132/-140`） |
| 宠物装备外观 | `getPetEquipItemId` 靠 `PET_EQUIP_SLOTS.get(i).equip()` |

### 3.9 持久化与清理

| bot 类别 | 宠物来源 | DB 写入 | 清理 |
|---|---|---|---|
| Ambient（模板，id≥20000） | 反射内存直造 | **无** | `removeBotFromServer` → `removePet` + 广播移除（无 DB 行可清） |
| Companion（持久） | `createPet` + CASH + `saveToDb` | 有（随 `saveCharToDB`） | 随 companion 生命周期；不随下线删除 |
| FM 开店 bot | 授予时先给，开店时回收 | 同 ambient | `removePets`（§3.10） |

- **Companion 幂等恢复（决策 8）**：`loadPersistentBot` 成功后，若 `chr.getPets()` 已非空 → 跳过授予；若为空且满足实力曲线 → 授予。这样宠物随 companion 持久保存，重启不丢、也不叠加。
  （可选增强：用 `bot_pets` 映射表记录 companion 与 petid 的归属，便于集中管理；MVP 先靠 `pets` 行 + `summoned` 标志恢复。）

### 3.10 FM 开店回收（决策 1）

`ArtificialFreeMarket.BotPlayerStorePermit(fakechar)` 打开商店后插一行：
```
BotPetController.removePets(fakechar)
```
- `removePets`：`removePet` + 广播 `showPet(remove=true)` + 卸载宠物装备；persistent 的删 `pets` 行 + `freeCashId`，ambient 的直接弃用。
- 同步迁移：若该 bot 此前由（非开店）类型转成开店，同样回收。

### 3.11 配置与开关

- `BotPet.yaml`（`PluginResources` 解析）：
  ```yaml
  enabled: true
  curve:  { p_max: 0.30, level_cap: 120, exponent: 1.4, tier_weight: 0.25, min_level: 10 }
  counts: [ {sMax: 0.25, w: [100,0,0]}, {sMax: 0.50, w: [80,20,0]},
            {sMax: 0.75, w: [60,32,8]}, {sMax: 2.0, w: [45,37,18]} ]
  pet_level: { base: 1, per_strength: 4, max: 5 }
  naming:  { chance: 0.70 }
  gear:    { item_pouch: {id: 1812001, chance: 0.60},
             meso_magnet: {id: 1812000, chance: 0.30} }
  follow:  { tick_ms: 200, eps_px: 25, speed: 200.0, teleport_dist_px: 160, swim_offset: 14 }
  pickup:  { max_per_tick: 1, range: 120, cooldown_ms: 1000 }
  speak:   { chance: 0.10, min_interval_ms: 8000, max_interval_ms: 25000 }
  persist: { companions: true }     # 持久化 bot 是否带宠物
  ```
- FM 开店回收是**无条件**的（决策 1），不设开关。
- MMC/GM：`botpet status|enable|disable|reload|grant|clear`（仿 `decoratenx`）。
- 全局 `ENABLED` 静态开关。

### 3.12 测试

- **纯逻辑单测**：`BotPetAssignerTest` —— 概率随实力单调不减且 ≤30%；数量 ∈[0,3] 且右偏；无放回；宠物等级 ∈[1,5]；命名概率区间。
- **纯函数单测**：`petFollowTarget`（陆图偏移/泳图偏移/死区）；`AbsoluteLifeMovement` 宠物字节布局。
- **工厂单测**：反射造 `Pet` 能拿到正确 itemId/level/uniqueId，且**不触发任何 `pets` 表写**（对内联 SQL 断言或 mock）。
- **手动集成**：GM `spawn` + `botpet status`；观察带宠比例、跟随、**泳图浮游**、切图/死亡回城、替玩家拾取、FM 开店无宠、companion 重启后宠物仍在。

---

## 4. 影响面与性能

- **宿主零改动**（不新增 `extension-api`）；反射仅用于 `Pet` 私有构造器（同模块，安全）。
- 新增插件文件 + 三处小改：`BotGeneration.createBotOn`（授予）、`BotGeneration.loadPersistentBot`（companion 幂等授予）、`ArtificialFreeMarket.BotPlayerStorePermit`（回收）、`BotGeneration.removeBotFromServer`（清理）。
- **性能**：跟随驱动 `O(带宠 bot 数)`；LOD 跳过 + 死区抑制，仅位移超阈值才发一个 `MOVE_PET`；拾取每 tick 有上限；ambient 宠物零 DB 写。
- **风险点**：无真实客户端时"召唤/移动/泳图"包能否正确渲染（预期可以，帧与宿主同源）——**阶段 2 首要验证**。

---

## 5. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| 1 授予 | `BotPetPool` + `BotPetAssigner` + `BotPetFactory`（反射/持久双路径）+ `BotPetController` | 进图带宠；比例随实力、≤30%、≤3 只；宠物低等级+随机名；ambient 零 DB 写 |
| 2 跟随 | `BotPetFollower` + 泳图适配 + 切图/死亡回城联动 | 平滑跟随；**泳图浮游正常**；切图/回城随行 |
| 3 拾取 | `petTryLoot` + `BotPetGear` | 有拾物装备的宠物会捡物/捡金币（含替玩家），其余不捡 |
| 4 打磨 | companion 持久化 + FM 开店回收 + MMC `botpet` | companion 重启宠物仍在；FM 开店无宠；可开关 |

---

## 6. 开放问题（实现前确认）

1. **"替玩家拾取"的确切语义（决策 9）**：支持拾物的宠物作为**主动拾取者**，在合法归属（自己/自由拾取）范围内**替玩家完成拾取动作**；**不抢**受保护的他人掉落。✅ 已按此实现。
2. **泳图宠物姿态**：宠物使用**游泳姿势（stance 12/13）+ 物理滑行**，不只是位置。✅ 已按此实现。
3. **曲线/比例数值**：30% 上限、宠物 1–5 级、命名 70%、拾物 60%/磁铁 30%。✅ 已按此实现。

---

## 7. 实现记录（as built）

全部落在插件内，宿主**零改动**。新增包 `soloMapling.ArtificialPlayer.BotPetSystem`：

| 文件 | 职责 |
|---|---|
| `BotPetConfig` (+`.yaml`) | 配置加载（`PluginResources` 解析，缺省回退默认） |
| `BotPetPool` (+`.yaml`) | 宠物 id 池，`ItemConstants.isPet` 校验，无放回抽取 |
| `BotPetNames` (+`.yaml`) | 随机宠物名（≤7 字符） |
| `BotPetAssigner` / `PetSpec` | **纯策略**：实力 → 携带概率 / 数量 / 宠物等级 / 命名 / 拾取装备 |
| `BotPetFactory` | 造宠双路径：反射内存直造（ambient）/ `createPet`+CASH 持久化（companion） |
| `BotPetGear` | 宠物装备（拾物/磁铁/名签）直写 `PET_EQUIP_SLOTS` |
| `BotPetController` | 授予 / 召唤 / 回收（切图重定位由宿主完成，见下） |
| `BotPetFollower` | 全局定时驱动：跟随 + 泳图滑行 + 宠物拾取 + 宠物行为/说话 |
| `BotPetSystem` | 门面：bootstrap / grant / remove / reload / enabled 开关 |
| `PetInteractionTable` | 读 `Item.wz/Pet/<id>.img/interact`：每条行为的 `prob`（触发%）/ 等级段 `l0`–`l1` / 序号 |
| `PetCommandInterpreter` | 纯选择器：按等级段过滤 + `prob` 加权抽一条行为 |

命令：`!botpet status|enable|disable|reload|grant <botId>|clear <botId>`。

集成点（4 处小改）：
- `BotGeneration.createBotOn` → 装饰完成后 `BotPetSystem.grant(bot)`。
- `BotGeneration.removeBotFromServer` → `remove`（仅 ambient）+ `onBotRemoved`。
- `HostCompanionRuntimeAdapter.attachAndStart` → companion 上线后 `grant`（幂等）。
- `ArtificialFreeMarket.BotPlayerStorePermit` → 开店时 `remove`（决策 1）。
- `SoloMaplingExtension` → `onServerReady` 时 `bootstrap`、`onUnload` 时 `shutdown`、注册 `!botpet`。

与规划稿的差异 / 落地细节：
1. **泳图跟随**：宠物按 `follow.speed` px/s 朝 bot 上方错位点逐 tick 逼近（带 dead-zone + 最大步长），`MOVE_PET` 带速度并置 SWIM 姿态 12/13 —— 即"游泳姿势 + 物理跟随"。
2. **陆地图**：宠物停在 bot 身后（40/80/120px），`findBelow` 贴地（null 安全），stance 按移动方向 WALK/STAND。
3. **宠物名签**：v83 只有一个名牌道具 `1822000`；各宠 index 写入各自的 `PET_EQUIP_SLOTS[i].nameTag()` 槽（客户端按槽判 `hasPetNameTag`）。
4. **宠物池排除 5000028/5000047**（龙/机器人蛋），避免穿上渲染成蛋。
5. **ambient 宠物 id** 取 `1_500_000_000` 起的插件私有区间，避开 `CashIdGenerator`（<777,000,000），无需 `freeCashId`。
6. **companion 下线不删宠**：`removePets` 对 companion 直接返回，其宠物随 `saveCharToDB` 持久化；上线 `grantForBot` 幂等（`getPets()` 非空则跳过），重启不丢也不叠加。
7. **移除顺序**：`removePets` 先清装备槽 → 广播移除 → `removePet` 清宠物槽 → `petStatUpdate`；`PetLootHandler` 在宠物离开后自然失效（`getPetIndex` 返回 -1）。
8. **配置**：`BotPetConfig.yaml` / `BotPetPool.yaml` / `BotPetNames.yaml`，均可放 `data/solomapling/override/...` 覆盖。
9. **切图 / 死亡回城无需插件代码**：宿主的 `MapleMap.addPlayer` 在每次进图时已对 `getPets()` 逐只 `setPos(getGroundBelow(...))` 并 `showPet`（发给 bot 自己的 client，headless 侧为空操作），而其它观察者通过 `spawnPlayerMapObject`（`getPets()` 自动带上）收到宠物。因此宠物天然随 bot 过传送门 / 传送 / 死亡回城，follower 只需在"已在图"时维持跟随，**不做任何地图变化处理**（避免与宿主重复 showPet 导致客户端重复生成）。
10. **宿主无"非落库"宠物工厂**：`Pet` 构造器私有，`createPet`/`loadFromDb` 都写/读库；`extension-api` 亦无宠物 API。故 ambient 的内存直造是**唯一**不落库路径，`BotPetFactoryTest` 证明其可在无 DB 环境构造出合法 `Pet`。
11. **宠物行为/说话（WZ 驱动）**：每种宠物在 `Item.wz/Pet/<id>.img/interact` 有自己的行为菜单——每条行为的 `prob`（=**服从**触发%）、等级段 `l0`–`l1`、以及 `success`/`fail` 两套 `act`（动作）。台词在 `String.wz/PetDialog.img/<id>/<key>`（**有 zh-CN 本地化**）。插件按 petLevel 过滤可用行为、等概率抽一条，再按 `prob` 掷"服从/不服从"，用宿主 `PacketCreator.commandResponse(cid, idx, talk=!obey, commandIndex, balloon)` 触发；**动画与台词都由客户端从本地 WZ 解析**——与宿主自己的 `PetCommandHandler` 同机制。节奏：每 pet 冷却 `speak.min/max_interval_ms`，每次触发概率 `speak.chance`（默认 0.10）。**与宿主 `PetCommandHandler` 完全同一条路径**，因此 bot 宠物在被"触发"时的表现与真人指令宠物一致；`hasPetChatballoon` 只影响气泡装饰（v83 的宠物名牌/Quote Ring 数据有限，随宠物装备槽如实上报）。

**审核修正（本轮）**：移除早期版本的多余地图变化重定位（宿主已处理，避免重复 showPet）；follower 改为只遍历"有宠物的 bot"（`TRACKED` 集合）并按 `SoloMaplingUtilities.getChr` 解析（授予发生在 BotSM 包装之前）；`grantForBot` 对"已有宠物"的幂等分支补 `track`（companion 重载后仍能跟随）；`createPersistent` 在 CASH 满时清理孤儿 `pets` 行；`BotPetPool` 兼容 yamlbeans 把裸数字读成字符串。**第二轮复审**：`BotPetFollower.start` 增加 `rescan()`——否则 `!botpet reload` 清空 `TRACKED` 后，已有宠物的 bot 不再被跟随；`removePets` 增加无宠快速返回；`!botpet clear` 回显真实宠物数与 companion 说明。**第三轮完整复审**：修复 **宠物名 NPE**——`ByteBufOutPacket.writeString` 对 null 无保护，而 `addPetInfo` 会写 `pet.getName()`；未命名宠物原先 `name==null`，会让 `showPet` / `spawnPlayerMapObject` NPE（整个授予失败）。现 `BotPetFactory` 保证名字**永不为 null**（未命名用占位名）；清理死代码：`BotPetAssigner` 去掉无用的 `names` 参数与 `BotPetNamesProvider` 接口、`BotPetPool` 去掉未用的 `drawDistinct`、`hasAnyPet` 降为私有、删除未使用的 `playerNearbyRange` 配置。**第四轮完整复审**：`createPersistent` 在 `loadFromDb` 失败时清理刚建的 `pets` 行（孤儿）；follower 改为**逐 bot 隔离异常**（对齐 `GrindTickRegistry` 的 per-participant 约定：单个 bot 抛错不再中断整轮 tick）；`!botpet` 帮助文本与实现对齐。**第五轮完整复审**：修正**宠物槽位（slot）语义**——`MOVE_PET` / `PET_COMMAND` / 装备槽 / 名签槽都以**宠物数组下标**为键（宿主从 `getPetIndex` 取），此前 follow 循环用"跳过 null 不加一"的计数器，一旦 `pets[]` 出现空洞（宿主 `SpawnPetProcessor` 在无多宠技能时调 `unEquipPet(pet0, false)` 会留下 `[0]=null, [1]=pet`）就会把后排宠物错位到 0 号槽；现改为按**数组下标**遍历（与 `loot()` 一致），授予时用 `getPetIndex(pet)` 读取引擎实际分配的槽位（对齐 `SpawnPetProcessor`），不再假设计数器。**第六轮完整复审**：修复**宠物装备不刷新角色外观**——宠物装备（名牌/拾物/磁铁）随**角色外观包**下发（body part 14/21/22/23，见 `addCharEquips`），观察者只有经 `equipChanged()` 广播才会收到；宿主的 `equip/unequip` 路径都以 `equipChanged()` 收尾，而 `BotPetGear.equip` / `clearPetGear` 原先只改物品不改外观，导致名牌/装备在别人客户端不显示。现授予后与清除后各补一次 `equipChanged()`（每次授予只调一次，不是每只宠物一次）。**第七轮完整复审**：`equipChanged()` 会向 `getMap()` 广播且**不判空**，而 `Character` 初始 `map==null`（`getMap()` 在放置前为空），故外观刷新统一走 `refreshLook(bot)`——map 为空时跳过广播，避免在地图未就绪的 bot 上 NPE。**第八轮（两个可见 bug）**：①**原地踏步**——宠物姿态**不是角色姿态**。官方宠物姿态字节 = (动作序号<<1)|朝向，序号顺序 STAND,MOVE,JUMP,ALERT,PRONE,FLY,HANG，即 STAND=0/1、MOVE=2/3、FLY=10/11。此前站立误用角色 `STAND_*=4/5`（对宠物是非法动作→客户端循环上一个动画=原地走），走路用的 2/3 恰好是宠物 MOVE 所以正常。两处独立事实印证：宿主 `SpawnPetProcessor` 召唤用 `setStance(0)` 且宠物静止站立（→0=STAND），旧代码走 2/3 显示正常（→2/3=MOVE）。**明确不采信 maplestory-wasm/Journey**（它把前两个反了：MOVE=0/1、STAND=2/3，与官方不符）。**（该轮结论有误，见第九轮订正）**。②**进图闪现**——follower 原在 `!isMapObserved` 时整体 `return`，宠物坐标在无人时**冻结**（而 bot 仍按 coarse 移动）；玩家进图的 `spawnPlayerMapObject` 带的是陈旧宠物坐标，下一 tick 才发 `MOVE_PET` 拉回→闪现。现改为**每 tick 都同步宠物坐标**（纯内存），仅把**广播/说话/拾取**留给被观察的地图；陈旧度因此也被限制在一个 tick 内。**第九轮（依据真实客户端 IDA 订正，权威）**：在完整命名的 GMS095 客户端（与 083 同源）里反编译 `CPet::OnResolveMoveAction`(0x6a06c0) 与 `CPet::MoveAction2RawAction`(0x6a0ff0)，得到**权威**的宠物 move-action 字节——`moveaction>>1`=动作序号、`moveaction&1`=朝向，且 **0 落到默认分支→序号 0 = MOVE（不是站立！）**：MOVE=2/3、STAND=4/5、JUMP(空中)=6/7、SWIM(水)=12/13、HANG(梯绳)=30/31；宠物**没有独立 FLY 动作**。因此上轮把 STAND 改为 0/1 反而制造了原地踏步（0=MOVE）；正确 STAND=4/5。**同时按真实 CPet 行为重写跟随**：①朝目标点（bot 身后小错位）以固定速度**滑动**逼近，**不再瞬移到位**（瞬移正是 bot 转身时宠物"闪到身后"的原因），仅在距离 > `teleport_dist_px`（如传送后）才瞬移；②每步把 y **吸附到自身 x 下方的地面**（`findBelow`+`calculateFooting`，向上探测若干像素），消除"悬空走路"；③水中用 SWIM=12/13 且 `fh=0` 漂浮；④bot 在绳/梯时宠物用 HANG=30/31 挂在其身侧；⑤bot **腾空**（跳跃或高空坠落）时宠物也跟着腾空、保持主人高度并用 JUMP=6/7（腾空时不贴地，`noGravity`）。**拾取**：新增每宠冷却 `pickup.cooldown_ms`（默认 **1000ms**）且 `max_per_tick=1`；且**只拾取怪物掉落的道具**（`MapItem.getDropper() instanceof Monster`，玩家投掷的 `dropper` 是 Character 会被跳过）。**第十轮（实机反馈）**：①**宠物太贴主人/多宠未散开**——原目标 x = `bot.x + index*22`，第 0 只正好落在主人身上；改为**对称扇形散开** `spreadFor(index)`（0→+45、1→−45、2→+90），水陆一致。②**水下踏步**——权威反编译 `CPet::OnResolveMoveAction` 确认游泳 move-action 就是 12/13，值没错；真因是**死区分支重发姿态时沿用了宠物自身的旧 fh**（陆地 foothold），宠物进泳图后常处于死区/短距离，于是带着**非 0 的陆地 fh** 被客户端锚到海床 → 渲染成走路。改为**漂浮态（水/空/绳，`noGravity`）时 fh 一律清 0**；另把水判据放宽为 `map.isSwim() || CharacterStance.isSwimming(bot)` 以覆盖 bot 已在水中游的情况。

测试：`BotPetAssignerTest`（10 例，纯策略）+ `BotPetConfigTest`（2 例，YAML 键位 + 池加载）+ `BotPetFactoryTest`（4 例，无 DB 反射造宠 + 非空名）+ `PetCommandInterpreterTest`（5 例，行为选择）。全量 `mvn test` 841 项通过。
