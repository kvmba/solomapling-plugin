package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.config.GameConfig;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.packet.Packet;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterDropEntry;
import org.gms.server.life.MonsterGlobalDropEntry;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companion.CompanionRoster;
import soloMapling.server.MethodScheduler;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Lands a real bot hit (melee / ranged / magic): broadcast the attack packet with the
 * damage line(s) so players see it, then apply the summed damage directly - skipping the
 * vanilla applyAttack path, which is keyed off stats a bot lacks. The bot is always passed
 * explicitly (never the shared client).
 *
 * On a kill we credit EXP + death via monster.damage + killMonster(withDrops=false), then
 * drop loot ourselves: the vanilla path only drops for damagers passing isLoggedinWorld(),
 * which a headless bot fails, so a bot kill would otherwise drop nothing. Loot keeps
 * vanilla ownership (owner = bot; party-shared if grouped, else owner ~15s then FFA) at
 * world drop rates.
 */
public final class BotAttackEffects {

    private static final Logger log = LoggerFactory.getLogger(BotAttackEffects.class);

    /*
     * Death animation id handed to MapleMap.killMonster: 1 = the vanilla fade-out
     * (0 = disappear instantly, 2+ = special per-mob sequences).
     *
     * Do NOT pass the attack hitDelay here. killMonster 4th arg is the animation byte written
     * straight into the KILL_MONSTER packet (PacketCreator writes it with writeByte, so 300ms
     * truncates to 44 - an animation the client has no death sequence for, and the mob pops out
     * of existence instead of playing die1). The hit delay belongs to the attack packet only.
     */
    private static final int DEATH_ANIMATION_FADE_OUT = 1;

    /*
     * Bounds for the post-death loot delay (0.42 * the mob's die1 animation length).
     * Vanilla uses 1000..3000; we keep the ceiling and lower the floor to 200ms so bot
     * kills feel responsive - see applyDamageAndLoot.
     */
    private static final long LOOT_DELAY_MIN_MS = 200L;
    private static final long LOOT_DELAY_MAX_MS = 3000L;

    private BotAttackEffects() {}

    /*
     * Credits to NutNNut for Packet IDs
     * Land a melee hit on one or more mobs: broadcast the swing carrying each mob's damage
     * line(s), apply the summed damage per mob, and drop loot for any it kills. Returns
     * true if at least one mob died. skillId 0 = a no-skill basic swing.
     *
     * @param bot          the attacking bot (passed explicitly - shared-client-safe)
     * @param hits         each target mob -> its rolled damage line(s); all same line count
     * @param skillId      the attack skill that renders, or 0 for a basic swing
     * @param bodyActionId the per-weapon swing animation id (from BotAttackData)
     * @param facingMask   the stance/facing byte (0x00 right / 0x80 left)
     * @param speed        the attack-speed byte (2..9)
     * @param hitDelay     ms before the damage numbers land (attack packet only; loot
     *                    timing comes from the die1 animation, see applyDamageAndLoot)
     */
    public static boolean meleeStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                      int skillLevel, int bodyActionId, int facingMask, int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = PacketCreator.closeRangeAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), toTargets(hits, hitDelay), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    /* Ranged version (bow/crossbow/gun/claw): like melee, plus the flying projectile. */
    public static boolean rangedStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                       int skillLevel, int projectile, int bodyActionId, int facingMask,
                                       int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = PacketCreator.rangedAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), projectile, toTargets(hits, hitDelay), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    /*
     * Magic version (wand/staff): a skill is mandatory (mages have no basic attack). The charge
     * int is -1 for normal skills but a real value for keydown CHARGE skills (Big Bang) - the
     * client over-reads the packet and crashes if a charge skill is sent without it.
     */
    public static boolean magicStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                      int skillLevel, int bodyActionId, int facingMask, int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = PacketCreator.magicAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), toTargets(hits, hitDelay),
                BotAttackData.magicChargeFor(skillId), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    private static boolean notReady(Character bot, Map<Monster, List<Integer>> hits) {
        return bot == null || bot.getMap() == null || hits == null || hits.isEmpty();
    }

    /* numAttacked (mobs, high nibble) | numDamage (lines per mob, low nibble). */
    private static int numAttackedAndDamage(Map<Monster, List<Integer>> hits) {
        int numDamage = hits.values().iterator().next().size();
        return (hits.size() << 4) | numDamage;
    }

    /* Packet target map: each mob's object id -> its damage line(s). */
    private static Map<Integer, List<Integer>> toTargets(Map<Monster, List<Integer>> hits, short hitDelay) {
        Map<Integer, List<Integer>> targets = new HashMap<>();
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            targets.put(hit.getKey().getObjectId(), hit.getValue());
        }
        return targets;
    }

    /* Broadcast once, then apply each mob's summed damage + loot. True if any mob died. */
    private static boolean broadcastAndApply(Character bot, Packet packet,
                                             Map<Monster, List<Integer>> hits, short hitDelay) {
        bot.getMap().broadcastMessage(bot, packet, /* repeatToSource */ false);
        GCMovement.markAlerted(bot); // hold the 5s ALERT pose so the bot's own idle/move broadcasts don't cancel it
        boolean anyKilled = false;
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            int total = 0;
            for (int line : hit.getValue()) {
                total += BotAttackData.decodeDamageLine(line); // crit lines arrive negative-encoded
            }
            if (applyDamageAndLoot(bot, hit.getKey(), total, hitDelay)) {
                anyKilled = true;
            }
        }
        return anyKilled;
    }

    /* Apply HP damage; on death, credit EXP + the death broadcast (no vanilla drops) and spawn our own loot. */
    private static boolean applyDamageAndLoot(Character bot, Monster target, int damage, short hitDelay) {
        MapleMap map = bot.getMap();
        Map<Integer, Integer> expBefore = companionPartyExp(bot);

        // Snapshot BEFORE killMonster: its finally block runs dispatchMonsterKilled ->
        // processMonsterKilled, which clears the mob's stati. Reading SHOWDOWN after that
        // always returns null and the bonus would be silently lost. This mirrors how
        // MapleMap.dropFromMonster freezes chRate into MobLootEntry at kill time.
        final float killChRate = lootChanceRate(bot, target);
        // Same reasoning for the loot flags: freeze what the kill saw rather than letting
        // the delayed roll re-read a mob that is already gone from the map.
        final boolean killNoDrops = target.dropsDisabled();
        final byte killDropType = (byte) (target.getStats().isExplosiveReward() ? 3
                : target.getStats().isFfaLoot() ? 2
                : bot.getParty() != null ? 1 : 0);

        boolean killed = target.damage(bot, damage, false); // register damage; false = allow death
        if (killed) {
            // withDrops=false: keep the EXP distribution + death broadcast, skip the
            // vanilla drop step (which yields nothing for a bot-only kill). We spawn the
            // loot ourselves below with proper ownership.
            map.killMonster(target, bot, false, DEATH_ANIMATION_FADE_OUT);
            if (!expBefore.isEmpty()) {
                log.info("Companion kill EXP diagnostic cid={} mobId={} damage={} partyId={} before={} after={}",
                        bot.getId(), target.getId(), damage,
                        bot.getParty() == null ? -1 : bot.getParty().getId(),
                        expBefore, companionPartyExp(bot));
            }
            // Captured at kill time: by the time a delayed task fires the mob is already
            // removed from the map, so the roll must not re-read live state off it.
            // Monster.map is never nulled, so a getMap() == null test would be dead code -
            // compare the bot's current map against the kill map instead.
            final int killMapId = map.getId();
            final Point deathPos = new Point(target.getPosition());
            final Runnable lootTask = () -> {
                // Use the captured Character directly: re-looking it up by id would go
                // through SoloMaplingUtilities.getChr, which is pinned to mainChannel
                // (world 0 / channel 1). A bot living on any other channel would resolve
                // to null, or to a different character with the same id, and silently
                // lose the loot. Channel.removePlayer only drops the map entry, so this
                // reference stays valid even if the bot is despawned meanwhile.
                if (bot.getMap() == null || bot.getMapId() != killMapId) {
                    return; // bot warped away or left - no loot at the corpse
                }
                dropMobLootAt(bot, target, killChRate, killDropType, killNoDrops, deathPos);
            };

            // Vanilla only staggers loot when use_spawn_loot_on_animation is on, landing it
            // 0.42 * die1 into the death animation. Honour the same flag so a server that
            // turns it off gets instant bot loot, exactly like a player's.
            //
            // Vanilla clamps to 1000..3000ms; we keep the 3000ms ceiling but lower the floor
            // to 200ms. Bots are headless, so nothing is lost by the pile appearing earlier,
            // and a long 1s wait on every single kill reads as lag when a bot grinds fast.
            // Because 0.42 * die1 only reaches 200ms at die1 ~= 476ms, most mobs land on the
            // floor value: practical delay drops from ~1000ms to 200ms for typical mobs,
            // while long-death-animation (boss) mobs keep a proportional, readable pause.
            if (GameConfig.getServerBoolean("use_spawn_loot_on_animation")) {
                int die1 = target.getAnimationTime("die1");   // reads WZ stats only - safe after death
                long lootDelayMs = Math.min(Math.max((long) (0.42 * die1), LOOT_DELAY_MIN_MS), LOOT_DELAY_MAX_MS);
                MethodScheduler.runAfterDelay(lootTask, lootDelayMs);
            } else {
                lootTask.run();
            }
            // No vacuum here: the dropped loot is collected organically by the bot itself - TrainingBot
            // walks over the pile and picks drops up one at a time (own + free-for-all), and any drop it
            // abandons expires via the normal map item lifetime. See TrainingBot loot handling + DropCommands.
        }
        return killed;
    }

    private static Map<Integer, Integer> companionPartyExp(Character bot) {
        if (bot == null || !CompanionRoster.isCompanion(bot.getId())) {
            return Map.of();
        }
        Map<Integer, Integer> experience = new LinkedHashMap<>();
        experience.put(bot.getId(), bot.getExp());
        for (Character member : bot.getPartyMembersOnSameMap()) {
            if (member != null) {
                experience.put(member.getId(), member.getExp());
            }
        }
        return experience;
    }

    /*
     * Roll the mob's loot the way MapleMap.dropFromMonster does, so a bot kill looks
     * exactly like a player kill: normal -> global -> quest drops, same droptype, same
     * chance math, same horizontal fan-out, and the same "loot lands partway through the
     * die1 animation" timing.
     *
     * We cannot call dropFromMonster directly: it goes through spawnDrop/spawnMesoDrop,
     * which hand the owner's Client to MapItem and to activateItemReactors. Template-cloned
     * bots share one headless BotClient whose getPlayer() is null (see BotClientBinding),
     * so that path is unsafe. Everything below uses the public spawnItemDrop / spawnMesoDrop
     * overloads that take the Character instead.
     *
     * chRate is frozen at kill time, the way dropFromMonster stores it on its MobLootEntry:
     * the SHOWDOWN read must happen before killMonster clears the mob's stati, and the
     * world rate is read here so a delayed roll cannot pick up a rate change in between.
     * It uses the world config rates (drop_rate/boss_drop_rate) rather than the bot's own
     * field: a bot never runs setWorldRates(), so its dropRate stays at the 1.0 default and
     * getBossDropRate() would divide it straight back out by the world rate.
     */
    private static float lootChanceRate(Character bot, Monster mob) {
        final World world = bot.getWorldServer();
        // No Math.max(1, ...) floor here: vanilla does not clamp chRate either, and a floor
        // would put a bot ABOVE real players on low-rate worlds (drop_rate < 1).
        float chRate = mob.isBoss() ? world.getBossDropRate() : world.getDropRate();
        MonsterStatusEffect showdown = mob.getStati(MonsterStatus.SHOWDOWN);
        if (showdown != null) {
            chRate *= (showdown.getStati().get(MonsterStatus.SHOWDOWN).doubleValue() / 100.0 + 1.0);
        }
        if (bot.isFamilyBuff()) {
            chRate *= bot.getFamilyDrop();
        }
        return chRate;
    }

    /*
     * Loot roll for a mob that died at deathPos. Everything mob-specific is captured at
     * kill time: killMonster clears the mob's stati and removes it from the map, so the
     * delayed roll must not re-read live state off the Monster.
     */
    private static void dropMobLootAt(Character bot, Monster mob, float chRate, byte dropType,
                                      boolean dropsDisabled, Point deathPos) {
        final MapleMap map = bot.getMap();
        if (map == null || dropsDisabled) {
            return;
        }

        final MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
        final List<MonsterDropEntry> lootEntry = mi.retrieveEffectiveDrop(mob.getId());
        final List<MonsterGlobalDropEntry> globalEntry = new ArrayList<>(mi.getRelevantGlobalDrops(map.getId()));
        if ((lootEntry == null || lootEntry.isEmpty()) && globalEntry.isEmpty()) {
            return; // thanks resinate
        }

        final World world = bot.getWorldServer();
        final float mesoRate = world.getMesoRate();   // unclamped, like vanilla chr.getMesoRate()

        final int mobX = deathPos.x;
        final int mobY = deathPos.y;

        // Normal vs quest drops, split exactly like sortDropEntries(): a quest item the
        // bot needs is "visible", one it does not need still drops but is not shown to it.
        //
        // KNOWN LIMITATION: vanilla's spawnDrop carries the questid into MapItem, and both
        // its spawnAndAddRangedMapObject callback and MapItem.sendSpawnData gate the drop
        // packet on chr.needQuestItem(questid, itemId) - so a quest item is only *rendered*
        // for players that actually need it (broadcastItemDropMessage itself does not gate,
        // it broadcasts to everyone in range). spawnDrop is private, there is no public
        // spawnItemDrop overload taking a questid, and MapItem has no setter for it, so a
        // plugin cannot reproduce this: our quest items drop with questid 0, which
        // needQuestItem treats as "needed by everyone", making them visible map-wide.
        // Visibility only - canBePickedBy() ignores questid, so pickup rights are unchanged
        // and nobody can loot something they should not. Needs a host API change to fix.
        final List<MonsterDropEntry> normal = new ArrayList<>();
        final List<MonsterDropEntry> visibleQuest = new ArrayList<>();
        final List<MonsterDropEntry> otherQuest = new ArrayList<>();
        final ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (lootEntry != null) {
            for (MonsterDropEntry mde : lootEntry) {
                if (!ii.isQuestItem(mde.itemId)) {
                    normal.add(mde);
                } else if (bot.needQuestItem(mde.questid, mde.itemId)) {
                    visibleQuest.add(mde);
                } else {
                    otherQuest.add(mde);
                }
            }
        }

        // Global drops only fire when the mob's own table has a real (non-meso) item,
        // matching MobLootEntry.run()'s hasItemDrop gate.
        boolean hasItemDrop = false;
        for (MonsterDropEntry e : normal) {
            if (e.itemId != 0) {
                hasItemDrop = true;
                break;
            }
        }

        // One fan-out counter shared across every group, so loot spreads out instead of stacking.
        final int[] index = {1};
        dropEntryGroup(bot, mob, normal, chRate, mesoRate, dropType, mobX, mobY, index);
        if (hasItemDrop) {
            dropGlobalGroup(bot, mob, globalEntry, dropType, mobX, mobY, index);
        }
        dropEntryGroup(bot, mob, visibleQuest, chRate, mesoRate, dropType, mobX, mobY, index);
        dropEntryGroup(bot, mob, otherQuest, chRate, mesoRate, dropType, mobX, mobY, index);
    }

    /* Rolls one MonsterDropEntry list (normal / visible-quest / other-quest) at chRate. */
    private static void dropEntryGroup(Character bot, Monster mob, List<MonsterDropEntry> entries,
                                       float chRate, float mesoRate, byte dropType,
                                       int mobX, int mobY, int[] index) {
        if (entries.isEmpty()) {
            return;
        }
        MapleMap map = bot.getMap();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        Collections.shuffle(entries);
        for (MonsterDropEntry de : entries) {
            int dropChance = (int) Math.min((float) de.chance * chRate * bot.getCardRate(de.itemId), Integer.MAX_VALUE);
            if (Randomizer.nextInt(999999) >= dropChance) {
                continue;
            }
            if (de.itemId == 0) { // meso
                int mesos = rollAmount(de.Minimum, de.Maximum);
                if (mesos <= 0) {
                    continue;
                }
                mesos = Math.max(1, (int) (mesos * mesoRate));   // vanilla floatToInt truncates
                map.spawnMesoDrop(mesos, spreadPos(mobX, mobY, dropType, index), mob, bot, false, dropType);
            } else {
                map.spawnItemDrop(mob, bot, toItem(ii, de.itemId, de.Minimum, de.Maximum),
                        spreadPos(mobX, mobY, dropType, index), dropType, false);
            }
        }
    }

    /* Global (continent-wide) drops: chance only, no chRate weighting, no quest split. */
    private static void dropGlobalGroup(Character bot, Monster mob, List<MonsterGlobalDropEntry> entries,
                                        byte dropType, int mobX, int mobY, int[] index) {
        if (entries.isEmpty()) {
            return;
        }
        MapleMap map = bot.getMap();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        List<MonsterGlobalDropEntry> shuffled = new ArrayList<>(entries); // cached/shared list: shuffle a copy
        Collections.shuffle(shuffled);
        for (MonsterGlobalDropEntry de : shuffled) {
            if (Randomizer.nextInt(999999) >= de.chance) {
                continue;
            }
            if (de.itemId != 0) {
                map.spawnItemDrop(mob, bot, toItem(ii, de.itemId, de.Minimum, de.Maximum),
                        spreadPos(mobX, mobY, dropType, index), dropType, false);
            }
        }
    }

    /*
     * The index-based horizontal fan-out (0, +25, -25, +50, -50 ... px). Explosive-reward
     * mobs (droptype 3) spread at 40px instead of 25px, matching both vanilla branches in
     * dropItemsFromMonsterOnMap / dropGlobalItemsFromMonsterOnMap.
     */
    private static Point spreadPos(int mobX, int mobY, byte dropType, int[] index) {
        int step = (dropType == 3) ? 40 : 25;
        int d = index[0]++;
        return new Point(mobX + ((d % 2 == 0) ? (step * ((d + 1) / 2)) : -(step * (d / 2))), mobY);
    }

    private static Item toItem(ItemInformationProvider ii, int itemId, int min, int max) {
        if (ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP) {
            return ii.randomizeStats((Equip) ii.getEquipById(itemId));
        }
        return new Item(itemId, (short) 0, quantityRoll(min, max));
    }

    /*
     * Vanilla's amount roll: random in [Minimum, Maximum) only when Maximum != 1 AND
     * Maximum > Minimum; otherwise the fixed value Maximum.
     *
     * Do NOT collapse this to a plain "min + nextInt(max - min)": for the very common
     * Minimum=0 / Maximum=1 row (a single coin, a single potion) that returns 0, and the
     * caller then skips the drop entirely - the loot silently disappears. Vanilla takes
     * the Maximum branch there and drops 1.
     */
    private static int rollAmount(int min, int max) {
        return (max != 1 && max > min) ? Randomizer.nextInt(max - min) + min : max;
    }

    /* Same roll, narrowed to the short quantity Item wants. */
    private static short quantityRoll(int min, int max) {
        return (short) Math.max(1, rollAmount(min, max));
    }
}
