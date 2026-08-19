package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.extension.api.ArtificialCharacters;
import org.gms.extension.api.TradeParticipantHook;
import org.gms.extension.api.TradeParticipants;

/**
 * Registers SoloMapling trade semantics with the host so {@code Trade} never imports plugin packages.
 */
public final class SoloMaplingTradeParticipantHook implements TradeParticipantHook {

    public static final SoloMaplingTradeParticipantHook INSTANCE = new SoloMaplingTradeParticipantHook();

    private SoloMaplingTradeParticipantHook() {
    }

    public static void register() {
        TradeParticipants.register(INSTANCE);
    }

    public static void unregister() {
        TradeParticipants.unregister(INSTANCE);
    }

    @Override
    public boolean autoAcceptVisit(int visitorId, int partnerId) {
        // Match prior host behavior: force-accept when the visit partner is headless.
        return ArtificialCharacters.isArtificial(partnerId);
    }

    @Override
    public boolean relaxInventoryChecks(int characterId) {
        return ArtificialCharacters.isArtificial(characterId);
    }

    @Override
    public boolean suppressTradePackets(int characterId) {
        return ArtificialCharacters.isArtificial(characterId);
    }

    @Override
    public boolean onExchangeSuccess(int characterId) {
        // Host still fires TradeResultCallback on the Trade object; we only claim the path.
        return ArtificialCharacters.isArtificial(characterId);
    }
}
