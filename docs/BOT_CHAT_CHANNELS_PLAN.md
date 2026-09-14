# Bot 聊天频道接收/回复方案（组队 · 私聊 · 好友/公会/联盟）

> 状态：**已实现（Phase A + Phase B）**，宿主 GMS083 与插件两侧均已落地并编译/测试通过。
> 目标：让插件 bot 能像真玩家一样**收到并回复**组队频道、私聊，以及好友/公会/联盟聊天。
> 范围（用户已确认）：**组队 + 私聊 + 好友/公会/联盟**；**跨图、跨频道都要收到**；**允许同时修改宿主 GMS083 与插件**。
> （不含夫妻聊天 `SpouseChatHandler` 与宠物聊天 `PetChatHandler`，见 §2 备注。）

## 实现摘要（与下方方案的出入，以代码为准）

- 枚举名最终定为 **`ChatType`**（对齐宿主 `MULTI_CHAT` 包里的 `type`），不是 `ChatScope`；字段/形参一律叫 `type`。
- 回复频道复用：`BotSM` 上新增 `replyType` / `replyTarget` 与 `enterReplyChannel` / `leaveReplyChannel` / `sayReply` 及可覆写的 `replyChannel()` / `replyChannelTarget()`。`SocialBot` 直接用字段（单会话、tick 线程）；`CompanionBot` 覆写为逐回合 `ThreadLocal`；`FollowerBot`/`TrainingBot` 因始终与玩家同图，走**同图气泡**回复，不进频道回复路径（见 §3.5 备注）。
- 伴侣（`CompanionBot`）仅当发送者**跨图**时才武装回复频道（同图已有气泡，武装会污染后续环境台词）。因为它的回合在**各自虚拟线程**上并发执行（同一玩家可叠两个回合；组队邀请事件回合绕过会话归属），回复频道**随排队的消息本身携带**（`TurnCoordinator.Message.replyType`）——频道属于该条消息的回合，既不串入他人回复、也不因消息被拒而残留，无需按玩家的旁表或清理；回合内由 `ThreadLocal` 承载给该回合的 `Say`。
- `BotSM.directInbox` 为**有界**队列（128，`ArrayBlockingQueue` + `offer`）：玩家可私聊任意可见角色，而被停用的 bot 永不排水、未观察的刷怪 bot 排水间隔可达数分钟，无界队列会被刷爆。
- `SocialBot` 的延迟回复（`speakAfterBeat`、`doGoodbye`）在**构建时捕获**频道（会话随后即 `resetConversation` 清空），不再在延迟回调里读 bot 级字段。
- `TrainingBot`、`FollowerBot` 也覆盖 `onDirectChat`：二者回复走**同图气泡**，故仅在**发言者同图**时接受（跨图说话者回复不可达，不武装悬空菜单）。
- `CompanionActionExecutor.HostEngineAdapter.say` 改为优先走 `bot.sayReply(...)`（拿 `CharacterStorage.getBotById`），无 BotSM 时回落地图气泡。
- `FollowerBot.onDirectChat` 在**发言者同图**时直接复用 `offerKeyword`（不设回复频道；跨图说话者不武装悬空菜单）。
- 宿主侧 `publishToArtificialRecipients` 用四个收件集解析器（buddy 走 `containsVisible`、party 走 `PartyCharacter.getPlayer`、guild 走 `Guild.getMembers` + online、alliance 走 `Alliance.getGuilds`），与引擎实际广播范围一致。

---

## 0. 结论先行

1. **根因不是"bot 不回应"，而是这些消息根本没有进入插件**。宿主只在 `GeneralChatHandler` 里发布了 `CharacterChatEvent`（地图公共聊天）；私聊、组队、好友、公会、联盟的处理器**只调用 `sendPacket` 投递，从不发布任何事件**。
2. 宿主其实**找得到** bot —— bot 注册进了 `world.getPlayerStorage()` 与频道 storage，`getCharacterByName`/`getCharacterById` 都能解析到它，`sendPacket` 也确实发到了 bot 的 client。但 bot 的 headless `BotClient.sendPacket` 是**空实现**，包发出即丢弃。玩家侧表现为"消息没报错也没退信，但 bot 毫无反应"。
3. 插件侧同样只有 `CHAT_GENERAL` 一条线：`EventType` 无对应枚举、`HostGameplayEventBridge` 只订阅 `CharacterChatEvent`、`PlayerChatBridge` 只把公共聊天灌进 `primary`。**宿主没发的事件，插件无从收到**。
4. 因此修复必须**两侧同时动**：宿主为这些频道**发布事件**，插件**新增事件类型 + 桥 + 每-bot 收件箱**。且因为组队/私聊本就跨图，**不能复用按"发起者地图"寻人的 `Dispatcher` 主路径**——需要一条**按目标 bot 直接投递**的新路径（现有 `secondary` 队列是全局单消费者，无法寻址单个 bot）。

---

## 1. 根因（证据）

### 1.1 宿主：只有公共聊天发布事件

`gms-server/src/main/java/org/gms/net/server/channel/handlers/GeneralChatHandler.java:71-73`

```java
if (!HostHooks.isArtificial(chr)) {
    HostHooks.publish(new CharacterChatEvent(chr, s));   // 唯一发布点
}
```

全仓 grep `HostHooks.publish` / `events().publish`（除生命周期事件）确认其余聊天处理器**零发布**：

| 处理器 | 投递方式 | 发布事件？ |
|---|---|:--:|
| `WhisperHandler.handleWhisper`（私聊） | `target.sendPacket(getWhisperReceive(...))`（:105） | ❌ |
| `MultiChatHandler`（好友/组队/公会/联盟） | `world.buddyChat` / `world.partyChat` / `Server.guildChat` / `allianceMessage`（:64-77） | ❌ |
| `SpouseChatHandler`（夫妻） | `spouse.sendPacket(OnCoupleMessage(...))`（:41） | ❌ |
| `PetChatHandler`（宠物） | `getMap().broadcastMessage(petChat(...))` | ❌ |

### 1.2 bot 的 client 是"黑洞"

`gms-server/src/main/java/org/gms/client/BotClient.java:38-41`

```java
@Override
public void sendPacket(Packet packet) {
    // no socket
}
```

bot 注册在 `world.getPlayerStorage()`（`BotGeneration.addBotToServer`），所以上述 `getCharacterByName` / `getCharacterById` 都能命中 bot，包发到 `BotClient` 后**被静默丢弃**。

### 1.3 插件：只有 CHAT_GENERAL 一条线

- `EventType.java`：只有 `CHAT_GENERAL`，无私聊/组队/公会等。
- `HostGameplayEventBridge.java:28`：只 `subscribe(CharacterChatEvent.class, ...)`。
- `PlayerChatBridge.java:27`：只订阅 `CHAT_GENERAL` → 写入 `primary`。
- `Dispatcher.java:59`：只从 `primary` 取消息，且用 `message.getMap().getCharacters()` **按发起者所在地图**寻人。

### 1.4 现有寻人路径的局限

`Dispatcher.processMessages` 以"发起者地图"为维度；公共聊天双方必然同图，成立。但**私聊/组队是跨图跨频道**的，按地图找不到目标 bot。同时 `MessageQueue` 的 `secondary`/`tertiary` 都是**全局单消费者**队列（`getMessageWithTimeout("secondary", ...)` 被 14 个 bot 类型共用），谁先 poll 谁拿走——**无法用来寻址某一个 bot**。

---

## 2. 范围

| 频道 | 宿主处理器 | 是否纳入 |
|---|---|:--:|
| 组队 chat | `MultiChatHandler` type=1 | ✅ |
| 好友 chat | `MultiChatHandler` type=0 | ✅ |
| 公会 chat | `MultiChatHandler` type=2 | ✅ |
| 联盟 chat | `MultiChatHandler` type=3 | ✅ |
| 私聊 whisper | `WhisperHandler` | ✅ |
| 夫妻 chat | `SpouseChatHandler` | ❌（备注：结构独立，后续按同一模式可加） |
| 宠物 chat | `PetChatHandler` | ❌（宠物 chat 是"宠物说话"，非人际频道） |
| 地图公共 chat | `GeneralChatHandler` | ✅ 已实现（现状即基准） |

**"群频道"= 好友/组队/公会/联盟**（客户端同一入口 `MULTI_CHAT`，`type` 0/1/2/3 区分）。

---

## 3. 设计

### 3.1 宿主：新增"定向聊天"事件（按收件人，且仅收件人是 bot 时发布）

复用现有做法（`CharacterMapEnteredEvent` 按角色、`PartyInviteEvent` 在处理器内联发布，`InMemoryHostEventBus` 同步派发）。

在 `org.gms.extension.event` 新增：

```java
// ChatType.java
public enum ChatType { WHISPER, BUDDY, PARTY, GUILD, ALLIANCE }

// CharacterDirectChatEvent.java
public record CharacterDirectChatEvent(
        Character sender, Character recipient, String message, ChatType type) implements HostEvent {}
```

**发布策略：按"收件人是人工角色"逐条发布**，与 `GeneralChatHandler` 只发真玩家一致——只不过这里过滤的是**收件人**（不是发送者），因为 a) bot→玩家/旁观玩家时不该产生事件；b) bot→bot 私聊时，收件 bot 仍应收到。

**为什么不带"已解析的收件人列表"而是逐条发**：`CharacterDirectChatEvent` 天然是"一人一份"，插件桥无需重复宿主的公会/联盟成员遍历逻辑（那些遍历在 `World.partyChat` / `Server.guildChat` / `allianceMessage` 内部，且跨频道有快照/加锁细节）。逐条发布也把开销限定在"收件人是 bot"的极少数情况。

### 3.2 宿主：各处理器发布点（内联，紧邻现有投递）

| 文件 | 位置 | 改动 |
|---|---|---|
| `WhisperHandler.handleWhisper` | :105 之后 | `if (HostHooks.isArtificial(target)) HostHooks.publish(new CharacterDirectChatEvent(user, target, message, ChatType.WHISPER));` |
| `MultiChatHandler` | 各分支投递之后 | type=0/1/2/3 分别对"收件集合"中 **`HostHooks.isArtificial`** 的成员逐条发布（type= BUDDY/PARTY/GUILD/ALLIANCE）。收件集合：好友=`recipients[]` 现解析、组队=`player.getParty().getMembers()`、公会=`Server.getGuild(gid).getMembers()`、联盟=`alliance.getGuilds()` 各公会成员 |

> 提示：`MultiChatHandler` 里好友/组队/公会/联盟的成员枚举，建议抽一个本类私有静态 `publishToArtificialRecipients(sender, recipients, message, type)`，避免四处重复 `isArtificial` + 发布（**最小改动**：宿主本就已有这些集合，只是多一次遍历过滤）。

### 3.3 插件：桥 → 事件类型 → 每-bot 收件箱

1. **`EventType`** 增加：`CHAT_WHISPER, CHAT_BUDDY, CHAT_PARTY, CHAT_GUILD, CHAT_ALLIANCE`。
2. **`HostGameplayEventBridge`** 订阅 `CharacterDirectChatEvent`，映射为 `GameEvent`（**需要携带 type**，见 §3.4）。**实现**：给 `GameEvent` 加一个可空 `ChatType type` 字段（构造重载，保持旧调用不变）。
3. **新桥 `DirectChatBridge`**（与 `PlayerChatBridge` 并列）：订阅上述事件 → `CharacterStorage.getBotById(recipient.getId())` → 命中则 `bot.postDirectChat(new ChatMessage(sender, content, type))`。
   - **关键**：不经 `Dispatcher`、不进共享 `primary`。因为私聊/组队跨图，`Dispatcher` 的地图寻人失效；且共享队列无法寻址单个 bot。

### 3.4 插件：每-bot 收件箱（新原语）

在 `BotSM` 增加一个**按目标 bot 的小收件箱**与钩子（这是本方案唯一的新抽象，**必要性**：现有 3 个队列全是全局单消费者，无法把一条消息投给指定 bot；私聊/组队又跨图，`Dispatcher` 的地图维度也不适用）：

```java
private final java.util.concurrent.ConcurrentLinkedQueue<ChatMessage> directInbox =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

/** 由桥在宿主包线程调用：只入队，不做重活。 */
public void postDirectChat(ChatMessage m) { if (m != null) directInbox.add(m); }

// tickRunnable 内、updateState() 之前，排空（限流）
protected void drainDirectChat() {
    for (int i = 0; i < MAX_DIRECT_CHAT_PER_TICK && !directInbox.isEmpty(); i++) {
        onDirectChat(directInbox.poll());
    }
}

/** 子类按需覆盖。默认 no-op（多数站桩 bot 不需要回复远方消息）。 */
protected void onDirectChat(ChatMessage m) { }
```

`ChatMessage` 增加可空 `ChatType type`（新构造重载，旧签名保持，`type=null` 表示地图公共聊天）。

**各类型覆盖 `onDirectChat`：**

| bot 类型 | 覆盖行为 |
|---|---|
| `CompanionBot` | 直接复用现有 AI 管线：`enqueuePlayerMessage(sender, content)`（其 `TurnCoordinator` 已支持跨图感知与节流，见 §3.4 备注） |
| `SocialBot` | 复用现有 respondant 流程：`getInteractors().setRespondant(sender)` + `onFirstInteraction(sender)`（若是首次）/ 直接作为一条来自 respondant 的消息处理 |
| `FollowerBot`（组队 bot 的主要形态） | 新增最小处理：登记 respondant 并触发一次问候/回应 |
| 其余（商人/站桩/游戏桌等） | 默认 no-op（不显示为"会私聊的 bot"） |

> **CompanionBot 备注**：其 `acceptsContinuation` 通过 `CompanionInteractionPolicy.allowsContinuation` 判定"同一地图"（`Dispatcher.deliverToPartyBots` 里也用它做同图过滤）。跨图私聊若要喂给伴侣，需要**放宽同图限制**（只在 whisper 场景），否则伴侣会因 `companion.getMapId() != player.getMapId()` 拒收。这一点要在 Phase A 一并决定（建议：whisper 允许跨图，party 依 party 关系）。

### 3.5 插件：频道感知回复（Phase B）

现状：bot 回话一律走 `SocialCommands.BotSpeak` → `getMap().broadcastMessage(getChatText(...))`（**地图广播**）。对远方的私聊/组队玩家，这个气泡他们**看不到**（跨图），而且会被同图旁人误当成地图聊天。

因此需要**按 type 选择发包**。新增 `SocialCommands` 三个发射器（复用宿主既有 `PacketCreator` / `World` 方法）：

```java
BotReplyWhisper(chr, target, msg);      // target.sendPacket(getWhisperReceive(chr.getName(), chr.getClient().getChannel() - 1, false, msg))
BotReplyParty(chr, msg);                // chr.getWorldServer()... world.partyChat(chr.getParty(), msg, chr.getName())
BotReplyGuild(chr, msg);                // Server.getInstance().guildChat(chr.getGuildId(), chr.getName(), chr.getId(), msg)
BotReplyBuddy(chr, target, msg);        // world.buddyChat(new int[]{target.getId()}, chr.getId(), chr.getName(), msg)
BotReplyAlliance(chr, msg);             // Server.getInstance().allianceMessage(allianceId, multiChat(chr.getName(), msg, 3), chr.getId(), -1)
BotReply(chr, type, target, msg);      // 按 type 分发；type==null 回落 BotSpeak（地图）
```

> 注意：这些是**bot 作为发送方向玩家投递**，包发给**玩家的真实 client**，所以能到；bot 自己及其他 bot 的 `sendPacket` 是空实现，无副作用；且这些是程序化调用（非 handler 路径），**不会回环**进 §3.3 的入站事件。

然后在 bot 的"回应"链路接入 `BotReply(chr, type, sender, line)` 取代 `BotSpeak`。落点集中在少数几个 choke point：

- `SocialBot`：`onFirstInteraction` / `appendGreeting` / `appendSingleResponse` / `handlePlayerMessage` / `doReducedResponse` / `handlePartyAsk`（**不包括**环境闲聊 `BotChatter`、随机表情等——那些本就该留在地图气泡）。
- `CompanionBot`：`CompanionActionExecutor` 执行 `Say` 动作时按 type 发包。
- bot 的回复频道由 `onDirectChat` 设置、在会话重置时清空（实现落在 `BotSM`：`replyType`/`replyTarget` + `enterReplyChannel`/`leaveReplyChannel`/`sayReply`）。

> **提示气泡的限制**：SocialBot 的选项菜单用 `chr.getClient().sendPacket(sendHint(...))`（bot client 空实现），**跨图私聊下玩家看不到选项菜单**。因此跨图私聊只做"问候 + 简短回复"，不呈现菜单；这是可接受的降级（菜单本就是同图社交的表现形式）。若要菜单私聊，需另发 `getWhisperReceive` 承载选项文本，属后续增强。

---

## 4. 逐文件改动清单

### 4.1 宿主 GMS083（`/workspace/GMS083`）

| 文件 | 改动 |
|---|---|
| `gms-server/.../extension/event/ChatType.java` | **新增** enum |
| `gms-server/.../extension/event/CharacterDirectChatEvent.java` | **新增** record |
| `.../channel/handlers/WhisperHandler.java` | `handleWhisper` :105 后按收件人是人工角色发布（type=WHISPER） |
| `.../channel/handlers/MultiChatHandler.java` | :64-79 各分支后对人工收件人逐条发布；抽 `publishToArtificialRecipients` + 四个收件集解析器 |
| （可选）`.../extension/README.md`、`solomapling-plugin/docs/HOST_BOUNDARY.md` | 更新事件清单 |

> **不改** `GeneralChatHandler`（地图公共聊天已通）。**不改** `CharacterChatEvent`（保持向后兼容，新增独立事件更清晰）。

### 4.2 插件 solomapling-plugin

| 文件 | 改动 |
|---|---|
| `server/EventMessageSystem/EventType.java` | +5 枚举 |
| `server/EventMessageSystem/GameEvent.java` | +可空 `ChatType type`（构造重载） |
| `plugin/HostGameplayEventBridge.java` | 订阅 `CharacterDirectChatEvent` |
| `ArtificialPlayer/BotMessagingSystem/DirectChatBridge.java` | **新增**：事件 → `bot.postDirectChat` |
| `ArtificialPlayer/BotMessagingSystem/ChatMessage.java` | +可空 `ChatType type`（重载） |
| `plugin/SoloMaplingExtension.java` | `onLoad` 中 `DirectChatBridge.register(runtime)` |
| `ArtificialPlayer/BotSM.java` | +`directInbox`/`postDirectChat`/`drainDirectChat`/`onDirectChat`；tick 内排空 |
| `ArtificialPlayer/BotTypes/CompanionBot.java` | override `onDirectChat` → `enqueuePlayerMessage` |
| `ArtificialPlayer/BotTypes/SocialBot.java` | override `onDirectChat` → respondant 流程 |
| `ArtificialPlayer/BotTypes/FollowerBot.java` | override `onDirectChat`（最小回应） |
| `BotCommandsPack/SocialCommands.java` | Phase B：`BotReply*` 发射器 |
| （Phase B）上述 bot 的回应 choke point | 用 `BotReply` 取代 `BotSpeak` |
| `docs/BOT_CHAT_CHANNELS_PLAN.md` | 本文 |

---

## 5. 分阶段落地

### Phase A · 接收（修"收不到"）— ✅ 已实现
1. 宿主新增 `ChatType` / `CharacterDirectChatEvent` + 两处发布。
2. 插件新增 `EventType` / `GameEvent.type` / `DirectChatBridge` / `ChatMessage.type` / `BotSM` 收件箱。
3. `CompanionBot` / `SocialBot` / `FollowerBot` 覆盖 `onDirectChat`（**回复仍走现有 `BotSpeak`**，即地图气泡）。
4. **验收**：同图组队频道发言，组队 bot 有反应；异图私聊 bot，bot 经其会话流程产生回应（同图可见气泡）。
   - 此阶段即已修复"**完全收不到**"的核心缺陷。

### Phase B · 频道感知回复（修"反馈看不到"）— ✅ 已实现
5. `SocialCommands.BotReply*` + `BotReply` 分发。
6. 在回应 choke point 接入 type，远图私聊/组队按频道回包。
7. **验收**：异图私聊 bot，玩家在私聊窗收到 bot 回复；异图组队频道发言，玩家在组队频道收到回复，且同图旁人**不会**误见为地图聊天。

### Phase C · 可选扩展（未实现）
8. 夫妻/宠物按同一模式补 `SpouseChatEvent` / `PetChatEvent`（若需要）。
9. 跨图私聊的选项菜单承载（用 `getWhisperReceive` 发送选项文本）。

---

## 6. 坑点与边界

1. **共享队列不可寻址单个 bot**：`MessageQueue.secondary/tertiary` 是全局单消费者，绝不能拿来做定向投递（会串话/丢话）。定向一律走 §3.4 的每-bot 收件箱。
2. **跨图过滤**：`CompanionBot.acceptsContinuation` / `Dispatcher.deliverToPartyBots` 均带"同图"判定；私聊要跨图，必须在 `onDirectChat` 的路径上**绕过**这些同图门槛，否则收不到。
3. **发送者过滤 vs 收件人过滤**：地图聊天过滤**发送者**（`!isArtificial`），定向聊天应过滤**收件人**（`isArtificial(recipient)`）。过滤错会把 bot 的程序化回复又喂回事件总线，形成回环。
4. **回环**：`BotReply*` 走 `World.partyChat` / `Server.guildChat` 等**直接投递**方法，不经聊天 handler，**不会**再次发布 `CharacterDirectChatEvent`——安全。但要确保不在这些方法外层额外触发发布。
5. **重复投递**：`MultiChatHandler` 若既发"已解析收件人列表事件"又发"逐条事件"会重复。**二选一**（本方案选逐条）。
6. **bot→bot 私聊**：收件 bot 仍应收到（过滤按收件人），注意其 `onDirectChat` 里 `isBot(sender)` 的处理——现有 `SocialBot.processMessages` 会 `if (isBot(sender)) return;` 丢弃 bot 之间的消息，跨图私聊要确认这是否是期望行为。
7. **事件同步派发在包线程**：`InMemoryHostEventBus.publish` 同步执行，`CharacterDirectChatEvent` → `DirectChatBridge` → `postDirectChat`（仅入队）必须**极轻**，禁止在桥里做发包/遍历。
8. **超长/空串/禁言**：沿用现有 `handleWhisper`/`MultiChatHandler` 的长度与节流校验（发布点放在校验**之后**），不要把非法消息带进事件。
9. **`ChatMessage` 构造**：`ChatMessage` 现有构造会用 `sender.getMap()` 取地图；跨图场景 `map` 字段可能不是 bot 所在图，定向路径**不应依赖 `ChatMessage.getMap()`**（只取 sender+content+type）。
10. **迁移兼容**：新增事件/枚举对旧部署无破坏（`CharacterChatEvent` 不变；`GameEvent` 加可空字段用重载，旧调用不变）。

---

## 7. 验收标准

| # | 场景 | Phase | 期望 |
|:--:|---|:--:|---|
| 1 | 同图、对组队 bot 发**组队频道**消息 | A | bot 有会话反应（不报错、不回退信） |
| 2 | 异图/异频道、**私聊**某个 bot | A | bot 收到并进入会话（同图可见其回应气泡） |
| 3 | 异图、**私聊** bot | B | 玩家在**私聊窗**收到 bot 回复 |
| 4 | 异图、组队频道对 bot 发言 | B | 玩家在**组队频道**收到回复；同图旁人**看不到**该回复 |
| 5 | 公会/联盟频道对在会 bot 发言 | A/B | 会内 bot 收到；回复按公会/联盟频道到达 |
| 6 | 地图公共聊天（回归） | — | 与现状完全一致（不受影响） |
| 7 | bot 之间的消息 | A | 按 §6.6 的既定语义，不产生回环/串话 |

---

## 附录 · 关键事实速查

| 事实 | 证据位置 |
|---|---|
| 宿主唯一聊天发布点 | `GeneralChatHandler.java:71-73`（`CharacterChatEvent`，仅公共聊天） |
| 私聊只 `sendPacket` 不发布 | `WhisperHandler.java:105` |
| 组队/好友/公会/联盟只投递不发布 | `MultiChatHandler.java:64-77` |
| bot 的 client 空实现 | `BotClient.java:38-41` |
| bot 注册进世界/频道 storage（能被 `getCharacterByName` 命中） | `BotGeneration.addBotToServer` |
| 插件只订阅 CHAT_GENERAL | `PlayerChatBridge.java:27`、`HostGameplayEventBridge.java:28` |
| 插件按"发起者地图"寻人 | `Dispatcher.java:59`（`message.getMap().getCharacters()`） |
| `secondary` 被 14 个 bot 类型共用（单消费者） | `MessageQueue.java` + 各 `processMessages` |
| CompanionBot 有现成跨图消息管线 | `CompanionBot.enqueuePlayerMessage` / `TurnCoordinator.enqueue` |
| CompanionBot 会话带同图门槛 | `CompanionInteractionPolicy.allowsContinuation` |
| 现有事件内联发布范例（可选遵循） | `PartyOperationHandler.publishInvite` :141-149 |
| 事件为同步内存派发 | `InMemoryHostEventBus.publish` |
