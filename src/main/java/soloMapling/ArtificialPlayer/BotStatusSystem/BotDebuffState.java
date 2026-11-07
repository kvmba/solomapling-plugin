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
 * Why the plugin owns the behaviour state: the engine only applies a disease from the mob's
 * controller packet path (see BotDebuffTable), which a headless bot never triggers. The plugin
 * therefore models the effect and reuses the engine's PacketCreator to render it for onlookers - the
 * same "bots show real buff/debuff packets but own their own state" contract the buff system already
 * follows.
 *
 * That is not the only way a bot can catch a disease, though: a Monster Carnival opponent's tab-1
 * Seal / Darkness / Weakness / Slow / Poison reaches the bot through the engine's own
 * {@code Character.giveDebuff}, as does the GM {@code /debuff} command and any quest script. Those
 * copies live in {@code Character.diseases}, and the engine's disease-expire task is registered for
 * real players only (at login), so left alone they would gate nothing and never wear off. Such a
 * disease is ADOPTED here on the next tick and routed back through the engine when it ends - see
 * {@link #syncHostDiseases} - so every source constrains the bot through one set of behaviour queries.
 *
 * Threading: apply / tick / clearAll run on a bot's movement or macro tick; the getters are read from
 * the movement, attack and contact-damage threads. The map is concurrent and each entry's mutable
 * fields are volatile, so a reader never sees a torn entry and a writer never blocks a reader.
 */
public final class BotDebuffState {

    private final Character chr;
    private final Map<Disease, Entry> active = new ConcurrentHashMap<>();
    // Same-disease lockout after a disease ENDED, keyed disease -> epoch ms it stops being immune.
    // Mirrors the engine's Character.debuffImmuneUntil: a mob parked on a bot cannot re-apply a
    // disease the moment it wears off.
    private final Map<Disease, Long> immuneUntil = new ConcurrentHashMap<>();
    // Earliest epoch-ms at which the engine's own disease list is worth re-reading. Guarded by
    // nothing: a racing sync does one redundant read, never a wrong state, and both writers only
    // ever push the deadline forward.
    private volatile long nextHostSyncAtMs;

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
        /**
         * True when the engine's own {@code Character.diseases} also carries this disease - a real
         * opponent in Monster Carnival, a GM command or a quest script put it there through
         * {@code Character.giveDebuff}. The engine owns that copy's cancel frame (and its
         * post-debuff lockout), so this state mirrors it for the behaviour layers instead of
         * rendering it a second time; see {@link #syncHostDiseases(long)}.
         */
        final boolean hostOwned;
        volatile long nextPoisonAtMs;

        Entry(long expiresAtMs, MobSkill skill, long nextPoisonAtMs, boolean hostOwned) {
            this.expiresAtMs = expiresAtMs;
            this.skill = skill;
            this.nextPoisonAtMs = nextPoisonAtMs;
            this.hostOwned = hostOwned;
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

    /**
     * True while a disease that just ENDED is still inside its post-debuff immunity window. The engine
     * has the same rule ({@code Character.isDebuffImmune}), so a bot is not re-locked the instant a
     * stun wears off. Package-visible so the lockout is unit-testable.
     */
    boolean isImmune(Disease disease, long now) {
        Long until = immuneUntil.get(disease);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            immuneUntil.remove(disease, until);
            return false;
        }
        return true;
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
        return apply(disease, durationMs, skillX, skill, System.currentTimeMillis());
    }

    /**
     * As above with the clock supplied, so the post-debuff immunity window is exercised without
     * waiting on real time.
     */
    boolean apply(Disease disease, long durationMs, int skillX, MobSkill skill, long now) {
        if (!BotDebuffTable.isDebuff(disease)) {
            return false;
        }
        // Same precedence as Character.giveDebuff: immune, then at-cap. The engine's own copy counts
        // as already suffering - adoption owns its clock, and a second one here would outlive it.
        if (isImmune(disease, now) || isHostHeld(disease)) {
            return false;
        }
        if (active.size() >= BotDebuffTable.MAX_ACTIVE) {
            return false; // matches Character.giveDebuff's cap
        }
        long duration = durationMs > 0 ? durationMs : BotDebuffTable.DEFAULT_DURATION_MS;
        long poisonFirstAt = BotDebuffTable.poisons(disease) ? now + BotDebuffTable.POISON_TICK_MS : 0L;
        Entry prior = active.putIfAbsent(disease, new Entry(now + duration, skill, poisonFirstAt, false));
        if (prior != null) {
            return false; // already suffering it, or lost a race with a concurrent apply
        }
        if (skill != null) {
            broadcastGive(disease, skill, skillX);
        }
        return true;
    }

    /**
     * Whether the engine's own {@code Character.diseases} holds {@code disease}. A second clock here
     * would keep gating it after the engine's copy ends, so the two sources never both own a disease.
     */
    private boolean isHostHeld(Disease disease) {
        return chr != null && chr.hasDisease(disease);
    }

    /**
     * Advances the debuff clock: expires anything past due (with a cancel packet) and runs one poison
     * tick when its period elapses. Called once per bot tick.
     */
    public void tick() {
        tickAt(System.currentTimeMillis());
    }

    /**
     * Core of {@link #tick()} with the clock supplied, so expiry, the post-debuff immunity window and
     * the poison cadence are all exercisable without real time. A state with no character (the
     * character-free test seam) still expires, it just has no packet to cancel and no HP to drain.
     */
    void tickAt(long now) {
        // A disease can reach the bot WITHOUT going through this class: Monster Carnival's tab-1
        // debuffs (MonsterCarnivalHandler -> Character.giveDebuff), the GM /debuff command and quest
        // scripts all write the engine's own Character.diseases, and the engine only runs its
        // disease-expire task for real players (registered at login) - so for a bot such a disease
        // would both gate nothing and never end. Adopt them here, before the expiry pass, so the
        // movement / attack / damage layers see one live picture regardless of who applied it.
        //
        // Both the size read and the list read cost the character's lock (the latter also builds a
        // fresh map), and this runs at 20 Hz on an observed map, so they are on the sync clock:
        // a healthy bot pays one lock acquisition every HOST_SYNC_INTERVAL_MS, never 20 a second.
        if (chr != null && now >= nextHostSyncAtMs) {
            nextHostSyncAtMs = now + BotDebuffTable.HOST_SYNC_INTERVAL_MS;
            if (chr.getDiseasesSize() > 0 || hasHostOwnedDisease()) {
                syncHostDiseases(now);
            }
        }
        if (active.isEmpty()) {
            return;
        }
        if (chr != null && (chr.getMap() == null || chr.getHp() <= 0)) {
            clearAll(); // leaving the world / already a corpse: the packets no longer matter
            return;
        }
        for (Map.Entry<Disease, Entry> e : active.entrySet()) {
            Disease disease = e.getKey();
            Entry entry = e.getValue();
            if (now >= entry.expiresAtMs) {
                if (active.remove(disease, entry)) {
                    end(entry, disease);
                    // The engine stamps the same lockout when its own disease expires
                    // (Character.dispelDebuff): the same disease cannot land again for a while.
                    immuneUntil.put(disease, now + BotDebuffTable.DEBUFF_IMMUNE_MS);
                }
                continue;
            }
            if (chr != null && BotDebuffTable.poisons(disease) && now >= entry.nextPoisonAtMs) {
                entry.nextPoisonAtMs = now + BotDebuffTable.POISON_TICK_MS;
                applyPoison((int) Math.round(chr.getCurrentMaxHp() * BotDebuffTable.POISON_MAX_HP_FRACTION));
            }
        }
    }

    /**
     * Whether this state is currently mirroring at least one engine-owned disease - the case where
     * the engine's list can go empty behind our back (an all-cure, the match-end {@code
     * dispelDebuffs}), which is exactly when the reverse edge of {@link #syncHostDiseases} has to run.
     */
    private boolean hasHostOwnedDisease() {
        for (Entry entry : active.values()) {
            if (entry.hostOwned) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mirror the engine's own {@code Character.diseases} into this state, in both directions.
     *
     * <p>The plugin is the authority for diseases a bot takes from mob contact
     * ({@link BotDebuffApplier}), and the engine is the authority for everything applied through
     * {@code Character.giveDebuff} - a Monster Carnival opponent's tab-1 Seal / Darkness /
     * Weakness / Slow / Poison, the GM {@code /debuff} command, a quest script. Both end up here,
     * which is what makes an opponent's debuff actually constrain the bot instead of only painting
     * an icon over it.
     *
     * <p>An adopted entry keeps the engine's remaining time (so it ends when the engine says) and is
     * marked {@code hostOwned}, which routes its end through {@code Character.dispelDebuff} - the
     * engine already broadcast the give frame and must be the one to broadcast the cancel. A
     * disease this state already tracks is converted rather than duplicated, so two sources can
     * never disagree about how long it lasts. The reverse edge matters too: an engine-side clear
     * (an all-cure, the match-end {@code dispelDebuffs}) must not leave this state gating a
     * disease nobody is suffering any more.
     *
     * <p>Runs on a {@link BotDebuffTable#HOST_SYNC_INTERVAL_MS} clock rather than every movement tick:
     * reading the engine's list means a fresh map built under its character lock, and re-doing that
     * at 20 Hz for every debuffed bot is exactly the per-tick copy this state otherwise avoids. The
     * caller arms that clock; this method only guards the read. The interval is still six times finer
     * than the 1500 ms expire pass the engine runs for real players
     * ({@code Character.diseaseExpireTask}), so an adopted disease starts and ends within a fraction
     * of the time the engine itself would take.
     */
    private void syncHostDiseases(long now) {
        if (chr == null) {
            return;
        }
        Map<Disease, Pair<Long, MobSkill>> host = chr.getAllDiseases();
        for (Map.Entry<Disease, Entry> e : active.entrySet()) {
            Entry entry = e.getValue();
            if (entry.hostOwned && !host.containsKey(e.getKey())
                    && active.remove(e.getKey(), entry)) {
                immuneUntil.put(e.getKey(), now + BotDebuffTable.DEBUFF_IMMUNE_MS);
            }
        }
        for (Map.Entry<Disease, Pair<Long, MobSkill>> h : host.entrySet()) {
            Disease disease = h.getKey();
            if (!BotDebuffTable.isDebuff(disease)) {
                continue;
            }
            Entry mine = active.get(disease);
            if (mine != null && mine.hostOwned) {
                continue; // adopted already; the expiry stored with it drives the engine copy
            }
            long expiresAt = now + Math.max(0L, h.getValue().getLeft());
            long poisonFirstAt = BotDebuffTable.poisons(disease)
                    ? (mine != null ? mine.nextPoisonAtMs : now + BotDebuffTable.POISON_TICK_MS)
                    : 0L;
            Entry adopted = new Entry(expiresAt, h.getValue().getRight(), poisonFirstAt, true);
            if (mine != null) {
                active.replace(disease, mine, adopted); // one disease, one clock: the engine's
                continue;
            }
            if (active.size() >= BotDebuffTable.MAX_ACTIVE && !evictSimulatedFor()) {
                continue; // the cap is the engine's own, and an engine copy outranks a simulated one
            }
            active.putIfAbsent(disease, adopted); // a concurrent write wins; the next sync reconciles
        }
    }

    /**
     * Make room for an engine-applied disease by dropping one the plugin simulated from mob contact.
     *
     * <p>Both sources share this one map, so the cap holds either way - the question at the cap is
     * only WHICH two survive. An engine-applied disease is already on every observer's client (the
     * engine broadcast its give frame and will not cancel it for us) and the engine has already
     * throttled it, so dropping it here would reproduce the original defect in a corner: a bot that
     * visibly wears a Seal and keeps swinging. The simulated entry is the one that can be retired
     * honestly, because its cancel frame is ours to send.
     *
     * @return true when room was made
     */
    private boolean evictSimulatedFor() {
        for (Map.Entry<Disease, Entry> e : active.entrySet()) {
            if (!e.getValue().hostOwned && active.remove(e.getKey(), e.getValue())) {
                broadcastCancel(e.getKey());
                return true;
            }
        }
        return false;
    }

    /**
     * Drops every disease this state holds. Used on a map change, and when a bot dies or is
     * retyped.
     *
     * <p>The cancellation is the whole point: the plugin sends the matching cancel frame for a
     * disease IT rendered, while an engine-owned copy (adopted from {@code Character.diseases}) is
     * ended through {@code Character.dispelDebuff} - the engine broadcast the give frame, so it must
     * broadcast the cancel, and it is also what keeps an all-cure (a quest script's
     * {@code dispelDebuffs}, the match-end sweep) from leaving a stale copy gating this bot.
     */
    public void clearAll() {
        if (active.isEmpty()) {
            return;
        }
        for (Map.Entry<Disease, Entry> e : active.entrySet()) {
            end(e.getValue(), e.getKey());
        }
        active.clear();
    }

    /**
     * Retire one entry that was just removed from {@link #active}.
     *
     * <p>An engine-owned copy is ended through {@code Character.dispelDebuff}: the engine broadcast
     * the give frame, and it is the one that knows the matching cancel (the slow frame is a
     * different opcode from every other disease). It also clears the engine's own bookkeeping, so a
     * bot never sits on a stale entry that would block the engine's two-disease cap or be
     * re-announced to the next arrival. A plugin-owned entry keeps the plugin's own cancel.
     */
    private void end(Entry entry, Disease disease) {
        if (entry.hostOwned && chr != null && chr.getMap() != null) {
            chr.dispelDebuff(disease);
        } else {
            broadcastCancel(disease);
        }
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
