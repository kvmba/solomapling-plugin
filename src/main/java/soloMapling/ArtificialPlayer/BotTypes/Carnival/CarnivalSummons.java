package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import org.gms.net.server.world.PartyCharacter;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;
import org.gms.server.partyquest.CarnivalFactory;
import org.gms.server.partyquest.CarnivalFactory.MCSkill;
import org.gms.server.partyquest.MonsterCarnival;
import org.gms.util.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asking the arena to add a summoned monster, which a socketless bot cannot do by packet.
 *
 * <p>A player summons by sending the arena an index into the map's summon list;
 * {@code MonsterCarnivalHandler} then checks the cost and the side's allowance, spends the
 * allowance, and adds the monster itself. A bot has no socket to send that from, so this
 * performs the same steps directly - and has to perform <em>all</em> of them, in the same
 * order, because skipping the allowance would hand the bot more summons than a player gets,
 * and skipping the CP check would hand it monsters it never paid for.
 *
 * <p>The handler's exact sequence (verified against
 * {@code MonsterCarnivalHandler.handlePacket}) is:
 * <ol>
 *   <li>refuse when {@code getCP() < spendCP};</li>
 *   <li>refuse when the side's allowance is spent;</li>
 *   <li>spend one allowance ({@code summonR}/{@code summonB});</li>
 *   <li>{@code addMonsterSpawn(mob, 1, team)} <b>and</b> {@code addAllMonsterSpawn(...)};</li>
 *   <li>{@code gainCP(-spendCP)} - the handler charges through {@code gainCP}, not a setter,
 *       so the per-player CP packet and the team totals stay in step.</li>
 * </ol>
 *
 * <p>Both spawn registries matter. {@code monsterSpawn} is what {@code respawn()} walks; the
 * {@code allMonsterSpawn} one is what the arena's teardown and re-open clears. A bot that only
 * registered in the first leaks one stale spawn point per summon into the next match, which is
 * how the {@code mobGenMax} allowance and the real body count drift apart.
 *
 * <p>Kept out of the bot so the budget rules live in one place with the arena's own
 * definitions rather than being re-derived alongside the fighting logic.
 */
final class CarnivalSummons {

    private CarnivalSummons() {
    }

    /**
     * Which entry each bot bought last, so consecutive purchases walk the price list instead of
     * buying the same monster five times in a row.
     *
     * <p>Per bot rather than per arena: two bots on one side share the arena's list but not
     * their budgets, and a shared cursor would make them all buy in lockstep.
     */
    private static final Map<Integer, Integer> LAST_CHOICE = new ConcurrentHashMap<>();

    /** When each bot last cast a debuff, so one bot does not drain the party's CP in a burst. */
    private static final Map<Integer, Long> LAST_DEBUFF_AT = new ConcurrentHashMap<>();

    /**
     * How long one bot waits between two debuff casts.
     *
     * <p>The handler has no cooldown of its own - CP is the only limit - but a player does not
     * re-cast every second, and a bot that did would spend the whole match's points on debuffs
     * and field nothing. Long enough that the field's monsters stay the main pressure.
     */
    private static final long DEBUFF_INTERVAL_MS = 8_000L;

    /** Drop a stopped bot's bookkeeping so neither map grows for the process's life. */
    static void release(int botId) {
        LAST_CHOICE.remove(botId);
        LAST_DEBUFF_AT.remove(botId);
    }

    /** Restart this bot's purchase rotation with a fresh arena. */
    static void resetChoice(int botId) {
        release(botId);
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
        MonsterCarnival carnival = bot.getMonsterCarnival();
        if (carnival == null) {
            return false;
        }

        // The CP and allowance checks and the spend below must be ONE atomic step per match.
        // `MonsterCarnival.canSummonR()` is a plain `summonsR < maxMobs` read and `summonR()` is a
        // plain increment, so two bots of one side ticking on different threads (the macro wheel
        // runs each bot on its own virtual thread) could both pass the check and both increment -
        // fielding one body more than the engine allows, and drifting the counter the win is
        // scored on. The lock is per match object, so different fields never contend.
        synchronized (carnival) {
            try {
                return requestLocked(bot, option, cost, carnival);
            } catch (RuntimeException e) {
                // MonsterCarnival.dispose() nulls its map before clearing the members' pointers,
                // and canSummonR() dereferences that map. The engine takes no lock of its own, so
                // a summon racing the very end of a match can observe the half-torn-down object.
                // The match is over at that point: dropping the request is the correct answer.
                return false;
            }
        }
    }

    /** The body of {@link #request}, run while holding the match's monitor. */
    private static boolean requestLocked(Character bot, Pair<Integer, Integer> option, int cost,
                                         MonsterCarnival carnival) {
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
        // The spawn point must exist for THIS side. getRandomSP returns null when the arena has
        // no free point for the team, and placing the monster at a null position would throw
        // later. Checked before the allowance is spent, so a refused summon costs nothing.
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
        bot.getMap().addAllMonsterSpawn(mob, 1, team);
        // The handler charges AFTER placing the monster, through gainCP(-cost): that path also
        // updates this player's CP packet and the team's total, which setCP alone would not.
        bot.gainCP(-cost);
        return true;
    }

    /**
     * Buy the most expensive monster this side can afford, skipping the one it just bought.
     *
     * <p>Two rules, both learned from how the arena is priced:
     * <ul>
     *   <li>Never ask for what the side cannot pay for - the handler would refuse it, and a
     *       refused request burns a tick while the other side keeps killing.</li>
     *   <li>Prefer the dearest affordable entry, but not the same one twice running: a flat
     *       "always the most expensive" burns the budget on two bodies, a flat "always cheapest"
     *       fields ten harmless ones. Walking down from the top of the affordable band is what a
     *       player does when the points arrive unevenly.</li>
     * </ul>
     *
     * @return true when a purchase went through
     */
    static boolean requestBest(Character bot, List<Pair<Integer, Integer>> options) {
        if (bot == null || options == null || options.isEmpty()) {
            return false;
        }
        int botId = bot.getId();
        Integer last = LAST_CHOICE.get(botId);
        int cp = bot.getCP();

        // Candidates the side can actually pay for, dearest first.
        List<Integer> affordable = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Integer cost = options.get(i).getRight();
            if (cost != null && cost > 0 && cost <= cp) {
                affordable.add(i);
            }
        }
        if (affordable.isEmpty()) {
            return false;
        }
        affordable.sort(Comparator
                .comparingInt((Integer i) -> options.get(i).getRight())
                .reversed()
                .thenComparingInt(i -> i));

        // Try the dearest affordable entries first, keeping the one bought last at the END of the
        // order rather than excluding it: a run that can only afford one monster must still be
        // able to buy it, and a run with alternatives should vary.
        for (int index : affordable) {
            if (last != null && index == last) {
                continue;
            }
            if (request(bot, index)) {
                LAST_CHOICE.put(botId, index);
                return true;
            }
        }
        if (last != null && affordable.contains(last) && request(bot, last)) {
            return true;
        }
        return false;
    }

    /**
     * Cast the arena's cheapest debuff on the other party, at a player-like cadence.
     *
     * <p>What the handler does for tab 1, reproduced: price the skill, refuse when the side
     * cannot pay, then apply it - to the whole enemy party when the skill says so, to one random
     * enemy otherwise - and charge through {@code gainCP}. The disease is the skill's own, which
     * is why this reads the arena's skill ids rather than carrying a list.
     *
     * @return true when a debuff was actually cast
     */
    static boolean castDebuff(Character bot, List<Integer> skillIds) {
        if (bot == null || bot.getMap() == null || skillIds == null || skillIds.isEmpty()) {
            return false;
        }
        MonsterCarnival carnival = bot.getMonsterCarnival();
        if (carnival == null) {
            return false;
        }
        // Same check-then-spend hazard as a summon, and worse: there is no allowance to bound it,
        // and Character.gainCP has no floor - two bots passing the CP check together would push
        // the score negative, which then feeds the win comparison.
        synchronized (carnival) {
            try {
                return castDebuffLocked(bot, skillIds, carnival);
            } catch (RuntimeException e) {
                // Same end-of-match race as the summon path: the engine nulls its map before the
                // characters stop pointing at it. The match is over; drop the cast.
                return false;
            }
        }
    }

    /** The body of {@link #castDebuff}, run while holding the match's monitor. */
    private static boolean castDebuffLocked(Character bot, List<Integer> skillIds,
                                            MonsterCarnival carnival) {
        int botId = bot.getId();
        long now = System.currentTimeMillis();
        Long last = LAST_DEBUFF_AT.get(botId);
        if (last != null && now - last < DEBUFF_INTERVAL_MS) {
            return false;
        }

        // Cheapest castable skill: the same "spend what the budget allows" rule as summons. A
        // debuff's value is the enemy's lost time, not its price, so the cheap one is the right
        // buy.
        MCSkill chosen = null;
        int chosenId = -1;
        for (int id : skillIds) {
            MCSkill skill = CarnivalFactory.getInstance().getSkill(id);
            if (skill == null || skill.cpLoss() <= 0 || bot.getCP() < skill.cpLoss()) {
                continue;
            }
            if (chosen == null || skill.cpLoss() < chosen.cpLoss()) {
                chosen = skill;
                chosenId = id;
            }
        }
        if (chosen == null) {
            return false;
        }

        var enemy = bot.getParty() == null ? null : bot.getParty().getEnemy();
        if (enemy == null || enemy.getMembers().isEmpty()) {
            return false;
        }

        if (chosen.targetsAll()) {
            for (PartyCharacter mpc : enemy.getPartyMembers()) {
                Character mc = mpc.getPlayer();
                if (mc != null) {
                    applyDebuff(mc, chosen);
                }
            }
        } else {
            // Single-target skills hit one random member, exactly as the handler does; the
            // collection is a party-member list, so it is copied before indexing.
            List<PartyCharacter> members = new ArrayList<>(enemy.getPartyMembers());
            int pick = (int) Math.floor(Math.random() * members.size());
            Character mc = members.get(pick).getPlayer();
            if (mc != null) {
                applyDebuff(mc, chosen);
            }
        }
        bot.getMap().broadcastMessage(
                org.gms.util.PacketCreator.playerSummoned(bot.getName(), 1, chosenId));
        bot.gainCP(-chosen.cpLoss());
        LAST_DEBUFF_AT.put(botId, now);
        return true;
    }

    /** Apply one skill's disease the way the handler's tab-1 branch does. */
    private static void applyDebuff(Character target, MCSkill skill) {
        var disease = skill.getDisease();
        if (disease == null) {
            target.dispel();
        } else {
            target.giveDebuff(disease, skill.getSkill());
        }
    }
}
