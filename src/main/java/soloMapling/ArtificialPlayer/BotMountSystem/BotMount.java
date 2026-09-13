package soloMapling.ArtificialPlayer.BotMountSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Mount;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.skills.Beginner;
import org.gms.net.server.Server;
import org.gms.server.StatEffect;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotCustomization;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 坐骑系统 (mount / Monster Riding) for artificial players.
 *
 * <p>v83 riding is not "set a buff" — a real player needs the whole kit, and the bot
 * mirrors it:
 * <ul>
 *   <li>the <b>skill</b> {@code Beginner.MONSTER_RIDER} (1004) — learned via
 *       {@code changeSkillLevel}; the buff's {@code isMonsterRiding()} test reads it;</li>
 *   <li>the <b>骑宠</b> (mount) item in the equipment slot {@code -18} (WZ {@code islot=Tm},
 *       e.g. Hog 1902000, reqLevel 70);</li>
 *   <li>the <b>鞍子</b> (saddle) item in slot {@code -19} (WZ {@code islot=Sd},
 *       e.g. Saddle 1912000, reqLevel 0);</li>
 *   <li>the {@link Mount} object + the {@link BuffStat#MONSTER_RIDING} buff — what every
 *       viewer's look packet actually reads ({@code spawnPlayerMapObject} /
 *       {@code writeForeignBuffs} emit the mount only when the buff is present).</li>
 * </ul>
 * So {@link #mount} learns the skill, equips both items, sets the {@code Mount} object,
 * registers the buff and broadcasts {@code showMonsterRiding} — exactly the state a real
 * rider is in. On dismount only the buff/Mount are toggled off; the skill and both items
 * stay owned, so the bot genuinely "has a mount" and can climb back on.
 *
 * <p>Rules (product):
 * <ul>
 *   <li>Only bots at {@link #MIN_LEVEL} (70) or above ever ride — the real v83 mount
 *       requirement (Hog/Frog etc. carry {@code reqLevel=70}).</li>
 *   <li>Only an {@value #OWN_CHANCE} fraction of the eligible population owns a mount,
 *       decided DETERMINISTICALLY from the bot's character id — a persistent companion
 *       keeps the same mount across restarts, and the crowd has a mount minority.</li>
 *   <li>A bot keeps its mount through ALL normal movement — walking, standing, jumping,
 *       climbing ropes/ladders, swimming, teleporting and map changes. It dismounts only
 *       for the three things a rider can't do: cast a skill (including any attack), sit in
 *       a chair, and die. A map whose WZ {@code fieldLimit} carries CANNOTUSEMOUNTS also
 *       dismounts it (the host does this on enter too).</li>
 *   <li>Mounts are cosmetic on bots: the riding buff does not change their movement
 *       speed and nothing drains fatigue.</li>
 * </ul>
 *
 * <p>Applies only to the four mobile families — 站街 (SocialBot), 打怪 (TrainingBot),
 * 游走 (TownWandererBot) and 持久化 (CompanionBot) — each of which opts in via
 * {@code BotSM.allowsMount()}. Merchants, gacha/blackjack/game-zone hosts, dice,
 * drop-game, OPQ and the tutorial/staging bots never mount. The one
 * {@link BotSM} tick drives all of them: {@link #tick(Character)} reconciles the mount
 * every tick (cheap no-op for a non-owner) and {@link #cancelForAction(Character)} is the
 * eager fail-safe the skill / attack / chair paths call before they take the pose.
 */
public final class BotMount {

    /** v83's own mount requirement — the Hog & friends sit at reqLevel 70+. */
    public static final int MIN_LEVEL = 70;

    /** Share of eligible (level >= 70) bots that own a mount. */
    public static final double OWN_CHANCE = 0.35;

    /** Equipment slots: 骑宠 (Tm) and 鞍子 (Sd), negative as the client encodes them. */
    private static final short SLOT_MOUNT = -18;
    private static final short SLOT_SADDLE = -19;

    /**
     * The mount kits a bot can own: a 骑宠 (Tm, slot -18) paired with its 鞍子 (Sd, slot -19).
     * Deliberately a fixed allow-list of a <b>complete, non-cash</b> pair present in this
     * server's WZ — an item id the client's TamingMob data lacks (or a saddle with no mount)
     * is a crash or a mountless rider for every viewer, so we never roll the whole 1902xxx /
     * 1912xxx ranges.
     *
     * <p>Hog 1902000 (reqLevel 70) + Saddle 1912000 (reqLevel 0) is the plain, non-cash farm
     * mount. If more complete pairs are added to the WZ later, extend this array.
     */
    private static final int[][] KITS = {
            {1902000, 1912000}, // Hog + Saddle
    };

    /** Rider skill id: {@code sourceid % 10000000 == 1004} → {@code isMonsterRiding()}. */
    private static final int RIDE_SKILL = Beginner.MONSTER_RIDER;

    /**
     * After an action forces a dismount (a skill, an attack, a chair), the bot will not
     * remount for this long. Without it a grinding bot — which alternates walk and swing
     * every couple of seconds — would flicker on and off the mount. Long enough to ride out
     * a fight, short enough that a bot settling to a stand or a walk after the fight climbs
     * back on.
     */
    private static final long REMOUNT_COOLDOWN_MS = 8_000L;

    // botId -> epoch-ms before which this bot must not remount (set by cancelForAction).
    private static final Map<Integer, Long> remountBlockedUntil = new ConcurrentHashMap<>();

    private BotMount() {}

    /** Whether this bot owns a mount at all, decided stably from its character id. */
    public static boolean ownsMount(Character bot) {
        return bot != null && kitIndexForId(bot.getId()) >= 0;
    }

    /** Whether the bot is currently astride (has the riding buff registered). */
    public static boolean isRiding(Character bot) {
        return bot != null && bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null;
    }

    /**
     * Reconcile the mount against the bot's present state. Safe to call every tick, and
     * idempotent: it only broadcasts on an actual state change (mount ⇄ dismount), never
     * while the bot is already in the intended state.
     * <ul>
     *   <li>owns no kit / below level / map forbids mounts → ensure dismounted;</li>
     *   <li>astride and a forbidden state (chair or death) → dismount;</li>
     *   <li>not astride, state allows it, and past the action cooldown → mount.</li>
     * </ul>
     */
    public static void tick(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        if (bot.getLevel() < MIN_LEVEL || !allowsMount(bot) || !ownsMount(bot) || mapForbidsMounts(bot)) {
            if (isRiding(bot)) {
                dismount(bot);
            }
            return;
        }
        if (isRiding(bot)) {
            if (forbidsMount(bot)) {
                dismount(bot);
            }
        } else if (!forbidsMount(bot) && System.currentTimeMillis() >= remountAllowedAt(bot)) {
            mount(bot);
        }
    }

    /**
     * Eager fail-safe for the few actions a mount can't sit through: casting a skill
     * (including any attack) and sitting a chair. Dismounts if astride and arms the remount
     * cooldown so a walk/swing cycle doesn't flicker the mount. Death never routes here —
     * the {@link #tick} sweep reads the corpse state directly. Cheap no-op when the bot
     * isn't riding and owns no mount.
     */
    public static void cancelForAction(Character bot) {
        if (bot == null || (!ownsMount(bot) && !isRiding(bot))) {
            return; // nothing to cancel and it can never mount — skip the bookkeeping write
        }
        remountBlockedUntil.put(bot.getId(), System.currentTimeMillis() + REMOUNT_COOLDOWN_MS);
        if (isRiding(bot)) {
            dismount(bot);
        }
    }

    /**
     * Force-mount for the GM test command, ignoring the ownership / pose / cooldown gates.
     * Still refuses the hard world rules — below {@link #MIN_LEVEL}, no map, or a map that
     * forbids mounts — because the tick sweep would undo it a moment later, so reporting
     * failure here is the honest answer. A GM-spawned non-owner still gets the full kit.
     */
    public static boolean forceMount(Character bot) {
        if (bot == null || bot.getMap() == null || bot.getLevel() < MIN_LEVEL
                || !allowsMount(bot) || mapForbidsMounts(bot)) {
            return false;
        }
        return mount(bot);
    }

    /** Force-dismount for the GM test command; holds it off like any action does. */
    public static void forceDismount(Character bot) {
        cancelForAction(bot);
    }

    /** Release a despawned bot's cooldown bookkeeping so the maps don't grow unbounded. */
    public static void forget(int botId) {
        remountBlockedUntil.remove(botId);
    }

    /** The 骑宠 (Tm) item ids a GM may hand out (for the command's help text). */
    public static int[] mountIds() {
        int[] out = new int[KITS.length];
        for (int i = 0; i < KITS.length; i++) {
            out[i] = KITS[i][0];
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static long remountAllowedAt(Character bot) {
        return remountBlockedUntil.getOrDefault(bot.getId(), 0L);
    }

    /** A cheap deterministic integer hash of the character id (stable across restarts). */
    private static int mix(int cid) {
        int h = cid * 0x9E3779B1;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    /** Index into {@link #KITS} for a character id, or -1 when it owns no mount. */
    static int kitIndexForId(int cid) {
        if (Math.floorMod(mix(cid), 1000) >= (int) Math.round(OWN_CHANCE * 1000)) {
            return -1;
        }
        return Math.floorMod(mix(cid), KITS.length);
    }

    /** Pure ownership test (package-private for the determinism test). */
    static boolean ownsMountForId(int cid) {
        return kitIndexForId(cid) >= 0;
    }

    /**
     * The only states a mount may NOT hold. A bot rides through every kind of normal
     * movement — walking, standing, jumping, climbing, swimming, teleport, map change — so
     * the ban is narrow: a chair (a distinct sit pose that owns the character's animation
     * slots), or a corpse. Skill casting is not a persistent pose, so it is handled eagerly
     * by {@link #cancelForAction} rather than here.
     */
    private static boolean forbidsMount(Character bot) {
        if (bot.getChair() > 0) {
            return true; // seated — the chair owns the pose
        }
        // A body is never on horseback. Cover the armed episode and a bot the host zeroed
        // directly (decHP) that has not been adopted yet.
        if (bot.getHp() <= 0) {
            return true;
        }
        BotSM owner = CharacterStorage.getBotById(bot.getId());
        return owner != null && owner.death().isDead();
    }

    private static boolean mapForbidsMounts(Character bot) {
        MapleMap map = bot.getMap();
        return map != null && FieldLimit.CANNOTUSEMOUNTS.check(map.getFieldLimit());
    }

    /**
     * Whether this bot's type may ride at all. Only the four mobile families do (站街 / 打怪 /
     * 游走 / 持久化); every other bot — merchants, gacha/blackjack/game-zone hosts, dice,
     * drop-game, OPQ, tutorial — inherits the base {@code false}. A bot with no registered
     * BotSM (shouldn't happen for a ticked bot) is treated as not allowed, so we never mount
     * an unmanaged character.
     */
    private static boolean allowsMount(Character bot) {
        BotSM owner = CharacterStorage.getBotById(bot.getId());
        return owner != null && owner.allowsMount();
    }

    /**
     * Put the bot astride its kit. Grants the missing pieces in the same order a real rider
     * ends up with them, then announces the change:
     * <ol>
     *   <li>learn {@code MONSTER_RIDER} (1004) if the bot doesn't have it — the skill the
     *       buff's {@code isMonsterRiding()} check keys off;</li>
     *   <li>equip the 骑宠 (Tm, slot -18) and 鞍子 (Sd, slot -19);</li>
     *   <li>set the {@link Mount} object, register the riding buff, and broadcast
     *       {@code showMonsterRiding}.</li>
     * </ol>
     * Everything is idempotent — a bot re-mounting a second time only re-registers the buff.
     *
     * @return false if the rider skill/effect is missing (nothing to ride with); true once
     *         the full kit and buff are in place.
     */
    private static boolean mount(Character bot) {
        Skill skill = SkillFactory.getSkill(RIDE_SKILL);
        if (skill == null || skill.getMaxLevel() < 1) {
            return false; // rider skill missing from WZ — nothing to ride with
        }
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) {
            return false;
        }

        int kitIndex = kitIndexForId(bot.getId());
        // A GM force-mount of a non-owner: fall back to the first kit so the pipe is testable.
        int[] kit = kitIndex < 0 ? KITS[0] : KITS[kitIndex];

        learnRiderSkill(bot, skill);
        equipIfMissing(bot, kit[0], SLOT_MOUNT);   // 骑宠 Tm
        equipIfMissing(bot, kit[1], SLOT_SADDLE);  // 鞍子 Sd

        // The Mount's skill id must match the rider skill the bot actually learned (1004);
        // bots are explorer jobs, whose rider skill is the plain Beginner one.
        Mount mount = bot.getMapleMount();
        if (mount == null) {
            bot.setMapleMount(new Mount(bot, kit[0], RIDE_SKILL));
        } else {
            mount.setItemId(kit[0]);
            mount.setSkillId(RIDE_SKILL);
        }

        long now = Server.getInstance().getCurrentTime();
        long duration = effect.getDuration() > 0 ? effect.getDuration() : RIDE_SKILL; // 1004: time=2100000ms
        // Non-silent so the buff lands in the character's buff holders; that registration is
        // what makes every later spawn/warp packet carry the mount instead of a bare character.
        bot.registerEffect(effect, now, now + duration, false);
        broadcastMountedLine(bot);
        return true;
    }

    private static void learnRiderSkill(Character bot, Skill skill) {
        if (bot.getSkillLevel(skill) >= 1) {
            return; // already known (persistent companions keep it across restarts)
        }
        // This server's 0001004 has a single level; maxLevel covers any future multi-level data.
        byte level = (byte) Math.max(1, skill.getMaxLevel());
        bot.changeSkillLevel(skill, level, level, -1);
    }

    private static void equipIfMissing(Character bot, int itemId, short slot) {
        Item current = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
        if (current != null && current.getItemId() == itemId) {
            return; // already wearing the right piece
        }
        BotCustomization.EquipItem(bot, itemId, slot);
    }

    /** Broadcast the mount line so everyone already on the map sees the bot astride. */
    private static void broadcastMountedLine(Character bot) {
        Mount mount = bot.getMapleMount();
        if (mount == null || bot.getMap() == null) {
            return;
        }
        bot.getMap().broadcastMessage(bot,
                PacketCreator.showMonsterRiding(bot.getId(), mount), false);
    }

    /**
     * Take the bot off its mount. Cancelling the riding buff makes the host drop the buff
     * stat and broadcast the foreign-buff cancel, so observers remove the mount model — same
     * as a player dismounting. The skill and both items stay owned; only the buff/Mount
     * toggle changes. No-op if the bot isn't riding.
     */
    private static void dismount(Character bot) {
        if (bot == null || !isRiding(bot)) {
            return;
        }
        bot.cancelEffectFromBuffStat(BuffStat.MONSTER_RIDING);
    }
}
