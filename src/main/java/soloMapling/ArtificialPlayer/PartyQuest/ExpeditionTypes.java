package soloMapling.ArtificialPlayer.PartyQuest;

/**
 * The expedition system, which is a different container from a party.
 *
 * <p>Several of the server's contents enrol their members in an {@code Expedition} rather than
 * hanging off a {@code Party} - the boss expeditions, Chaos Zakum and Horntail, Pink Bean, CWKPQ,
 * and Ariant Coliseum. An expedition has its own registration window, its own size limits per
 * type, and its own map handling, so a bot that wants to join one is joining something the
 * party mechanisms cannot describe.
 *
 * <p>The one thing worth knowing before touching any of them: every type's size limit is
 * <em>thirty</em> on both ends, so an expedition is not the small party a quest is - it is the
 * whole group, and the bot's job in it is to be one of many rather than a partner. That is why
 * the boss expeditions are the last thing here worth automating rather than the first: a bot
 * that dies halfway through a thirty-person Zakum run is a different problem from a bot that
 * misses a platform in Kerning.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code org/gms/server/expeditions/ExpeditionType.java} - the table of types, their
 *       member limits and level bands</li>
 *   <li>{@code org/gms/server/expeditions/Expedition.java} - registration, {@code addMember},
 *       the leader, and the disposal path</li>
 * </ul>
 */
public final class ExpeditionTypes {

    private ExpeditionTypes() {
    }

    /** One expedition type's limits, as the enum declares them. */
    public record Type(String name, int minMembers, int maxMembers, int minLevel, int maxLevel) {

        /** Whether a level can join. */
        public boolean levelEligible(int level) {
            return level >= minLevel && level <= maxLevel;
        }
    }

    /**
     * The table, transcribed from {@code ExpeditionType}. Kept here rather than reflected out of
     * the enum because the enum also carries drop and reward tables this does not want, and
     * because a bot only needs the limits.
     */
    public static final Type[] ALL = {
            new Type("BALROG_EASY", 3, 30, 50, 255),
            new Type("BALROG_NORMAL", 6, 30, 50, 255),
            new Type("SCARGA", 6, 30, 100, 255),
            new Type("SHOWA", 3, 30, 100, 255),
            new Type("ZAKUM", 6, 30, 50, 255),
            new Type("HORNTAIL", 6, 30, 100, 255),
            new Type("CHAOS_ZAKUM", 6, 30, 120, 255),
            new Type("CHAOS_HORNTAIL", 6, 30, 120, 255),
            new Type("ARIANT", 2, 7, 20, 30),
            new Type("ARIANT1", 2, 7, 20, 30),
            new Type("ARIANT2", 2, 7, 20, 30),
            new Type("PINKBEAN", 6, 30, 120, 255),
            new Type("CWKPQ", 6, 30, 90, 255),
    };

    public static Type byName(String name) {
        for (Type type : ALL) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Whether a party would fill an expedition of this type.
     *
     * <p>Worth checking before committing a bot to one: the limits are wide, and a type that
     * wants six members will refuse a party of four no matter how well the members play.
     */
    public static boolean partySizeFits(String name, int members) {
        Type type = byName(name);
        return type != null && members >= type.minMembers() && members <= type.maxMembers();
    }

    /**
     * How many bots a party would need to reach a type's minimum, which is the number that
     * decides whether a run is worth attempting at all.
     */
    public static int botsNeededFor(String name, int currentMembers) {
        Type type = byName(name);
        if (type == null) {
            return -1;
        }
        return Math.max(0, type.minMembers() - currentMembers);
    }
}
