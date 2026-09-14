package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;
import org.gms.server.partyquest.MonsterCarnival;
import org.gms.util.Pair;

import java.awt.Point;

/**
 * Asking the arena to add a summoned monster, which a socketless bot cannot do by packet.
 *
 * <p>A player summons by sending the arena an index into the map's summon list;
 * {@code MonsterCarnivalHandler} then checks the cost and the side's allowance, spends the
 * allowance, and adds the monster itself. A bot has no socket to send that from, so this
 * performs the same steps directly - and has to perform all of them in the same order,
 * because skipping the allowance would hand the bot more summons than a player gets, and
 * skipping the CP check would hand it monsters it never paid for.
 *
 * <p>Kept out of the bot so the budget rules live in one place with the arena's own
 * definitions rather than being re-derived alongside the fighting logic.
 */
final class CarnivalSummons {

    private CarnivalSummons() {
    }

    /**
     * Spend CP on the monster at {@code index} of the arena's list and add it for this
     * character's side.
     *
     * @return true when a monster was actually summonable and has been added
     */
    static boolean request(Character bot, int index) {
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        var options = bot.getMap().getMobsToSpawn();
        if (options == null || index < 0 || index >= options.size()) {
            return false;
        }

        Pair<Integer, Integer> option = options.get(index);
        int cost = option.getRight();
        var carnival = bot.getMonsterCarnival();
        if (carnival == null) {
            return false;
        }

        // Same two gates the packet handler applies, in the same order: CP first, then the
        // side's remaining allowance. Getting this backwards would let a bot summon with
        // points it does not have.
        if (cost <= 0 || bot.getCP() < cost) {
            return false;
        }
        int team = bot.getTeam();
        boolean allowed = team == 0 ? carnival.canSummonR() : carnival.canSummonB();
        if (!allowed) {
            return false;
        }

        Monster mob = LifeFactory.getMonster(option.getLeft());
        if (mob == null) {
            return false;
        }
        Point spawn = bot.getMap().getRandomSP(team);
        if (spawn == null) {
            return false;
        }
        mob.setPosition(spawn);

        // Spend the allowance, then add. The handler does it in this order too, and the
        // charge is what keeps the next check honest.
        if (team == 0) {
            carnival.summonR();
        } else {
            carnival.summonB();
        }
        bot.getMap().addMonsterSpawn(mob, 1, team);
        return true;
    }

    /**
     * The Carnival Points a summon costs, so a caller can weigh it against what it has.
     * Returns -1 for an index the arena does not offer.
     */
    static int costOf(Character bot, int index) {
        if (bot == null || bot.getMap() == null) {
            return -1;
        }
        var options = bot.getMap().getMobsToSpawn();
        if (options == null || index < 0 || index >= options.size()) {
            return -1;
        }
        return options.get(index).getRight();
    }
}
