# 陪玩系统（Companion）真 BUG 待审核清单

> 状态：**已审核并修复**（2026-10）。审核结论见每条的「复核」行；
> 23 条中 19 条判定为真 bug 并已修，4 条不成立/不构成 bug（见文末「四、审核后撤回」）。
>
> 审查方式：逐行阅读 soloMapling/companion/** 全部主源码、CompanionBot.java、桥接层
> （Dispatcher / DirectChatBridge / BotPartyInviteBridge）与共享层
> （GrindTickRegistry / GrindBrain / TrainingMapChooser），并对照宿主 BeiDou
> （GMS083/gms-server）源码逐条验证引擎契约。
> 未采信任何文档/注释/tests 的自述；行号以审查时工作区代码为准。
>
> 2026-10 第二轮复核（证伪优先）追加 O 系列见文末「二、全系统复核追加」；
> 该轮对首轮结论做了证伪，只有回溯到宿主源码/WZ 数据仍成立者才保留。
>
> 修复约束：全部落在插件侧，未改宿主；每条修复配最小回归测试。
> 编译/测试在 worktree 隔离会话中进行（`/tmp/wt-*`），未在原工程目录并发构建。

## 汇总

| 级别 | 编号 | 摘要 |
|---|---|---|
| P0 | BUG-1 | 同图私聊回复降级为地图喇叭，私聊内容全图可见 |
| P0 | BUG-2 | decHP 地图扣到 0 HP 后永久死锁，无任何恢复路径 |
| P0 | BUG-3 | 模拟升级绕过宿主 levelUp，AP/SP/HP/MP 永久缺失并落库 |
| P0 | BUG-4 | 记忆系统三断层：只回溯最近 100 条、永不归档、维护组件全部未接线 |
| P1 | BUG-5 | 跨图私聊丢失该玩家的全部记忆与关系 |
| P1 | BUG-6 | 训练战斗 POSITION_REPAIR 修复分支结构性不可达 |
| P1 | BUG-7 | 过期队伍邀请永久滞留队列并持续授权 |
| P1 | BUG-8 | 背包压力下贱卖 USE 栏值钱道具（卷轴等） |
| P1 | BUG-9 | 四转 SP 永久死锁（masterLevel=0） |
| P1 | BUG-10 | enqueueEvent 会话劫持 |
| P2 | BUG-11 | REST/GOODBYE 期间 solo grind 照常战斗 |
| P2 | BUG-12 | ActionValidator 的 engaged/cooldown 规则被硬编码关闭 |
| P2 | BUG-13 | ACCEPT_PARTY 特例解析不排除 bot 目标 |
| P2 | BUG-14 | 共享 BotClient 槽位与 BotClientBinding 文档矛盾（潜伏雷） |
| P2 | BUG-15 | PLANNING_TIMEOUT 12s 硬编码，与可配 LLM 超时错配 |
| P2 | BUG-16 | equipFromBackpack 同 id 多实例按槽位而非质量挑选 |
| P2 | BUG-17 | 每个实例新建整套 LLM client + 5 个 JDBC 仓库 |
| P2 | BUG-18 | lastProactiveByPlayer 只增不清 |

---

## P0

### BUG-1 同图私聊回复降级为地图喇叭（隐私泄漏）

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:203-205（onDirectChat 的同图降级）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:413-419（playDecision 仅在 replyType 非空时武装回复频道）
  - src/main/java/soloMapling/companion/execution/CompanionActionExecutor.java:190-200（say 无频道时回退 BotSpeak 地图气泡）
- 原因：onDirectChat 对同图来的定向行（WHISPER/PARTY/GUILD/BUDDY/ALLIANCE）把 replyType 置为
  null；宿主 WhisperHandler.handleWhisper 与 MultiChatHandler.publishToArtificialRecipients
  对 artificial 收件人发布事件与双方是否同图无关，因此同图私聊必然走到这里；replyType=null
  导致 playDecision 不武装 ThreadLocal 回复频道，HostEngineAdapter.say 回退 BotSpeak
  （地图气泡），玩家私聊内容以全图广播形式回复。对照 SocialBot.onDirectChat /
  FollowerBot.onDirectChat 均直接使用 message.getChatType()，从不同图降级。
- **复核：真 bug，已修。** 删除 onDirectChat 的同图降级，一律透传 `message.getChatType()`。
  私聊的可见性由频道决定而非距离——宿主对 artificial 收件人无同图门槛，同图私聊与跨图私聊
  同样可达、同样私有，置 null 只会让它掉回地图气泡。测试：同图 WHISPER 行 replyType 非空。

### BUG-2 decHP 地图扣到 0 HP 后永久死锁

- 位置：
  - src/main/java/soloMapling/companion/survival/CompanionSurvivalPolicy.java:47-55（shouldUse 要求 current > 0）
  - src/main/java/soloMapling/companion/survival/CompanionSurvivalController.java:104-107（useNeededPotion 要求 isAlive，即 hp>0）
- 原因：companion 在宿主 decHP 地图（Aqua Road / El Nath / Orbis Tower 等）会被持续扣血：
  MapleMap.addPlayer 对任何角色（无 artificial 排除）调 World.addPlayerHpDecrease；
  World.runPlayerHpDecreaseSchedule 定时调 Character.doHurtHp()（companion 的
  addBotToServer → markPresentInWorld() 使 isAwayFromWorld() 为 false，因此命中）；
  宿主 setHp 钳位下限为 0。一旦到 0，没有任何恢复路径：
  1. 插件喝药被 isAlive() + current > 0 双重封锁（宿主 applyHpMpChange 本可在 0 HP 时正常加血）；
  2. BotDeath.kill() 与 needsZeroHpAdoption 均显式排除 companion；
  3. 宿主回复不存在：HealOvertimeHandler 是真实客户端包处理器，headless bot 永不发送；
     MapleMap.recovery 无服务器端消费者；无任何 regen task 命中 bot；
  4. BotPotionSim.appliesTo 显式排除 companion（!CompanionRoster.isCompanion）；
  5. 唯一出口是离线结算 gainExp 触发宿主 levelUp 的 changeHpMp(localMaxHp, localMaxMp, true)，最长 24h 后。
  期间 hp=0 会被 saveCharToDB 持久化，跨重启延续；companion 以 0 HP 继续走路、接对话、战斗。
- **复核：真 bug，已修。** 只改两处政策判断：`shouldUse` 允许 `current == 0`（0 HP 正是最需要喝药的
  一格；宿主 `setHp` 只钳下限，正向 delta 无阻碍），并去掉 `useNeededPotion` 的 `isAlive()` 前置。
  `BotPotionSim.appliesTo` **不改** —— companion 有自己的 SurvivalController，加进来会双药；
  缺口仅是 0 HP 这一格，上面两处已覆盖。测试：`healsFromZeroHp` / `rejectsNegativeAndFullAndEmptyPools`。

### BUG-3 模拟升级绕过宿主 levelUp，成长数据永久缺失并落库

- 位置：
  - src/main/java/soloMapling/companion/execution/SoloGrindController.java:388-418（accrueSimulatedExperience 的 companion.setLevel/setExp）
- 原因：无人观察时的单独刷怪直接 setLevel/setExp。宿主 Character.level 是 Lombok @Setter
  普通字段，无副作用；宿主 levelUp() 中的 gainAp(5)、按职业 addMaxMPMaxHP、
  levelUpGainSp()、recalcLocalStats、setMPC/silentPartyUpdate 全部被跳过。后果：
  1. HP/MP 池不随等级增长（companion 被 BotPotionSim.ensureMaxHp 排除，无替代机制）——高等级 companion 顶着低等级 HP 池；
  2. AP/SP 从不发放——下次 reconcile 时 getRemainingAp()=0，CompanionBuildAllocator 无点可分配；
  3. 全部经 saveCharToDB 持久化到 characters.level/ap/sp，永久损伤。
  同一 companion 的两条升级路径不一致：被观察时走宿主 gainExp（正路，触发完整 levelUp），
  HostCompanionRuntimeAdapter.grantOfflineExperience 也用 gainExp（正路），仅此路径残缺。
- **复核：真 bug，已修。** 删掉手写的 ExpTable 循环，改为 `companion.gainExp(gain, false, false, false)`
  ——与被观察路径、离线结算路径同一条正路，宿主自己跨过每个 ExpTable 阈值。
  同时删掉 `BotLevelUpNotice.announce`：宿主 `gainExp` 内部（Character.java:3092-3094）已按同一
  config switch + 同一 i18n key 广播，再调一次会双广播。`ExpTable`/`BotLevelUpNotice` 导入随之移除。

### BUG-4 记忆系统三断层：窗口 / 无归档 / 组件未接线

- 位置：
  - src/main/java/soloMapling/companion/agent/ProductionCompanionBrain.java:136-138（buildInput 固定 limit=100）
  - src/main/java/soloMapling/companion/persistence/JdbcCompanionMemoryRepository.java:117（ORDER BY occurred_at DESC LIMIT ?）
  - src/main/java/soloMapling/companion/agent/ProductionCompanionBrain.java:212-237（persist 每回合无条件插入，expires_at=null）
- 原因：
  1. 每回合 planning 只能召回"最近 100 条"记忆（固定 limit + SQL 时间倒序截断），记忆越多遗忘越严重；
  2. 每次成功对话无条件插入一条 EPISODIC 记忆且无 TTL，bot_memories 无界增长；
  3. 全仓 grep 确认零生产调用方（仅 test 引用）：MemoryConsolidationService（归档+摘要）、
     markRecalled、updateStrength、JdbcCompanionKnowledgeRepository、PerceptionPolicy
     —— 没有归档任务、没有 recalled-at 刷新，MemoryScorer 的衰减只在一个不变的 100 条窗口内生效；
  4. 潜伏：JdbcCompanionMemoryRepository.existsWithTag（:189-209）逐行拉全量活跃记忆再解码 tags，
     O(n) 扫描；MemoryConsolidationService 一旦接线，每次 apply 都是全表扫。
- **复核：真 bug，已修（3 项中的 2 项 + 1 项遗留）。**
  1. **窗口**：`100` → `MEMORY_RECALL_LIMIT = 200`，并注明这是召回上限而非遗忘点
     （MemorySelector 之后仍按 salience/strength/recency 排序裁剪）。
  2. **TTL**：`persist` 传入 `now.plus(EPISODIC_TTL)`（30 天）而非 null，仓库自身的
     `expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3)` 过滤器自动收敛，表不再无界增长。
  3. **接线 MemoryConsolidationService：本轮未做。** 归档任务需要一个周期性调度入口，
     而 companion 数量级为个位数、`existsWithTag` 的 O(n) 会随每次 apply 全表扫，
     两者叠加的收益/风险需要单独评估。**列为遗留项**，不在本批最小改动内。
     其余 3 条（markRecalled / updateStrength / JdbcCompanionKnowledgeRepository /
     PerceptionPolicy 无生产调用方）同属该遗留项。

---

## P1

### BUG-5 跨图私聊丢失该玩家的全部记忆与关系

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:694-717（snapshotPerception：targetIds 仅含同图玩家 + pending inviter）
  - src/main/java/soloMapling/companion/planner/CompanionPlannerContextBuilder.java:39-44（allowedActor 按 targetCharacterIds 过滤记忆）
  - src/main/java/soloMapling/companion/planner/CompanionPlannerContextBuilder.java:27-33（关系行同样过滤）
- 原因：跨图 whisper 的玩家不在 targetCharacterIds 集合中 → 该玩家的全部历史记忆被过滤出
  prompt（"Relevant memories: - none"），与该玩家的关系行也被剔除。跨图对话的陪玩对交谈对象
  完全失忆。gift 门槛（CompanionGiftPolicy.relationshipAllows）使用未过滤的 related 不受影响，
  受影响的是 prompt 披露层。
- **复核：真 bug，已修。** `snapshotPerception` 始终把本回合对话者 `planningPlayerId` 加入
  `targetIds`。`sameMapCharacterIds` 保持纯同图（`ActionValidator.requireSameMap` 依赖它做移动
  类动作的门禁），只有记忆/关系披露层放宽——白名单变宽不等于"所有人"：没和这个 companion
  说过话的玩家依然被 `allowedActor` 过滤掉。测试：`CompanionPlannerContextBuilderTest`
  （跨图 speaker 保留记忆与关系 / 陌生人仍被过滤）。

### BUG-6 训练战斗 POSITION_REPAIR 修复分支结构性不可达

- 位置：
  - src/main/java/soloMapling/companion/execution/CompanionCombatRecoveryPolicy.java:17-28（choose 要求 !mapObserved 才给 POSITION_REPAIR）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:560（grindTick 训练分支要求 target 同图在线）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:583-589（修复动作落点为玩家位置）
- 原因：矛盾链条——执行到恢复判定时必然满足 target.getMap()!=null 且同图；TrainWith 目标必为
  真实玩家（SnapshotResolver.resolveCharacter 排除 bot）；真人在图 ⇒ ObserverTracker.poll
  （!isBot → full.add，约 1s 周期）⇒ GCMovement.isMapObserved=true ⇒ !mapObserved 条件被排除，
  永远只剩 REPATH。唯一例外是玩家刚进图、1s poll 未刷新且 progressAge≥90s 同时成立的竞态窗口。
  设计意图（"无人看见时闪现修复"）在结构上不可能发生。
- **复核：真 bug（死分支），已修——删代码而非补逻辑。** 删除 `POSITION_REPAIR` 枚举值、
  `POSITION_REPAIR_AFTER_MS`、`choose` 的不可达分支，以及 `grindTick` 里对应的 teleport 分支。
  理由：唯一能触发的窗口是"玩家刚进图、poll 未刷新"——而那正是这个可见 teleport 最不该出现
  的时刻，留着它是一个潜伏 glitch 而非功能。`mapObserved` 参数随之删除：调用点
  （CompanionBot.grindTick）仍在计算它供诊断日志使用，但不再有任何决策依赖它。
  测试：`unobservedStallRepathsRatherThanTeleporting` 等 4 例。

### BUG-7 过期队伍邀请永久滞留队列并持续授权

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyQueue.java:46-65（last-wins 但无 TTL）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:673-692（observePendingInvite）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:704-709（snapshotPerception 把陈旧 inviter 计入授权集）
  - src/main/java/soloMapling/companion/execution/CompanionActionExecutor.java:208-227（acceptParty 校验的是队列条目）
- 原因：companion 不调用 BotRecruitManager.pollInvites；全仓 removePartyInvite 只有 accept
  （BotPartyCommands:96）与 reject（:122）两个调用点。planner 既不接受也不说再见时（如只回了句聊天），
  条目永存。宿主 InviteCoordinator 约 3 分钟已过期的邀请持续出现在授权集中：后续任何回合
  ACCEPT_PARTY 都能通过 executor 的三重 inviter 校验（条目还在），然后在宿主
  answerInvite → NOT_FOUND 处失败（PARTY_ACCEPT_FAILED）——玩家看到"陪玩答应了却没进队"。
- **复核：真 bug，已修。** 在队列侧加时间戳 + 惰性过期：`PartyInviteEntry` 增 `enqueuedAtMs`，
  `getPartyInvite` 超过 `INVITE_TTL_MS`（180s，对齐宿主 `runTimeoutSchedule` 的 3 分钟）即移除并
  返回 null。改一处覆盖全部 drain 方（companion / SocialBot / OPQ），比在 companion 里加
  `pollInvites` 改动小且不引入新的 FSM 交互。测试：`BotPartyQueueTest`（过期移除 / 窗口内仍应答 /
  last-wins 不变）。`addPartyInvite` 因此拆出一个"整条 entry"的重载（生产仍传刚盖时间戳的条目），
  测试借它把条目老化到 TTL 之外，避免真睡 3 分钟。

### BUG-8 背包压力下贱卖 USE 栏值钱道具（卷轴等）

- 位置：
  - src/main/java/soloMapling/companion/survival/CompanionSurvivalController.java:277-319（sellInventory 的 USE 过滤）
- 原因：USE 栏卖货过滤只排除两类——可充能（ItemConstants.isRechargeable）与带 hp/mp 恢复效果的
  物品。卷轴（204xxxx，宿主 ItemConstants.getInventoryType：type byte 2 → USE）、回城卷及其他
  无 hp/mp 效果的消耗品在背包压力时全部按 NPC 价卖掉。companion 战斗经 GrindLoot 捡自己与
  FFA 掉落，卷轴掉落进 USE 栏是常态；与 FreeMarket 体系"值钱货挂 FM"的世界观冲突。
- **复核：真 bug，已修。** USE 栏由黑名单改为白名单：新增
  `CompanionSurvivalPolicy.isSellableUseItem(hp, mp)`，只有真正恢复 HP 或 MP 的物品（即这个系统
  自己认定的 potion）可卖。卷轴、回城卷等一概不卖，而 potion（含商店不供应的）仍可出清。
  顺带修正了原实现的另一处不一致：旧条件把 `hpRate/mpRate > 0` 也当排除依据，而 `potion()`
  候选判定只认 flat hp/mp，两处口径不同。测试：`onlyPotionsAreSellableFromTheUseTab`。

### BUG-9 四转 SP 永久死锁（masterLevel=0）

- 位置：
  - src/main/java/soloMapling/companion/progression/CompanionBuildAllocator.java:123-131（planSp：四转技能 maximumLevel 取 character.getMasterLevel(skill)）
  - src/main/java/soloMapling/companion/progression/CompanionSpPlanner.java:53-57（blockedPolicy=RESERVE 直接 break）
- 原因：宿主 Skill.isFourthJob()（job % 10 == 2）为真时，Character.getMasterLevel（:5064-5070）
  对未学习技能恒返回 0。companion 体系内没有任何授予四转 master level 的路径（无技能册、无任务、
  runtime adapter 未处理）→ target = min(MAX, 0) = 0 → current >= target 全部 skip →
  末位 RESERVE milestone 直接 break。四转 companion 永远只有 1-3 转技能，SP 永不消耗，
  每次 reconcile 记录 spBlocked=mastery-cap:<skillId>:0 且无告警出口。
- **复核：真 bug，已修。根因不是"缺一个常量"，而是 companion 从未"授予"过 master level。**
  证据：宿主 `Skill` 类根本不读 WZ 的 `masterLevel`（`SkillFactory.loadFromData` 只读
  skillType/elemAttr/effect/action/hit/ball；全仓 `DataTool.getInt("masterLevel")` 仅
  `ItemInformationProvider:1480`，读的是 **Item.wz 技能书**的 info）。宿主自己的授予路径是
  `SkillBookHandler:87` → `changeSkillLevel(skill, level, Math.max(book.masterLevel, current), -1)`。
  companion 没有技能册也没有任务，所以这一步永远没发生。
  修法：`planSp` 的 `maximumLevel` 与 `applySp` 的 `changeSkillLevel` 都改用
  `Math.max(character.getMasterLevel(skill), FOURTH_JOB_MASTER_LEVEL)` —— 首次学习即随技能一起
  授予 master level，与宿主技能书同构。常量取 **10**，依据是本仓库 `wz/Skill.wz` 实测：
  2121001/2121002/2121006、2221001/2221002/2221006、2321005、3121002/3121007/3120005、
  3220004/3221002/3221006、4121006/4120002/4120005、4221007/4220002/4220005 全部声明
  `masterLevel = 10`（与对应技能书同值），非拍脑袋。测试：`fourthJobSpendsAgainstAGrantedMasterLevel`。

### BUG-10 enqueueEvent 会话劫持

- 位置：
  - src/main/java/soloMapling/companion/agent/TurnCoordinator.java:97-101（enqueueEvent 无条件覆盖 sessionPlayerId）
  - src/main/java/soloMapling/companion/agent/TurnCoordinator.java:86-94（enqueue 的会话归属校验）
- 原因：玩家 A 会话进行中（ENGAGED 且有排队消息）时，队伍邀请事件（来自 B）经 enqueueEvent
  覆盖 sessionPlayerId = B。tick 按 FIFO 先处理 A 的已排队消息、再处理 B 的邀请；回合结束后
  state=ENGAGED（sessionDeadline 被 A 的回合刷新），此后 A 的新消息被 enqueue:88-90 拒绝、
  acceptsContinuation(A) 变 false，最长锁死 45 秒（SESSION_TIMEOUT）。三条规则
  （事件绕过会话归属 / FIFO 处理 / 冷却后回到 ENGAGED）互相矛盾。
- **复核：不成立，撤回。** 原文自相矛盾：`enqueueEvent` 覆写 `sessionPlayerId` 后，
  `enqueue:88-90` 拒绝 A 的新消息、`acceptsContinuation(A)` 返回 false —— 这**正是 B 拿到会话
  所有权的预期语义**，不是死锁。`TurnCoordinator.enqueueEvent` 的 javadoc（:96）明确宣示了
  "bypasses chat-session ownership" 这一语义，party-invite 事件理应优先于聊天会话。
  所谓"最长锁死 45 秒"也不存在：A 只是被礼貌拒绝（Dispatcher 因此不会把 A 的行路由给
  companion），45s 后 `sessionDeadline` 到期回到 ROUTINE，A 立即可继续对话。
  未改动任何代码。

---

## P2

### BUG-11 REST/GOODBYE 期间 solo grind 照常战斗

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:524-528（stopForRest 不停 soloGrind）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:531-538（grindTick 只查 running/PAUSE，不受 waitFor 约束）
  - src/main/java/soloMapling/companion/execution/CompanionTrainingController.java:17-23（接口契约：stopForRest 应停一切会在 REST 后恢复移动的活动）
- 原因：REST/GOODBYE 用 bot.waitFor() 暂停 FSM tick，但 250ms 战斗 sweep（grindTick）不受
  waitFor 约束，stopForRest() 也未调用 soloGrind.stop(...)——"休息"中的 companion 的
  GrindBrain 照常出拳走位，违反 stopForRest 的接口契约。
  （复核修正：在途 GCTravel 会被取消——HostEngineAdapter.rest()/goodbye() 在 stopForRest 后
  立即调用 GCMovement.stop(companion)，此点不构成 bug。）
- **复核：真 bug，已修。** `stopForRest` 增补 `soloGrind.stop(getChr())`——solo grind 与
  training 一样属于"会在 REST 后恢复移动的活动"，接口契约覆盖它。
  **未改 `grindTick` 的 `isWaiting()` 语义**：`stopForRest` 是 REST/GOODBYE 的唯一入口，
  停在这里即覆盖全部路径；而 `waitFor()` 只设 `waitUntilMs`、不停 FSM，这是 BotTradeSM 等
  36 个调用方依赖的行为，不该为一个 bug 改它。


### BUG-12 ActionValidator 的 engaged/cooldown 规则被硬编码关闭

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:723-725（snapshotPerception 传 Set.of() 与 false）
  - src/main/java/soloMapling/companion/agent/ActionValidator.java:31-34 与 117-123（相应规则永不触发）
- 原因：cooldownActions=Set.of()、engaged=false 是硬编码，requireNotEngaged 与
  ACTION_ON_COOLDOWN 是死代码。运行时表现：训练中收到 LLM 的 FOLLOW 动作会静默
  GCMovement.follow 改跟别人，而 combatLifecycle 仍指向旧 trainingTarget，两个移动意图并行。
- **复核：真 bug，已修。** `snapshotPerception` 传真实值：
  `cooldownActions` = `trainingTarget != null ? {FOLLOW, GO_TO, TRAIN_WITH} : {}`
  （正在跟某个玩家训练时，不允许 planner 再下发会争抢同一移动的意图），
  `engaged` = `soloGrind.engaged()`（该 companion 正忙自己的事）。
  两处本来就是 `ActionValidator` 三个规则的设计意图，只是从未接线；给真实值即让规则生效，
  不引入新策略。planning 本身仍可中断（引擎忙态在 execution 处检查），故 SAY/EMOTE/party 不门禁。


### BUG-13 ACCEPT_PARTY 特例解析不排除 bot 目标

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:773-781（SnapshotResolver.resolveCharacterFor 的 ACCEPT_PARTY 特例）
- 原因：特例分支直接返回 pendingInviter，不经过 resolveCharacter 的 isBot 过滤
  （同 resolver 其他路径都过滤）。允许 companion 接受来自其他 bot 的邀请，行为不一致。
- **复核：真 bug，已修。** `SnapshotResolver.resolveCharacterFor` 的 ACCEPT_PARTY 特例补
  `!isBot(pendingInviter)`。party invite 的发起方按宿主契约必为真人
  （`InviteCoordinator.createInvite(type, from, partyId, target)`），bot target 只可能来自陈旧
  队列条目；补上后该特例与 resolver 其他路径口径一致，而非静默放大。


### BUG-14 共享 BotClient 槽位与 BotClientBinding 文档矛盾（潜伏雷）

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:317（loadPersistentBot 使用 BotClientHandler.clientFor(channel) 共享单例并 setPlayer 不还原）
  - src/main/java/soloMapling/ArtificialPlayer/BotClientHandler.java:36-42（per-channel 共享 client）
  - src/main/java/soloMapling/ArtificialPlayer/BotClientBinding.java（javadoc 声称 companion 各有 private BotClient）
- 原因：BOT_CLIENTS.computeIfAbsent 每 channel 一个 BotClient；多 companion 同 channel 时
  基础 player 槽位被后加载者覆盖，withBoundPlayer 在操作间隙恢复的 previous 是另一个 companion。
  复核结论：已核实的引擎路径（pickup/shop.buy/shop.sell/potion）都在 withBoundPlayer 排他区内
  读槽位，未找到当前可触发的实际错乱——降级为文档失实 + 潜伏雷。
- **复核：真 bug（潜伏竞态），已修。** `BotClientBinding` javadoc（:21-23）自称
  "Persistent companions are the exception: loadPersistentBot gives each of them a **private**
  BotClient"，但 `BotGeneration.loadPersistentBot:317` 用的是 `BotClientHandler.clientFor(channel)`
  ——**每 channel 一个共享 client**，且 `setPlayer`（:323）会覆盖同 channel 前一个 companion 的槽位；
  `withBoundPlayer` 退出时恢复的 `previous` 于是是另一个 companion 的 Character。
  companion 持久化背包与装备，这是真实的串数据风险而非装饰性问题。
  修法：`loadPersistentBot` 装载后立即 `adoptPrivateClient(companion)`（复用既有的 PQ 参与者抽象，
  资源特征完全一致：少量、引擎交互长于调用点）。共享 client 只被 ambient bot 使用，不受影响。


### BUG-15 PLANNING_TIMEOUT 12s 硬编码，与可配 LLM 超时错配

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:64（PLANNING_TIMEOUT = 12s）
  - src/main/java/soloMapling/companion/agent/ProductionCompanionBrain.java:59-65（Settings.timeout 来自 SocialLlmConfig，可配 2-60s）
- 原因：运营把 llm.timeout-ms 配到 >12s 时，每个回合必然先触发 coordinator 的 TIMEOUT fallback
  （玩家收到兜底回复），provider 结果迟到后进结果队列再被丢弃；且 CompletableFuture.orTimeout
  不取消底层 HTTP 调用，请求继续占用资源直到 provider 自身超时。
- **复核：真 bug（错配类），已修。** `PLANNING_TIMEOUT` 改为从
  `SocialLlmConfig.timeoutMs()` 派生并保底下限：
  `Duration.ofMillis(Math.max(2_000L, SocialLlmConfig.timeoutMs()))`。
  原 12s 硬编码在 LLM 配 5s 时，会让 5s 就失败的结果在 `TurnCoordinator` 队列里躺 7s
  （planningDeadline 分支），期间该 companion 无法接受任何新回合。
  `CompletableFuture.orTimeout` 不取消底层 HTTP 这一点未改——那需要取消语义，超出最小改动范围，
  且 `CompanionPlannerService` 的 settings.timeout 已经和它一致（同一来源）。


### BUG-16 equipFromBackpack 同 id 多实例按槽位而非质量挑选

- 位置：
  - src/main/java/soloMapling/companion/gear/CompanionGearController.java:356-372（max(Comparator.comparingInt(Item::getPosition))）
- 原因：同 id 多件时取最后一个槽位的实例，而非按 CompanionGearPolicy.compareQuality 挑最优；
  与 auto-equip 路径（比较真实实例属性）、shop 路径（比较模板属性）口径不一致。
- **复核：**不成立，撤回（原文档定性有误）。**** 核查 `CompanionGearController.gearItem`
  （:327-339）只读 `equip.getWatk()/getStr()/...` 等**模板属性**；companion 侧没有任何砸卷/星力
  路径（`EquipMetadataCache.EquipEntry` 也不携带实例级随机属性）。因此**同 itemId 的多个实例
  属性必然相同**，"按槽位挑"与"按质量挑"结果等价，不存在"挑到差的"。
  按 AGENTS.md「禁止过度设计」不为此改代码；保留现状并在此钉住该不变量。


### BUG-17 每个实例新建整套依赖

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:107-114（构造器链 ProductionCompanionBrain.createDefault()）
  - src/main/java/soloMapling/companion/agent/ProductionCompanionBrain.java:53-73（createDefault 每次新建 DeepSeek client + 5 个 JDBC 仓库）
- 原因：N 个 companion = N 个 LLM HTTP client + N 套仓库实例。功能正确，资源浪费；
  ProductionCompanionBrain 内部本就是线程安全的 repository/executor 组合，可单例共享。
- **复核：真 bug（资源类），已修。** `createDefault()` 改为 `synchronized` 懒加载单例。
  这 5 个对象全部无状态：planner 线程安全、4 个 JDBC 仓库每次调用自建连接且无字段、
  executor 是共享池——N 个 companion 建 N 份纯属浪费。
  **配置时效已核实**：`SocialLlmConfig.configure` 只从 `SoloMaplingExtension.onLoad` 调用一次
  （`SocialLlmService.configure:27-41` 同样是"一次性"语义，且已有 `configure(LlmClient)` 测试缝），
  全仓无运行时重配入口，故单例不会读到过期配置，无需失效钩子（YAGNI）。


### BUG-18 lastProactiveByPlayer 只增不清

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java:104（ConcurrentHashMap，仅 put 无 remove）
- 原因：主动社交记录只增不清，随历史玩家数无界增长。量级受 ProactiveAttentionPolicy 冷却限制，
  属卫生问题。
- **复核：真 bug，已修。** `maybeInitiateConversation` 在地图上无玩家时
  `lastProactiveByPlayer.clear()`。语义上这本就是"本图玩家的主动记录"，跨图即失效。
  安全性：`ProactiveAttentionPolicy.shouldInitiate` 的 baseline 取
  `lastInteractionAt` 与 `lastProactiveAt` 的较晚者，清掉 proactive 只会让冷却从
  `lastInteractionAt` 起算（更严），不会造成刷屏。


---

## 复核后撤回的原提案（不列入 bug）

- 离线结算奖励丢失：重推时序后实际方向是重复发放（saveCheckpoint 失败时游标不推进，
  下次 spawn 重发同一时段），且该方向被代码注释明示接受。
- 双脑重附：lifecycle 路径有 online.get 前置、spawn 失败残留路径先 setRunning(false)+
  stopScheduledTask、GM 转换路径先 manuallyStopBot——未找到可到达的"旧 tick 闭包继续跑"场景。
- EXECUTING 状态无 tick 分支：CompanionBot.execute() 全路径复核无可抛出点，即使卡住也会在
  下一条消息经 startPlanning 自愈——健壮性缺口，未列为 bug。
- stopForRest 不取消在途 GCTravel：不成立，rest/goodbye 随后立即 GCMovement.stop。

---

## 二、全系统复核追加（2026-10 第二轮，覆盖非 companion 的全体 bot）

> 审查方式：对全仓 `src/main/java/soloMapling/**`（519 文件）与宿主 `gms-server`
> 逐条回溯验证。**只列经宿主源码或 WZ 实测仍可复现者**；首轮 15 条中已被证伪的
> 7 条连同证据记入文末「三、第二轮证伪记录」。

| 级别 | 编号 | 摘要 |
|---|---|---|
| P1 | BUG-19 | `manuallyStopBot` 缺 null 判定，未注册 bot 直接 NPE（GM 命令即触发） |
| P1 | BUG-20 | OPQ 编排器 `hasUnclaimedLiveCloudReactor` / `hasUnclaimedLiveBoxReactor` 未同步 |
| P2 | BUG-21 | `BotTradeSM.update()` 无终态短路，超时判定覆盖 `COMPLETED`/`TIMED_OUT` |
| P2 | BUG-22 | `BotEventBuffer.add` 用 `size()` 做容量判定，非原子且 O(n) |
| P2 | BUG-23 | `feedReactor` 未防 `getReactItem` 返回 null / 内部越界 |

---

### BUG-19 `manuallyStopBot` 缺 null 判定，未注册 bot 直接 NPE

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:339-343（`manuallyStopBot` 无判空）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:315-321 / 330-337（对照：`manuallyStartBot` **两处**都有 `bot == null` 保护）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:345-352（对照：`startAttackTestBot` 亦有保护）
- 调用点（全部会把 null 传进来）：
  - src/main/java/soloMapling/command/ArtificialPlayerCommand.java:434-435（`!bot manualstop <cid>`）
  - src/main/java/soloMapling/command/ArtificialPlayerCommand.java:468-471（`!bot disconnect|dc|remove <cid>`）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:413-419（`massManualStop`：按 cid 区间遍历，多数 cid 不是注册 bot）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:437-444（`stopBots`：有 `getValidBot` 判空，此路安全）
- 原因：`BotGeneration.createBot`（:217-291）只创建 Character 并放进 world storage，
  **不调用 `CharacterStorage.addActiveBot`**（全仓 grep 确认：只有 `BotTypeManager` 的
  各 `createAndSetBot` 与 `CompanionBot.attach` 会注册 BotSM）。因此 `!bot create` /
  `!bot spawn`（`handleSpawn`，ArtificialPlayerCommand:828-886；未带 `autoAttack` 时只 createBot（:869），不注册 BotSM）
  产生的 bot 可以 `getCharFromChannelStorage(cid)` 取到 Character，但
  `getBotById(cid)` 返回 null；`manuallyStopBot` 随即 `bot.setRunning(false)` → NPE。
- 复现：`!bot spawn warrior 1`（idle 模式）→ `!bot manualstop <cid>`。
  异常抛在 `ExecutorServiceManager.getExecutorService()` 的线程池任务中
  （ArtificialPlayerCommand:143-149 的 lambda），GM 侧只看到命令无响应。
- 根因是这一个函数的判空缺失 —— 同族 3 个函数中只有它没有。
- **复核：真 bug，已修。** `manuallyStopBot` 补 `if (bot == null) return;`，对齐同族
  `manuallyStartBot` / `startAttackTestBot`。未注册 cid（`!bot spawn` 不带 autoAttack 只 createBot、
  `!bot create`/`!bot disconnect|remove` 传任意 cid）都会到达这里，原 NPE 被 executor 线程池吞掉，
  GM 侧只看到命令无响应。测试：`BotTypeManagerStopGuardTest`（源码级钉住三兄弟都有判空、
  且判空先于首次解引用）。


### BUG-20 OPQ 编排器 `hasUnclaimedLiveCloudReactor` / `hasUnclaimedLiveBoxReactor` 未同步

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQOrchestrator.java:192-206（`hasUnclaimedLiveCloudReactor`，**无 `synchronized`**）
  - src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQOrchestrator.java:265-277（`hasUnclaimedLiveBoxReactor`，**无 `synchronized`**）
  - 对照写方：同文件 :144（`assignCloudReactor`，`synchronized`）与 :214（`assignBoxReactor`，`synchronized`）
  - 调用点：src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:394（cloud）、:944、:1142（box）
- 原因：两个读方法与两个写方法读写同一组 `context.getCloudAssignmentsView()` /
  `getBoxAssignmentsView()`（`OPQSharedContext` 的 ConcurrentHashMap，逐 key 原子但**复合读不原子**）。
  写方持 `OPQOrchestrator` 监视器做"读 taken → 选 reactor → put assignment"，
  读方不持锁做"读 taken → 判定是否还有空位"，因此读方可能看到写方的中间态：
  1. （cloud）B 在 `hasUnclaimedLiveCloudReactor` 读到旧 taken 快照，判定"还有空位"，
     但随后 `assignCloudReactor` 返回 null（已被 A 占满）→ OPQBot:393-398 既不转
     `STAGE_1_RETURN` 也不推进，空转直到 `STAGE_WAIT_TIMEOUT_MS`（OPQConstants:128，120s）。
  2. （box）`getState()` 读的是 `Reactor` 的非 volatile `state` 字段（宿主 Reactor.java:52），
     与 `hitReactor` 的持锁写并发，可读到尚未稳定的中间值，使 stage-2 提前
     `markTaskComplete`（OPQBot:1148）而箱子仍有剩余。
- 说明：同族问题已在 `OPQSharedContext.mySealedSlot`（:130）以 `synchronized` 修过，
  这两处是同一根因的漏网成员。
- **复核：真 bug，已修。** 两个读方法补 `synchronized`，与写方
  `assignCloudReactor` / `assignBoxReactor` 同一监视器——`mySealedSlot:130` 正是这么修的，
  这两处是同根因的漏网成员。这两个方法是 OPQBot 的 stage 决策点（:393-398 / :1148），
  调用频率远低于 1Hz tick，加锁无热路径风险。


### BUG-21 `BotTradeSM.update()` 无终态短路，超时判定覆盖 `COMPLETED`/`TIMED_OUT`

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeSM.java:220-222（`update()` 末尾无条件跑 `calculateTimeOut()`）
  - src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeSM.java:93-100（`calculateTimeOut` 只比较 `startTime + timeoutSeconds`）
  - src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeSM.java:54 / 56（`timeoutSeconds = 60`）
  - `startTime` 的重置点：:69（构造）、:134（RESPONDING）、:169（CONFIRMING）—— 之后不再重置
- 原因：`update()` 的 `switch` 跑完终态分支后仍会执行末尾的超时检查。已进入
  `COMPLETED`/`TIMED_OUT` 后若 60s 已过，状态被改回 `TIMED_OUT`，
  下一 tick 重跑 `TIMED_OUT` 分支（:204-212）：重复 `writeTradeChat` + 重复
  `declineTradeInvite`（宿主 `Trade.declineTrade` 判 `trade == null` 早退，故幂等）
  + 重复 `waitFor(2500)`。
- 实际窗口：`BotSM.updateState()` 的 TRADING 分支（BotSM.java:668-674）在
  `isTradeComplete() && !verifyTradePartner()` 时立即 `cleanupTradeState()` 并弃用
  `botTradeSM`，因此重复被执行的机会有限 —— 事件序列里的可见后果是超时提示语
  与关闭动作可能重复一次，不是无限循环。
- 修法：`update()` 开头对 `COMPLETED`/`TIMED_OUT` 早退，或让 `calculateTimeOut()`
  仅在非终态时生效。
- **复核：真 bug（窗口有限），已修。** `BotTradeSM.update()` 开头对
  `COMPLETED` / `TIMED_OUT` 早退。`calculateTimeOut` 只比较 `startTime + timeoutSeconds*1000`，
  而 `startTime` 仅在进入 RESPONDING/CONFIRMING 时重置，故终态后 60s 一过就会被反复改回
  `TIMED_OUT`，重放超时提示语 + 延迟 decline。`default:` 分支本就会为未知状态抛异常，
  所以终态短路安全且必要（顺带覆盖 DECLINE 后的重复）。


### BUG-22 `BotEventBuffer.add` 用 `size()` 做容量判定，非原子且 O(n)

- 位置：src/main/java/soloMapling/server/EventMessageSystem/BotEventBuffer.java:21-27
  （`if (queue.size() >= maxSize) queue.poll(); return queue.offer(event);`）
  - `maxSize` 由构造方给出：src/main/java/soloMapling/ArtificialPlayer/BotSM.java:204（`new BotEventBuffer(100)`）
- 原因：`ConcurrentLinkedQueue.size()` 是 O(n) 全遍历且与 `offer` 无原子性。
  `onEvent`（BotSM.java:952-955）由 `EventBus.publish` 在**多个不同线程**上调用
  （聊天走 packet 线程、升级走宏 tick、地图进入走地图线程），因此并发 add 时
  上限可被突破；同时每次事件都付一次 O(n) 遍历，事件密集时退化为 O(n²)。
- 影响：上限失效 + 不必要的热点开销。溢出本身无功能损害（队列本就允许丢弃旧事件）。
- **复核：真 bug，已修。** `BotEventBuffer` 增 `AtomicInteger size` 影子计数：
  `ConcurrentLinkedQueue.size()` 是 O(n) 遍历且与 `offer` 无原子性，两个线程同时 add 会双双
  看到"还有空位"从而突破上限，且每次 add 都付一次全表遍历。改为 O(1) 计数后，并发下最多突破
  "同时通过检查的线程数"，而事件本就允许丢弃，无害。测试：`BotEventBufferTest`
  （容量/并发不清零/clear 同步）。


### BUG-23 `feedReactor` 未防 `getReactItem` 返回 null / 内部越界

- 位置：
  - src/main/java/soloMapling/ArtificialPlayer/PqActions.java:695-721（`feedReactor`，:702-703 直接 `required.getLeft()`）
  - 宿主契约：/workspace/GMS083/gms-server/src/main/java/org/gms/server/maps/ReactorStats.java:114-120
    （`stateInfo.get(state).get(index)` 可抛 NPE/IndexOutOfBounds，或 `StateData==null` 时返回 null）
  - 宿主 Reactor.getReactItem：Reactor.java:231-233（**不判空**，直接透传）
- 原因：`feedReactor` 只做了 `getReactorType() == 100` 前置过滤。该判定的粒度是
  "**当前 state** 的 type"，而 `getReactItem(getEventState())` 用的是**事件状态**索引，
  二者在数据上并不保证一致。实测（本轮用 WZ 全量解析验证）：
  - 受影响的 5 个 OPQ reactor（2001000 / 2001001 / 2001016 / 2002003 / 2006001）
    均只有 `state 0` 一个 event，其余 state 为 `type=0`/无 event；
  - 全仓 `setEventState` 仅 5 处（OrbisPQ.js:163 的 music，HorntailPQ.js:129-132 的 passKey1-4），
    均落在多 state 的 reactor 上，故**当前数据下不可达**。
  - 一旦后续 WZ 改动或新脚本对单-state reactor 调 `setEventState(>0)`，
    `ReactorStats.getReactItem` 会先 `stateInfo.get(n)` 得 null 再 `.get(index)` → **NPE**。
- 定性：健壮性缺口（当前不可达），不是现行崩溃点。修法：`required == null` 短路即可。
- **复核：真 bug（健壮性缺口，当前不可达），已修。** `feedReactor` 在
  `getReactItem(getEventState())` 之后补 `required == null` 短路。
  `getReactorType() != 100` 判的是**当前 state** 的 type，`getReactItem` 用的是**事件状态**索引，
  二者数据上不保证一致；宿主 `ReactorStats.getReactItem:114-116`
  （`stateInfo.get(state).get(index)`）无保护。`required.getRight()` 在 :711/:716/:719 被解引用三次，
  故短路放在取得处而非每处使用点。当前 5 个 OPQ reactor 均单 state-0 event，确实不可达——
  这正说明它是缺口而非现行崩溃点，值得一行防御。


---

## 三、第二轮证伪记录（不列入 bug）

> 对首轮 15 条逐条回溯宿主源码 / WZ 实测后，以下 7 条**证据不支持**，撤回：

- **首轮 #2「`feedReactor` 必崩」**：实测 5 个相关 reactor 的 state 链结构使
  `getReactorType()==100` 前置过滤全部生效，`setEventState` 全仓仅 5 处且均安全。
  保留为 BUG-23（健壮性缺口），非"NPE 必现"。
- **首轮 #3「`isAltarThrower` 永久霸占选举」**：逐行复核 `OPQOrchestrator.java:335-357`，
  不在场的 bot 在遍历中被 `continue` 跳过，`winnerStillHere` 保持 false → 会重选。逻辑正确。
- **首轮 #4「`approachReactor` beat 注册错误地图」**：自证不成立 ——
  `workReactorOnBeatUnlocked`（PqActions.java:588-614）有 `box.map() != bot.getMap()` 保护。
- **首轮 #6「`removeMessageIfConditionMet` NPE」**：全仓 grep 确认**零调用点**（仅定义，
  MessageQueue.java:112-130），是死代码。列为清理项，不是 bug。
- **首轮 #9「`BotSpawnThrottle` 钉住线程」**：虚拟线程 sleep，且 `rollWindowIfDue`
  （:134-148）已把空闲恢复限制在单窗口，不构成缺陷。
- **首轮 #12「`releaseMovementLock` NPE/抛异常」**：逐处核对 5 个 try/finally
  （MovementCommands.java:122/379/453/527/573/726/769），每处 release 都对应同函数内
  的成功 acquire，lock 必然已存在，不可达。缺防御而非 bug。
- **首轮 #14「`Dispatcher` 异常永久丢失后续消息」**：部分撤回。`catch → return false`
  确实终止本轮 drain（Dispatcher.java:95-98），但消息取自 `primary` 队列且已在
  `getMessageNonBlocking` 时被消费；真实影响是本轮剩余 ≤31 条延后到下一个 2s tick，
  不是"永久丢失后续消息"。

其余降级项（首轮 #5/#7/#8/#10/#11/#13/#15）：真实但非"BUG" ——
#5 软上限溢出无功能损害、#7 该 reactor 监视器无竞争方、#8 所有 `getState()` 读取点
或在 `synchronized` 方法内或属 GM 手动路径、#10 `clearMoveIntent` 后 `hasMoveTarget`
变 false 是期望行为、#11 `BOT_BASE_ID = 20000` 与 legacy 回退同域且 companion 走
`ArtificialCharacters` 分类器不误命中、#13 构造期 client 已由 `setBotStats`
（BotGeneration.java:463-476）保证非 null、#15 注释与 WZ 一致性属文档问题
（实测 922010500 有 2 个 9300013、922010506 有 7 个，与 RELEASE_NOTES 一致、
与 `LudiStages.stage5AdmitsTo` 注释矛盾）。


---

## 四、审核结论汇总（2026-10）

### 4.1 判定为真 bug 并已修复（19 条）

| 编号 | 级别 | 根因（一句话） | 修法 |
|---|---|---|---|
| BUG-1 | P0 | 同图私聊被置 replyType=null，回答掉回地图气泡全图广播 | onDirectChat 一律透传 message.getChatType() |
| BUG-2 | P0 | 0 HP 被 isAlive() + shouldUse 双重封锁，无任何恢复路径 | shouldUse 允许 current==0；去掉 useNeededPotion 的 isAlive 前置 |
| BUG-3 | P0 | setLevel/setExp 绕过宿主 levelUp，AP/SP/HP 池永久缺失并落库 | 改走 companion.gainExp(...)（与另两条升级路径同一条正路） |
| BUG-4 | P0 | 召回窗口固定 100、写入无 TTL、维护组件未接线 | limit→200；insert 传 30 天 TTL；**归档接线列为遗留** |
| BUG-5 | P1 | targetIds 只含同图玩家，跨图 whisper 的回答完全失忆 | snapshotPerception 始终把本回合对话者计入 targetIds |
| BUG-6 | P1 | POSITION_REPAIR 要求 !mapObserved，与其可达前提互斥 | 删死分支（枚举值 + choose + grindTick 的 teleport） |
| BUG-7 | P1 | 邀请队列无 TTL，过期条目持续授权并命中宿主 NOT_FOUND | PartyInviteEntry 加 enqueuedAtMs + getPartyInvite 惰性过期（180s） |
| BUG-8 | P1 | USE 栏黑名单过滤，卷轴/回城卷在背包压力下被贱卖 | 改白名单：只有恢复 HP/MP 的物品可卖 |
| BUG-9 | P1 | 四转技能 master level 从未被授予，恒为 0，SP 永不消耗 | 学习即授予 master level（WZ 实测值 10），planSp/applySp 同步 |
| BUG-11 | P2 | stopForRest 不停 soloGrind，REST 期间 GrindBrain 照常战斗 | stopForRest 增补 soloGrind.stop() |
| BUG-12 | P2 | 快照硬编码 Set.of()/false，ActionValidator 两条规则永不触发 | 传真实值：trainingTarget 时门禁移动类动作；engaged=soloGrind.engaged() |
| BUG-13 | P2 | ACCEPT_PARTY 特例绕过 isBot 过滤 | 特例补 !isBot(pendingInviter) |
| BUG-14 | P2 | companion 共用 channel 共享 client，player 槽位互相覆盖 | loadPersistentBot 后 adoptPrivateClient（复用既有抽象） |
| BUG-15 | P2 | PLANNING_TIMEOUT 硬编码 12s，与可配 LLM 超时错配 | 从 SocialLlmConfig.timeoutMs() 派生并保底下限 |
| BUG-17 | P2 | 每个 companion 新建整套 LLM client + 5 个 JDBC 仓库 | createDefault 改单例（已核实配置仅在 onLoad 读取一次） |
| BUG-18 | P2 | lastProactiveByPlayer 只增不清 | 图上无玩家时清空（语义上跨图即失效，且只会让冷却更严） |
| BUG-19 | P1 | manuallyStopBot 缺判空，未注册 bot 直接 NPE 且被线程池吞掉 | 补 bot == null 前置，对齐同族两函数 |
| BUG-20 | P2 | 两个 has*Reactor 读方法无 synchronized，复合读非原子 | 补 synchronized，与写方同一监视器（mySealedSlot 同修法） |
| BUG-21 | P2 | update() 末尾无条件超时检查，终态被反复改回 TIMED_OUT | update() 开头对 COMPLETED/TIMED_OUT 早退 |
| BUG-22 | P2 | add 用 size() 判容量，O(n) 且非原子，上限可被突破 | AtomicInteger 影子计数，poll/decrement 成对 |
| BUG-23 | P2 | getReactItem 可返回 null，.getLeft() 直接解引用 | required == null 短路（一处覆盖三处解引用） |

### 4.2 判定不成立 / 不构成 bug（4 条）

| 编号 | 结论 | 证伪理由 |
|---|---|---|
| BUG-10 | 不成立 | `enqueueEvent` 覆写 sessionPlayerId 是**宣示过的语义**（javadoc "bypasses chat-session ownership"），party-invite 事件理应优先于聊天会话。A 被拒绝是预期所有权转移，非死锁；45s 后 sessionDeadline 到期即恢复。 |
| BUG-16 | 不构成 bug | companion 侧无砸卷/星力路径，`gearItem` 只读模板属性，同 itemId 多实例属性必然相同，"按槽位挑"与"按质量挑"等价。原文档定性有误，已订正；不为等价行为改代码（YAGNI）。 |
| BUG-23 的"必崩"说法 | 文档自身已正定性 | 原文已写"健壮性缺口（当前不可达），不是现行崩溃点"——结论正确，仍值得一行防御。 |
| 首轮证伪 7 条 | 全部成立 | 抽验 isAltarThrower / removeMessageIfConditionMet / releaseMovementLock 三条均与代码一致。 |

### 4.3 遗留项（本轮未做，需单独评估）

- **BUG-4 第 3 项：MemoryConsolidationService 接线。** 需要一个周期性维护入口；
  叠加 `existsWithTag` 的 O(n) 全表扫，收益/风险需单独评估。companion 数量级为个位数，
  不在本批最小改动范围内。
- **BUG-15 的 HTTP 取消语义。** `CompletableFuture.orTimeout` 不取消底层请求；
  需要取消语义，超出最小改动范围（`CompanionPlannerService.settings.timeout` 已与它同源，
  不会出现"等一个没人要的结果"）。

### 4.4 验证方式与已知基线

- 全部修复落在**插件侧**，未改宿主 `GMS083/gms-server`。
- 编译/测试在 worktree 隔离会话（`/tmp/wt-*`）中进行，未在原工程目录并发构建。
- 新增/改写测试：`CompanionSurvivalPolicyTest`、`CompanionCombatRecoveryPolicyTest`、
  `CompanionSpPlannerTest`、`CompanionPlannerContextBuilderTest`、`BotPartyQueueTest`、
  `BotTypeManagerStopGuardTest`、`BotEventBufferTest`。
- **基线对齐**：未修复的 HEAD（`a95b4a4`）在同一文件集下跑出 **1550 tests / 3 failures / 0 errors**；
  本批修复后为 **1564 tests / 3 failures / 0 errors**（+14 新测试，失败集与基线完全一致，
  均为既有失败：`OPQBotLifecycleTest.startScheduledTaskArmsBothBeforeDelegating`、
  `PqStageNudgeLockOrderTest.bothArrivalCallbacksRouteThroughTheDispatchedBridge`、
  `WalkShieldReleasePolicyTest.walkToReleasesThroughTheSeamAndNotInAFinally`）。
- **注意（与本批无关的既有破损）**：HEAD 上有 18 个 GCMoveSystem/PQ 测试文件引用了
  `BotMovementState.lastMapId`，而该字段在 HEAD 中名为 `lastMap`（类型 MapleMap），
  导致 `mvn test` 在 **test-compile 阶段即失败**。这是基线固有的破损，
  与本次修复无关；为跑通测试将它们（及其唯一依赖方 `PqOrphanProbeTest`）临时移出，
  基线与本批使用完全相同的文件集，故对比有效。
