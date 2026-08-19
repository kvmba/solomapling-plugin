# Host boundary

## Goal

BeiDou engine code must not `import soloMapling.*`. SoloMapling remains a provided-thin-jar plugin that registers capabilities the host already exposes.

## What the host owns

- `ArtificialCharacters` + `CharacterClassifier` (`extension-api`)
- `TradeParticipantHook` + `TradeParticipants` (`extension-api`)
- `HostHooks.isArtificial` / `HostHooks.trade*` / `HostHooks.publish`
- Gameplay events: `CharacterMapEnteredEvent`, `CharacterChatEvent`, `PartyInviteEvent`, `TradeInviteEvent`
- Simulation APIs: `BotClient`, `BotTier`, movement/combat helpers

## What the plugin does on load

1. `ArtificialCharacters.register(id -> id > 20000 || id == 999)`
2. `TradeParticipants.register(SoloMaplingTradeParticipantHook)`
3. `HostGameplayEventBridge` — host map/chat events → internal `EventBus`
4. `PlayerChatBridge` / `BotPartyInviteBridge` / `BotTradeInviteBridge`
5. Register GM commands via `HostCommandRegistry`

## Trade (phase 3)

Host publishes `TradeInviteEvent` and consults `TradeParticipantHook`. Plugin owns `BotTradeQueue` + FSM. See [TRADE_DESIGN.md](TRADE_DESIGN.md).
