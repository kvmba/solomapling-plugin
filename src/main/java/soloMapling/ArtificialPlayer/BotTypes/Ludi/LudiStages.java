package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.List;

/**
 * What a bot does in each Ludi PQ stage.
 *
 * <p>Most of the quest is the same instruction repeated: the room holds monsters, they drop
 * passes, and the stage NPC wants a set number of them. Those stages are one method.
 *
 * <p>Two are different. Stage 6 is a tower whose portals mostly throw the climber back down,
 * so progress comes from trying them - the bot walks the row rather than pretending to know
 * which one is right. Stage 8 wants exactly five people standing on five specific crates out
 * of nine, and the quest has already written down which five; the bot reads that and takes
 * its share, leaving room for the rest of the party.
 */
public final class LudiStages {

    private LudiStages() {
    }

    /** A stage is done when the quest says so; these are its own per-stage flags. */
    public static boolean stageCleared(Character bot, int stage) {
        return PqActions.readEimString(bot, stage + "stageclear") != null;
    }

    // =========================================================================
    // The collection stages
    // =========================================================================

    /**
     * Fight what is in the room and gather this stage's passes.
     *
     * <p>The bot attacks, loots its share, and then hands its whole stock of passes to the
     * party leader by dropping them at his feet: the stage NPC checks the inventory of
     * whoever talks to him, and that is the leader - a bot that keeps its share starves the
     * turn-in and the party stalls on the stage.
     */
    public static void gatherPasses(Character bot, int stage) {
        int wanted = LudiPqData.passesWanted(stage);
        if (wanted > 0 && PqActions.countItem(bot, LudiPqData.PASS) >= wanted) {
            return; // this bot is carrying its share; more hands are not needed
        }
        PqActions.attack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        if (PqActions.handItemsToLeader(bot, LudiPqData.PASS) > 0) {
            PqActions.say(bot, "PASSES! I have dropped the passes at the leader's feet.");
        }
    }

    // =========================================================================
    // Stage 6 - the climb
    // =========================================================================

    /**
     * Work up the tower by trying its portals.
     *
     * <p>The portal row is laid out bottom to top, and stepping into one either lifts the
     * climber or drops him back where he started - the map data gives every portal the same
     * target, so there is nothing to read and no way to know in advance. The bot walks the
     * row and steps into each in turn, which is what a party does, and the map change that
     * follows tells it whether that worked.
     *
     * <p>Progress is judged by height: the tower is one map, so arriving higher up than
     * before is the only observable that distinguishes a working portal from a dead one.
     */
    public static boolean climbTower(Character bot, int previousY) {
        int hereY = bot.getPosition().y;
        for (int portalId = LudiPqData.CLIMB_PORTAL_FIRST;
             portalId <= LudiPqData.CLIMB_PORTAL_LAST; portalId++) {
            PqActions.takePortal(bot, portalId);
            PqActions.holdArea(bot, bot.getPosition(), 600);
            // A working portal leaves the bot higher than it was (the tower's y decreases as
            // it goes up); a dead one puts it back at the bottom.
            if (bot.getPosition().y < hereY) {
                return true;
            }
        }
        // Nothing lifted us this pass. The stage flag is the only other thing that can say
        // the climb is over - the party may have finished it while this bot was retrying.
        return stageCleared(bot, 6) || previousY > hereY;
    }

    // =========================================================================
    // Stage 8 - the crate combination
    // =========================================================================

    /**
     * Stand on this bot's share of the crates the quest asked for.
     *
     * <p>Exactly five people have to be on crates and the pattern has to match, so the bots
     * cannot simply take five boxes: the real player is one of the five the quest counts, and
     * a bot that filled his crate would make the count come out at five on the wrong boxes.
     * The bots therefore take from the far end of the list and leave the near end free.
     *
     * @return how many crates the plan wants, or -1 when the quest has not picked yet
     */
    public static int takeCrate(Character bot, int bodyIndex) {
        String combo = PqActions.readEimString(bot, LudiPqData.COMBO_PROPERTY);
        if (combo == null) {
            return -1;
        }
        List<Point> wanted = LudiPqData.cratesIn(combo);
        if (wanted.size() != LudiPqData.CRATES_TO_STAND_ON) {
            // The quest builds its combination as exactly five of nine; anything else means
            // the property was written by something other than the stage script.
            return -1;
        }
        Point mine = LudiPqData.myCrate(combo, bodyIndex);
        if (mine != null) {
            PqActions.holdArea(bot, mine, 1_200);
        }
        return wanted.size();
    }

    // =========================================================================
    // Stage 9 - the boss
    // =========================================================================

    /**
     * Fight the boss stage.
     *
     * <p>The stage ends when one Alishar trophy is in hand, and that drop belongs to whoever
     * killed him, so the bot only has to add damage.
     */
    public static void fightBoss(Character bot) {
        PqActions.attack(bot);
    }
}
