# Trade integration (phase 3) — implemented

Status: **implemented** on BeiDou host + solomapling-plugin.

## Before

`Trade.java` embedded artificial-character branches and a shared `PendingTradeInvites` queue that the plugin polled.

## After

### Host

- Publishes `TradeInviteEvent(invited, inviter)` after a successful invite setup
- Consults `TradeParticipantHook` via `HostHooks.trade*`:
  - `autoAcceptVisit` — force-accept when partner is headless
  - `relaxInventoryChecks` / `suppressTradePackets`
  - `onExchangeSuccess` — claim completion instead of `completeTrade()` (host still fires `TradeResultCallback`)
- No `PendingTradeInvites`; no `import soloMapling`

### Plugin

- Registers `SoloMaplingTradeParticipantHook` in `onLoad`
- `BotTradeInviteBridge` → `BotTradeQueue` (plugin-local)
- `BotTradeLogic` / `DropGameBot` drain `BotTradeQueue`

## Non-goals (unchanged)

- Full `HostTrade` facade
- Mixin-based Trade injection
