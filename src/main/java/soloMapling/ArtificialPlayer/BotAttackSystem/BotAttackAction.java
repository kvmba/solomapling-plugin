package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** One non-blocking owner for a body's attack decision, packet and damage application. */
public final class BotAttackAction {
    private static final Set<Integer> ACTIVE = ConcurrentHashMap.newKeySet();

    private BotAttackAction() {
    }

    public static <T> T tryRun(Character bot, boolean force, T refused, Supplier<T> attack) {
        if (bot == null || bot.getMap() == null || !ACTIVE.add(bot.getId())) {
            return refused;
        }
        try {
            // A movement tick may already hold its monitor when it retaliates. It must
            // never wait for another attack owner: that owner may be waiting for movement.
            return GCMovement.runAttackAction(bot, force, refused, () -> {
                BotDebuffState status = BotDebuffState.of(bot);
                return !force && status != null && status.blocksAttack() ? refused : attack.get();
            });
        } finally {
            ACTIVE.remove(bot.getId()); // no lock registry survives the in-flight attack
        }
    }
}
