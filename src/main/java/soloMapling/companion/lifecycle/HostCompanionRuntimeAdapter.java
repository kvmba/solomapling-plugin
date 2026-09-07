package soloMapling.companion.lifecycle;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.util.DatabaseConnection;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.CompanionBot;
import soloMapling.companion.CompanionRoster;
import soloMapling.companion.agent.ProductionCompanionBrain;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.progression.CompanionBuildAllocator;
import soloMapling.companion.progression.CompanionCareerBuild;
import soloMapling.companion.progression.CompanionCareerPath;
import soloMapling.companion.routine.CompanionNoviceLevel;
import soloMapling.companion.routine.OfflineProgressionSettlement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Production bridge to BeiDou's native character persistence and bot FSM.
 */
public final class HostCompanionRuntimeAdapter implements CompanionRuntimeAdapter {
    private static final Logger log = LoggerFactory.getLogger(HostCompanionRuntimeAdapter.class);
    private static final int HARD_EXPERIENCE_CAP = 25_000;
    private static final int HARD_MESO_CAP = 100_000;
    private static final int MAX_ADVANCEMENTS_PER_RECONCILE = 4;

    /**
     * Most of one level a settlement may grant.
     *
     * <p>A companion below {@link CompanionNoviceLevel#VALUE} gets nothing at
     * all: it is still on the beginner island, and the island is the tutorial.
     * A bot that came back from a night offline two levels higher skipped the
     * part where it was supposed to learn to swing — and since the island is a
     * one-way trip out, settling it forward would strand it there.</p>
     *
     * <p>Deliberately small, and for a reason that has changed: a companion no
     * longer relies on offline time to advance. While it is online it levels by
     * fighting — really, when a player is watching it, and by simulated kills
     * when nobody is — so the offline settlement is only meant to show that time
     * passed, not to be the engine of its career. A fifth of a level keeps it
     * that way round: a companion that was away a night comes back a little
     * further along, never a level or three ahead of the one that logged in and
     * actually fought.</p>
     *
     * <p>The old flat 25,000 cap was calibrated for somebody in their 30s and was
     * absurd at the bottom: at level 1 it is a thousand times the 15 EXP the
     * island asks for, so one offline night could carry a new companion clean off
     * the island without it ever swinging. Tying the cap to the host's own EXP
     * table scales it sensibly instead — about 3 EXP at level 1, 248 at level 10,
     * 2,695 at level 30. The hard cap still binds at the top, where a level costs
     * more than it anyway.</p>
     */
    static final double SETTLEMENT_LEVEL_FRACTION = 0.2;

    @Override
    public int persistedLevel(CompanionProfile profile) {
        String sql = "SELECT level FROM characters WHERE id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, profile.characterId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "Companion character not found: " + profile.characterId());
                }
                return Math.max(1, resultSet.getInt("level"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to read companion level " + profile.characterId(), exception);
        }
    }

    @Override
    public LoadedCompanion load(CompanionProfile profile) {
        CompanionRoster.register(profile.characterId());
        Character character = BotGeneration.loadPersistentBot(profile.characterId());
        CompanionCareerBuild careerBuild =
                CompanionCareerBuild.parse(profile.careerBuild());
        log.info("Companion build dry-run cid={} job={} level={} build={} {}",
                character.getId(), character.getJob().getId(), character.getLevel(),
                careerBuild.id(),
                CompanionBuildAllocator.preview(character, careerBuild).summary());
        return new HostLoadedCompanion(character, careerBuild);
    }

    @Override
    public CareerReconciliation reconcileCareer(
            LoadedCompanion companion, CompanionProfile profile) {
        CompanionCareerBuild careerBuild = CompanionCareerBuild.parse(profile.careerBuild());
        HostLoadedCompanion loaded = (HostLoadedCompanion) companion;
        if (loaded.careerBuild() != careerBuild) {
            throw new IllegalStateException("Loaded companion career build changed");
        }
        return reconcileCareer(unwrap(companion), careerBuild);
    }

    @Override
    public String buildDiagnostics(
            LoadedCompanion companion,
            CompanionProfile profile
    ) {
        CompanionCareerBuild careerBuild =
                CompanionCareerBuild.parse(profile.careerBuild());
        return "careerBuild=" + careerBuild.id()
                + ";ruleset=" + CompanionCareerBuild.RULESET_VERSION
                + ";dryRun=true;"
                + CompanionBuildAllocator.preview(
                        unwrap(companion), careerBuild).summary();
    }

    private static CareerReconciliation reconcileCareer(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        CompanionBuildAllocator.Allocation before =
                CompanionBuildAllocator.allocate(character, careerBuild);
        int advancements = advanceCareer(character, careerBuild);
        CompanionBuildAllocator.Allocation after =
                CompanionBuildAllocator.allocate(character, careerBuild);
        int apSpent = before.apSpent() + after.apSpent();
        int spSpent = before.spSpent() + after.spSpent();
        String detail = joinDetail(before.summary(), after.summary());
        if (apSpent > 0 || spSpent > 0) {
            log.info("Companion build allocated cid={} build={} job={} level={} apSpent={} spSpent={}",
                    character.getId(), careerBuild.id(),
                    character.getJob().getId(), character.getLevel(),
                    apSpent, spSpent);
        }
        return new CareerReconciliation(
                advancements, apSpent, spSpent,
                "careerBuild=" + careerBuild.id() + ";ruleset="
                        + CompanionCareerBuild.RULESET_VERSION + ";" + detail);
    }

    private static int advanceCareer(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        int advancements = 0;
        while (advancements < MAX_ADVANCEMENTS_PER_RECONCILE) {
            var nextJobId = CompanionCareerPath.nextJobId(
                    character.getJob().getId(), character.getLevel(), careerBuild);
            if (nextJobId.isEmpty()) {
                break;
            }
            Job previous = character.getJob();
            if (previous == Job.BEGINNER
                    && !meetsFirstJobRequirement(character, careerBuild)) {
                log.warn("Companion first job deferred cid={} build={} level={} str={} dex={} int={} luk={}",
                        character.getId(), careerBuild.id(), character.getLevel(),
                        character.getStr(), character.getDex(),
                        character.getInt(), character.getLuk());
                break;
            }
            Job next = Job.getById(nextJobId.getAsInt());
            if (next == Job.BEGINNER || next == previous) {
                throw new IllegalStateException(
                        "Invalid companion career transition " + previous + " -> " + nextJobId.getAsInt());
            }
            character.changeJob(next);
            character.equipChanged();
            advancements++;
            log.info("Companion career advanced cid={} level={} fromJob={} toJob={}",
                    character.getId(), character.getLevel(), previous.getId(), next.getId());
        }
        return advancements;
    }

    private static boolean meetsFirstJobRequirement(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        return switch (careerBuild.firstJobId()) {
            case 100 -> character.getStr() >= 35;
            case 200 -> character.getInt() >= 20;
            case 300, 400 -> character.getDex() >= 25;
            case 500 -> character.getDex() >= 20;
            default -> false;
        };
    }

    @Override
    public void applyProgression(
            LoadedCompanion companion,
            OfflineProgressionSettlement settlement) {
        Character character = unwrap(companion);
        if (CompanionNoviceLevel.isNovice(character.getLevel())) {
            // Still a novice: nothing at all, mesos included. The settlement's
            // settledThrough is still recorded upstream, so this time is not
            // banked for later — a companion that finally reaches the bar does
            // not collect a backlog for the weeks it spent below it.
            log.debug("Companion offline settlement skipped for novice cid={} level={} below={}",
                    character.getId(), character.getLevel(), CompanionNoviceLevel.VALUE);
            return;
        }
        int experience = Math.toIntExact(Math.min(
                settlement.experience(), experienceCap(character.getLevel())));
        int mesos = Math.toIntExact(Math.min(settlement.mesos(), HARD_MESO_CAP));
        if (experience > 0) {
            grantOfflineExperience((HostLoadedCompanion) companion, experience);
        }
        if (mesos > 0) {
            character.gainMeso(mesos, false, false, false);
        }
    }

    /**
     * The most EXP one settlement may grant at this level: a fifth of a level,
     * by the host's own table, and never more than the flat cap.
     *
     * <p>Read from {@link ExpTable} rather than assumed, so a host that retunes
     * its curve is followed automatically. A level past the end of the table
     * falls back to the flat cap rather than to zero — an over-generous
     * settlement is recoverable, a companion that can never gain offline
     * experience is not.</p>
     */
    static long experienceCap(int level) {
        final int needed;
        try {
            // The host's table is a plain array indexed by level, so the only
            // way this fails is a level past its end.
            needed = ExpTable.getExpNeededForLevel(Math.max(0, level));
        } catch (IndexOutOfBoundsException ignored) {
            return HARD_EXPERIENCE_CAP;
        }
        if (needed <= 0) {
            return HARD_EXPERIENCE_CAP;
        }
        long scaled = Math.round(needed * SETTLEMENT_LEVEL_FRACTION);
        return Math.max(1, Math.min(HARD_EXPERIENCE_CAP, scaled));
    }

    private static void grantOfflineExperience(HostLoadedCompanion companion, int experience) {
        Character character = companion.character();
        int remaining = experience;
        while (remaining > 0) {
            advanceCareer(character, companion.careerBuild());
            if (character.getLevel() >= character.getMaxLevel()) {
                log.warn("Companion offline EXP stopped at career cap cid={} level={} job={} remaining={}",
                        character.getId(), character.getLevel(), character.getJob().getId(), remaining);
                return;
            }
            int needed = ExpTable.getExpNeededForLevel(character.getLevel()) - character.getExp();
            int grant = Math.min(remaining, Math.max(1, needed));
            character.gainExp(grant, false, false);
            remaining -= grant;
        }
    }

    @Override
    public void saveCheckpoint(LoadedCompanion companion) {
        unwrap(companion).saveCharToDB(true);
    }

    @Override
    public void attachAndStart(LoadedCompanion companion) {
        Character character = unwrap(companion);
        CompanionBot.attachAndStart(character, ProductionCompanionBrain.createDefault());
    }

    @Override
    public void stopSaveAndRemove(LoadedCompanion companion) {
        Character character = unwrap(companion);
        BotSM bot = CharacterStorage.getBotById(character.getId());
        if (bot != null) {
            bot.setRunning(false);
            bot.stopScheduledTask();
        }
        BotGeneration.saveAndRemovePersistentBot(character);
    }

    private static Character unwrap(LoadedCompanion companion) {
        if (!(companion instanceof HostLoadedCompanion host)) {
            throw new IllegalArgumentException("Loaded companion was not created by this adapter");
        }
        return host.character();
    }

    private static String joinDetail(String left, String right) {
        if (left.equals(right)) {
            return left;
        }
        return left + ";" + right;
    }

    private record HostLoadedCompanion(
            Character character,
            CompanionCareerBuild careerBuild
    ) implements LoadedCompanion {
        private HostLoadedCompanion {
            if (character == null) {
                throw new NullPointerException("character");
            }
            if (careerBuild == null) {
                throw new NullPointerException("careerBuild");
            }
        }

        @Override
        public int characterId() {
            return character.getId();
        }

        @Override
        public int level() {
            return character.getLevel();
        }
    }
}
