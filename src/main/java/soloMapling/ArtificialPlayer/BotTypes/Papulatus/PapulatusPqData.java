package soloMapling.ArtificialPlayer.BotTypes.Papulatus;

/**
 * Vs Papulatus (帕普拉图斯, the "闹钟" boss of the Time Sphere) as its own scripts define it.
 *
 * <p>Two maps and one room: the party is recruited in the lobby under the clocktower
 * ("时间塔的隐秘场所"), and the whole run happens in the machine room the lobby's gate leads
 * to ("时间塔的本源"). The room starts <em>empty</em> - there is no monster in its WZ life
 * section at all - and the fight is summoned by the party: the crack reactor there is
 * item-triggered on one Piece of Cracked Dimension (4031179).
 *
 * <p>That is why this bot's loop is unusually thin. It does not summon anything (the piece
 * comes from the player's own quest furniture), so it fights while there is something to
 * fight and otherwise waits with the party; and once the clock is down the room has no exit
 * portal - the party leaves by talking to the tombstone NPC - so the bot's cue to go home is
 * the leader leaving, never a timer of its own.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/PapulatusBattle.js} - {@code minPlayers 1}, {@code maxPlayers 6},
 *       levels 1-255, recruit/exit map 220080000, entry map 220080001, {@code maxLobbies 1},
 *       and {@code monsterKilled} clearing the run on mob 8500002</li>
 *   <li>{@code scripts/portal/Populatus00.js} - the gate: the party leader walks it, and the
 *       eligibility check keeps only members standing in the recruit map</li>
 *   <li>{@code scripts/reactor/2201004.js} - {@code act()} spawns the Time Sphere (8500000)
 *       and opens the room's map monitor; the sphere revives into the clock (8500001) and the
 *       clock into the body (8500002), per their WZ {@code revive} tables</li>
 *   <li>{@code scripts/npc/2041025.js} - the tombstone that warps the party back to the lobby</li>
 * </ul>
 */
public final class PapulatusPqData {

    private PapulatusPqData() {
    }

    /** Where the party is recruited: "时间塔的隐秘场所". */
    public static final int RECRUIT_MAP = 220080000;

    /** The machine room the run happens in: "时间塔的本源". */
    public static final int BOSS_ROOM = 220080001;

    /** Whether a map is the room the run happens in. */
    public static boolean isBossRoom(int mapId) {
        return mapId == BOSS_ROOM;
    }
}
