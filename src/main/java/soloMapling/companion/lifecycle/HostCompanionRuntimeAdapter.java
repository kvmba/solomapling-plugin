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
        return new HostLoadedCompanion(
                BotGeneration.loadPersistentBot(profile.characterId()),
                CompanionCareerBuild.parse(profile.careerBuild()));
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
        int experience = Math.toIntExact(Math.min(
                settlement.experience(), HARD_EXPERIENCE_CAP));
        int mesos = Math.toIntExact(Math.min(settlement.mesos(), HARD_MESO_CAP));
        if (experience > 0) {
            grantOfflineExperience((HostLoadedCompanion) companion, experience);
        }
        if (mesos > 0) {
            character.gainMeso(mesos, false, false, false);
        }
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
