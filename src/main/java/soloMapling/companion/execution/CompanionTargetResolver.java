package soloMapling.companion.execution;

import org.gms.client.Character;
import soloMapling.companion.agent.CompanionAction;

import java.util.Optional;

/**
 * Per-decision authority boundary for engine targets.
 *
 * <p>Implementations must resolve only characters and maps included in the
 * validated perception snapshot. The executor never falls back to global,
 * channel, map, or character storage lookups.</p>
 */
public interface CompanionTargetResolver {

    Optional<Character> resolveCharacter(int characterId);

    /**
     * Action-specific authority hook. Implementations may narrowly authorize a
     * target for one action without exposing it to ordinary target resolution.
     */
    default Optional<Character> resolveCharacterFor(
            CompanionAction.ActionType actionType, int characterId) {
        return resolveCharacter(characterId);
    }

    boolean allowsMap(int mapId);
}
