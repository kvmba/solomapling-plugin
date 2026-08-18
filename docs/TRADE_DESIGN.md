# Trade integration design (phase 3)

Status: **design only** — phases 1–2 left trade on host APIs (`HostHooks.isArtificial` + `PendingTradeInvites`).

## Problems with the current model

`Trade.java` still embeds artificial-character branches:

1. Inventory / meso checks relaxed for artificial chars  
2. On complete: real players `completeTrade()`, bots get `setCallbackSuccessfulTrade()`  
3. Invite path: if target is artificial, enqueue `PendingTradeInvites` and `visitTrade` treats bot as auto-accepted  

The plugin’s FSM (`BotTradeSM` / `BotTradeLogic`) polls `PendingTradeInvites` and drives accept/decline. That works, but:

- Host knows “bot trade semantics”  
- Queue is a shared mutable side channel  
- Hard to test / extend (buying merchants, drop-game trades, etc.)

## Design goals

1. Host never imports SoloMapling; ideally never special-cases “bot complete” beyond a registered hook  
2. Plugin owns the full trade FSM  
3. Prefer **events + small hook interface** over a shared queue  
4. Keep compile-time `provided` thin jar (plugin may still call `Trade` / `Character` APIs)

## Proposed API

### A. Host events (publish-only)

```text
TradeInviteEvent(inviterId, invitedId)     // after InviteCoordinator.createInvite
TradeCancelledEvent(characterId, reason)
TradeCompletedEvent(characterId, partnerId) // after successful exchange (optional)
```

Use ids in `extension-api` where possible; BeiDou can also publish engine-typed records in `org.gms.extension.event` like `PartyInviteEvent` today.

### B. Host hook (plugin registers in onLoad)

```java
public interface TradeParticipantHook {
    /** Invited character has no client — should the invite be considered accepted for UI sync? */
    default boolean autoAcceptVisit(int invitedId, int inviterId) { return false; }

    /** Called instead of Character.completeTrade() for this participant when exchange succeeds. */
    default void onExchangeSuccess(int characterId) { }

    /** Skip inventory ownership checks for this character? */
    default boolean relaxInventoryChecks(int characterId) { return false; }
}
```

Host holds `CopyOnWriteArrayList<TradeParticipantHook>` (or a single optional hook via `HostRuntime`).

`Trade.java` becomes:

```text
if (hooks.anyMatch(h -> h.autoAcceptVisit(...))) { ... }
if (hooks.anyMatch(h -> h.relaxInventoryChecks(id))) { ... }
on success:
  for each side:
    if (hook.onExchangeSuccess) hook else completeTrade()
```

### C. Plugin-side rewrite

- Remove polling `PendingTradeInvites` from `BotTradeLogic.checkTradeQueue`  
- Subscribe to `TradeInviteEvent` → start / nudge `BotTradeSM` (same pattern as `BotPartyInviteBridge`)  
- Implement `TradeParticipantHook` for auto-accept + success callback into existing FSM  
- Delete or deprecate `PendingTradeInvites` once migration is done  

Optional later: plugin never calls `Trade.visitTrade` from the host invite path; host only fires the event and the plugin calls public trade APIs to accept when ready (closer to a real client).

## Migration steps

1. Add events + `TradeParticipantHook` registry on BeiDou (no behavior change yet)  
2. SoloMapling registers hook + event subscriber; dual-run with `PendingTradeInvites`  
3. Remove queue and `isArtificial` branches that are fully covered by the hook  
4. Regression: player↔player, player↔bot, bot decline/blocklist, DropGameBot trades  

## Non-goals

- Full HostEngine `HostTrade` facade (too large)  
- Mixin-based Trade injection (unnecessary while BeiDou is a controlled fork)

## Outcome

Trade becomes another **host capability** (events + participant hook), and SoloMapling’s trade FSM lives entirely in the plugin — matching party invite / map enter / chat after phases 1–2.
