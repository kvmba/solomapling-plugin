ALTER TABLE `bot_profiles`
    ADD COLUMN `career_build` VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT 'Immutable v083 career build selected at provisioning'
        AFTER `persona_seed`;
