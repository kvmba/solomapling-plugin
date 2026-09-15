package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.server.life.MobSkill;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotHealthFloor;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/*
 * One bot's live mob-debuffs (frozen / sealed / slowed / weakened / blinded / poisoned), with the
 * behaviour queries the movement, attack and damage layers read, and the foreign-buff packets real
 * players see.
 *
 * Lives on BotSM beside BotDeath. Everything a bot does that a disease constrains asks THIS object,
 * so the rule is stated once instead of scattered across the bot types.
 *
 * Why the plugin owns the state instead of the engine's Character.diseases: the engine only applies a
 * disease from the mob's controller packet path (see BotDebuffTable), which a headless bot never
 * triggers. The plugin therefore models the effect and reuses the engine's PacketCreator to render it
 * for onlookers - the same "bots show real buff/debuff packets but own their own state" contract the
 * buff system already follows.
 *
 * Threading: apply / tick / clearAll run on a bot's movement or macro tick; the getters are read from
 * the movement, attack and contact-damage threads. The map is concurrent and each entry's mutable
 * fields are volatile, so a reader never sees a torn entry and a writer never blocks a reader.
 */
public final class BotDebuffState {

    private final Character chr;
    private final Map<Disease, Entry> active = new ConcurrentHashMap<>();

    public BotDebuffState(Character chr) {
        this.chr = chr;
    }

    /**
     * The debuff state belonging to this character's running bot behaviour, or null when it has none
     * (a bare artificial character, or one mid-retype). Lets the damage layer reach a bot's status
     * without knowing which of the twenty-odd bot types it is - the same contract as BotDeath.of.
     */
    public static BotDebuffState of(Character chr) {
        if (chr == null) {
            return null;
        }
        BotSM owner = CharacterStorage.getBotById(chr.getId());
        return owner == null ? null : owner.status();
    }

    /** One live disease. Mutable poison clock so the periodic tick re-arms without replacing the entry. */
    private static final class Entry {
        final long expiresAtMs;
        final MobSkill skill;
        volatile long nextPoisonAtMs;

        Entry(long expiresAtMs, MobSkill skill, long nextPoisonAtMs) {
            this.expiresAtMs = expiresAtMs;
            this.skill = skill;
            this.nextPoisonAtMs = nextPoisonAtMs;
        }
    }

    // ── Behaviour queries ────────────────────────────────────────────────────

    /** True while this exact disease is active (package-visible: used by tests to pin the cap). */
    boolean has(Disease disease) {
        return active.containsKey(disease);
    }

    /** How many diseases are active right now (0..{@link BotDebuffTable#MAX_ACTIVE}). */
    int size() {
        return active.size();
    }

    /** STUN / SEDUCE: the bot cannot move or attack. */
    public boolean isFrozen() {
        return anyMatching(BotDebuffTable::freezes);
    }

    /** Frozen or sealed: the bot cannot swing. */
    public boolean blocksAttack() {
        return anyMatching(BotDebuffTable::blocksAttack);
    }

    /** Ground speed scale while SLOW is up (1.0 = normal). */
    public double moveFactor() {
        return anyMatching(BotDebuffTable::slows) ? BotDebuffTable.SLOW_MOVE_FACTOR : 1.0;
    }

    /** Damage dealt multiplier while WEAKEN is up (1.0 = normal). */
    public double outFactor() {
        return anyMatching(BotDebuffTable::weakens) ? BotDebuffTable.WEAKEN_OUT_FACTOR : 1.0;
    }

    /** Contact damage taken multiplier while WEAKEN is up (1.0 = normal). */
    public double takenFactor() {
        return anyMatching(BotDebuffTable::weakens) ? BotDebuffTable.WEAKEN_TAKEN_FACTOR : 1.0;
    }

    /** Roll for DARKNESS: true when this swing whiffs even though a target was in reach. */
    public boolean whiffs() {
        return anyMatching(BotDebuffTable::blinds)
                && ThreadLocalRandom.current().nextDouble() < BotDebuffTable.DARKNESS_MISS_CHANCE;
    }

    /**
     * Whether any active disease satisfies the predicate. Keeping every behaviour query routed through
     * the table's own predicates means {@link BotDebuffTable} really is the single statement of "what
     * each disease does" - not just a suggestion the getters happen to ignore.
     */
    private boolean anyMatching(Predicate<Disease> predicate) {
        for (Disease disease : active.keySet()) {
            if (predicate.test(disease)) {
                return true;
            }
        }
        return false;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /** Entry point from the applier: applies a disease using the WZ skill's own duration/x value. */
    public boolean apply(Disease disease, MobSkill skill) {
        if (skill == null) {
            return false;
        }
        return apply(disease, skill.getDuration(), skill.getX(), skill);
    }

    /**
     * Core apply, decoupled from the packet source so behaviour is testable without a live
     * {@code Character} / WZ {@code MobSkill}: only {@code durationMs} / {@code skillX} drive the
     * effect, and {@code skill} may be null (then no foreign packet is sent). Any real disease is
     * accepted (curse and friends are tracked so their packet is cancelled on expiry, even though they
     * gate no behaviour); the behaviour getters above filter through {@link BotDebuffTable}. A disease
     * already active is not re-applied, and the engine's two-disease cap is honoured.
     *
     * @return true when this call actually added the disease
     */
    boolean apply(Disease disease, long durationMs, int skillX, MobSkill skill) {
        if (!BotDebuffTable.isDebuff(disease)) {
            return false;
        }
        if (active.size() >= BotDebuffTable.MAX_ACTIVE) {
            return false; // matches Character.giveDebuff's cap
        }
        long now = System.currentTimeMillis();
        long duration = durationMs > 0 ? durationMs : BotDebuffTable.DEFAULT_DURATION_MS;
        long poisonFirstAt = BotDebuffTable.poisons(disease) ? now + BotDebuffTable.POISON_TICK_MS : 0L;
        Entry prior = active.putIfAbsent(disease, new Entry(now + duration, skill, poisonFirstAt));
        if (prior != null) {
            return false; // already suffering it
        }
        if (skill != null) {
            broadcastGive(disease, skill, skillX);
        }
        return true;
    }

    /**
     * Advances the debuff clock: expires anything past due (with a cancel packet) and runs one poison
     * tick when its period elapses. Called once per bot tick.
     */
    public void tick() {
        if (active.isEmpty() || chr == null) {
            return;
        }
        if (chr.getMap() == null || chr.getHp() <= 0) {
            clearAll(); // leaving the world / already a corpse: the packets no longer matter
            return;
        }
        long now = System.currentTimeMillis();
        for (Disease disease : active.keySet()) {
            Entry entry = active.get(disease);
            if (entry == null) {
                continue;
            }
            if (now >= entry.expiresAtMs) {
                if (active.remove(disease, entry)) {
                    broadcastCancel(disease);
                }
                continue;
            }
            if (BotDebuffTable.poisons(disease) && now >= entry.nextPoisonAtMs) {
                entry.nextPoisonAtMs = now + BotDebuffTable.POISON_TICK_MS;
                applyPoison((int) Math.round(chr.getCurrentMaxHp() * BotDebuffTable.POISON_MAX_HP_FRACTION));
            }
        }
    }

    /**
     * Drops every disease, cancelling the foreign effect first when anyone can see the bot. Used on a
     * map change (the engine does not carry diseases across maps either) and when a bot dies or is
     * retyped. No-op when nothing is active.
     */
    public void clearAll() {
        if (active.isEmpty()) {
            return;
        }
        for (Disease disease : active.keySet()) {
            broadcastCancel(disease);
        }
        active.clear();
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void applyPoison(int damage) {
        if (chr == null || damage <= 0) {
            return;
        }
        // Same floor semantics as contact damage: poison never kills - it pins the bot at the health
        // floor. Actual death stays the damage layer's / BotDeath's job, so a bot cannot die to a
        // side-effect with nobody standing it back up.
        int floor = BotHealthFloor.floorFor(chr.getCurrentMaxHp());
        int hpDamage = Math.min(damage, Math.max(0, chr.getHp() - floor));
        if (hpDamage <= 0) {
            return;
        }
        chr.safeAddHP(-hpDamage);
        chr.updatePartyMemberHP();
    }

    private void broadcastGive(Disease disease, MobSkill skill, int skillX) {
        if (!observed()) {
            return;
        }
        List<Pair<Disease, Integer>> statup =
                Collections.singletonList(new Pair<>(disease, skillX));
        chr.getMap().broadcastMessage(chr,
                BotDebuffTable.slows(disease)
                        ? PacketCreator.giveForeignSlowDebuff(chr.getId(), statup, skill)
                        : PacketCreator.giveForeignDebuff(chr.getId(), statup, skill),
                false);
    }

    private void broadcastCancel(Disease disease) {
        if (chr == null || chr.getMap() == null || !observed()) {
            return;
        }
        chr.getMap().broadcastMessage(chr,
                BotDebuffTable.slows(disease)
                        ? PacketCreator.cancelForeignSlowDebuff(chr.getId())
                        : PacketCreator.cancelForeignDebuff(chr.getId(), disease.getValue()),
                false);
    }

    private boolean observed() {
        return chr != null && chr.getMap() != null && GCMovement.isMapObserved(chr.getMapId());
    }
}
