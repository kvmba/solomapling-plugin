package soloMapling.companion.lifecycle;

import org.gms.client.Character;
import org.gms.util.DatabaseConnection;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.CompanionBot;
import soloMapling.companion.CompanionRoster;
import soloMapling.companion.agent.ProductionCompanionBrain;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.routine.OfflineProgressionSettlement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Production bridge to BeiDou's native character persistence and bot FSM.
 */
public final class HostCompanionRuntimeAdapter implements CompanionRuntimeAdapter {
    private static final int HARD_EXPERIENCE_CAP = 25_000;
    private static final int HARD_MESO_CAP = 100_000;

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
        return new HostLoadedCompanion(BotGeneration.loadPersistentBot(profile.characterId()));
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
            character.gainExp(experience, false, false);
        }
        if (mesos > 0) {
            character.gainMeso(mesos, false, false, false);
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

    private record HostLoadedCompanion(Character character) implements LoadedCompanion {
        private HostLoadedCompanion {
            if (character == null) {
                throw new NullPointerException("character");
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
