# Release notes — SoloMapling Plugin (SPI)

## 0.3.0-SNAPSHOT (SPI packaging)

### Fork point

- **Upstream:** [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) (author: MadaraGameDev / Richy), AGPL-3.0.
- **What this is:** an architectural refactor of that tree into a **host SPI plugin jar**, not a competing “official” SoloMapling distribution.
- **Base idea:** keep the SoloMapling bot framework; stop shipping it as a Cosmic fork.

### Changes vs upstream SoloMapling

| Kept | Changed |
|------|---------|
| `soloMapling/**` framework (bots, dialogue, FM, env waves, …) | Packaged as `ServerExtension` jar for `plugins/` |
| SoloMapling brand in package/artifact ids | Title/docs marked **Plugin / SPI** to avoid “official replacement” confusion |
| AGPL-3.0 + attribution ([NOTICE](NOTICE)) | Host boundary: chat / map / trade / party hooks on the host; plugin bridges subscribe |

### Host wiring (BeiDou)

- Chat: host `CHAT_GENERAL` → plugin `PlayerChatBridge` → primary message queue.
- Party invite: host `PartyInviteEvent` → plugin `BotPartyInviteBridge` → `BotPartyQueue`.
- Trade / artificial-character skips remain on the host (`BotTradeQueue`, `BotHelpers`).

### Credits

Please credit **MadaraGameDev / Richy** and link [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) in demos, videos, and write-ups. Prefer upstream showcase media when describing SoloMapling’s capabilities.
