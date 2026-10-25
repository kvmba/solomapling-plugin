package soloMapling.ArtificialPlayer.BotTypes.Ellin;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Ellin PQ ("Ellin Forest") with a real player leading.
 *
 * <p>The quest alternates between two instructions, and its own portals state both: a room is
 * either "kill what is here" or "break what is blocking the way". The bot answers whichever
 * applies to the room it is standing in, checking the same conditions the doors check - so if
 * the portal would refuse, the bot has already seen why.
 *
 * <p>The maze room is handled the way Ludi's tower is: its portals mostly lead back to where
 * the traveller came from, so the bot steps through and judges by whether it arrived anywhere
 * new.
 */
public class EllinPQBot extends PartyQuestBot {

    /** The NPC standing in the maze whose talk warps the team out of it. */
    private static final int MAZE_NPC = 2133001;

    public EllinPQBot(Character character) {
        super(character);
        dialoguePath = "EllinPQBotDialogue.yaml";
        botType = "EllinPQBot";
        questName = "EllinPQ";
        // The quest's reactor scripts resolve their player through client.getPlayer() when
        // they run, so this bot needs a client of its own rather than the shared one.
        BotGeneration.adoptPrivateClient(character);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        tickPartyQuest();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        return EllinPqData.isQuestRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return EllinPqData.RECRUIT_MAP;
    }

    /**
     * The chain door the quest advances by: every room's {@code east00} is the script gate
     * {@code party6_stage} (target 999999999), whose script warps into the next room once the
     * room's own condition holds (clear mobs, the broken spine). The script re-checks that
     * condition itself, so a bot arriving early is refused and retries.
     */
    @Override
    protected Portal scriptedForwardPortal() {
        var map = getChr().getMap();
        return map == null ? null
                : PartyQuestBot.scriptedPortalOf(map.getPortals(), "east00", "party6_stage");
    }

    @Override
    protected boolean workStage() {
        int mapId = getChr().getMapId();
        var map = getChr().getMap();

        // The maze's own door out: the NPC at the maze's centre warps the whole team to the
        // frog room when anyone talks to him - that is the only way past 930000300, whose
        // script portals all lead back into the maze itself. The bot asks once per visit;
        // the actual warp carries whoever is in the maze, the leader included.
        if (mapId == EllinPqData.MAZE_MAP) {
            askMazeExit();
        }

        // The frog room wants the Poison Golem's guards CAUGHT, not killed (NPC 2133001
        // hands out purifiers and grades 20 Monster Marbles), and a dead frog never drops
        // one - so the bot adds no swings there at all.
        if (mapId == EllinPqData.FROG_ROOM) {
            return false;
        }

        // The stone room's box yields the Purple Stone of Magic, and the leader cannot open the
        // way on without it (2133004 checks HIS inventory). Break the box, pick the stone up and
        // hand it to him - the same leader-checked transfer every other turn-in needs.
        if (mapId == EllinPqData.STONE_BOX_ROOM) {
            workStoneBox();
            return false;
        }

        // The altar room: the altar reactor is what spawns the Poison Golem, and the golem's
        // death is the run's clear condition. Both halves are needed - an altar nobody feeds
        // spawns nothing, and the golem fights back while it is up.
        if (mapId == EllinPqData.ALTAR_ROOM) {
            workAltar();
            return false;
        }

        // The spine blocks the way until it is broken, in the room that has it. The guard
        // cap bounds a stuck state walk; the sleep between hits is the player's repeat rate,
        // the same beat breakBoxInPlace uses - eight back-to-back swings read as a machine gun.
        int spine = PqActions.findReactorOid(getChr(), EllinPqData.SPINE_REACTOR);
        if (spine >= 0) {
            for (int guard = 0; guard < 8 && spine >= 0; guard++) {
                if (!PqActions.hitReactor(getChr(), spine)) return false;
                if (!soloMapling.ArtificialPlayer.BotHelpers.blockingSleep(
                        soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages.reactorSwingBeatMs())) {
                    Thread.currentThread().interrupt();
                    break;
                }
                spine = PqActions.findReactorOid(getChr(), EllinPqData.SPINE_REACTOR);
            }
            PqActions.boxFinishedPullTick(getChr()); // broken (or stuck): resume promptly
        }

        // Then whatever is standing in the room.
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());
        }
        return false;
    }

    /**
     * The combat beat must not fight here either: the frog room's guards die to ordinary
     * swings and have to be purified with the NPC's item instead, so a bot that "helps" by
     * swinging on the sweep would kill what the party came to catch.
     */
    @Override
    protected boolean fightsOnSweep() {
        return getChr().getMapId() != EllinPqData.FROG_ROOM;
    }

    /**
     * Break the stone room's boxes, pick the Purple Stone up, and put it in the leader's hands.
     *
     * <p>The exit NPC ({@code 2133004}) checks {@code cm.haveItem(4001163)} on whoever talks to
     * it, so the stone has to be in the leader's inventory - a bot that keeps it stops the whole
     * party. The stone itself drops from the box reactors in this room.
     */
    private void workStoneBox() {
        Character bot = getChr();
        int box = PqActions.findReactorOid(bot, EllinPqData.STONE_BOX);
        if (box >= 0) {
            if (PqActions.approachReactor(bot, box, false, true) == PqActions.Approach.IN_POSITION) {
                PqActions.hitReactor(bot, box);
            }
            return;
        }
        // Box gone (or not in this room's copy): sweep what it left and put it in his hands.
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{EllinPqData.PURPLE_STONE});
        PqActions.handItemsToLeader(bot, EllinPqData.PURPLE_STONE);
    }

    /**
     * Feed the altar if this bot happens to be holding the stone, then fight whatever is here.
     *
     * <p>The altar is an item reactor ({@code wz/Reactor.wz/3001000.img.xml}: type 100, wants
     * {@code 4001163}x1) whose script spawns the Poison Golem - and the golem's death is the
     * run's clear condition. The room has no other source of monsters, so an unfed altar means
     * an empty room.</p>
     *
     * <p>The stone itself is the PARTY's single copy: {@code 930000500} holds one box reactor
     * ({@code 3002001}, drop chance 1) and no more, and the leader has to be carrying it to get
     * past {@code 2133004} at all. So in the normal flow he keeps it and drops it on the altar
     * himself; the feed here is the fallback for the case where the hand-off could not complete
     * (he was out of reach, or his inventory was full) and the bot arrived still holding it. A bot
     * that kept the stone instead of handing it over would strand the party at the exit NPC, which
     * is why {@code workStoneBox} gives it up first.
     */
    private void workAltar() {
        Character bot = getChr();
        int altar = PqActions.findReactorOid(bot, EllinPqData.ALTAR);
        if (altar >= 0 && PqActions.countItem(bot, EllinPqData.PURPLE_STONE) > 0) {
            PqActions.feedReactor(bot, altar, EllinPqData.PURPLE_STONE);
        }
        PqActions.seekAndAttack(bot);
    }

    /**
     * Ask the maze's NPC (2133001) to open the way out; retry while still in the maze.
     *
     * <p>The script warps the whole team to the frog room on the confirmation click, so the
     * bot's talk is not self-serving - it is the door. Clicks are paced by the engine's own
     * 500ms throttle inside {@code PqActions.talkTo}.
     */
    private void askMazeExit() {
        // A successful confirmation warps the team synchronously. While still here, retry:
        // an unavailable NPC or a refused start must not latch this or a later run shut.
        PqActions.talkTo(getChr(), MAZE_NPC, 0);
    }
}
