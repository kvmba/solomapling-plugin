CREATE TABLE IF NOT EXISTS `bot_profiles`
(
    `character_id`     INT(11)      NOT NULL COMMENT 'Companion character id',
    `account_id`       INT(11)      NOT NULL COMMENT 'Owning native account id',
    `display_name`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'Companion display name',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Whether the companion is roster-enabled',
    `status`           VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT 'Lifecycle status',
    `persona`          TEXT         NULL COMMENT 'Stable persona description',
    `persona_seed`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Deterministic persona seed',
    `system_prompt`    TEXT         NULL COMMENT 'Optional host system prompt',
    `greeting`         TEXT         NULL COMMENT 'Optional first-contact greeting',
    `routine_timezone` VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    `routine_profile`  TEXT         NULL COMMENT 'Companion routine definition',
    `growth_stage`     VARCHAR(32)  NOT NULL DEFAULT 'initial',
    `current_mode`     VARCHAR(32)  NOT NULL DEFAULT 'idle',
    `last_online_at`   TIMESTAMP(3) NULL DEFAULT NULL,
    `last_settled_at`  TIMESTAMP(3) NULL DEFAULT NULL,
    `created_at`       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`character_id`),
    KEY `idx_bot_profiles_roster` (`enabled`, `status`, `account_id`, `updated_at`, `character_id`),
    CONSTRAINT `fk_bot_profiles_account`
        FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_bot_profiles_character`
        FOREIGN KEY (`character_id`) REFERENCES `characters` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT 'LLM companion profiles';

CREATE TABLE IF NOT EXISTS `bot_relationships`
(
    `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `character_id`           INT(11)         NOT NULL COMMENT 'Companion profile character id',
    `related_character_id`   INT(11)         NOT NULL COMMENT 'Native character known by the companion',
    `relationship_type`      VARCHAR(32)     NOT NULL DEFAULT 'acquaintance',
    `familiarity`            SMALLINT        NOT NULL DEFAULT 0,
    `trust`                  SMALLINT        NOT NULL DEFAULT 0,
    `affinity`               SMALLINT        NOT NULL DEFAULT 0,
    `interaction_count`      INT UNSIGNED    NOT NULL DEFAULT 0,
    `summary`                TEXT            NULL,
    `last_interaction_at`    TIMESTAMP(3)    NULL DEFAULT NULL,
    `created_at`             TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`             TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bot_relationship_pair` (`character_id`, `related_character_id`),
    KEY `idx_bot_relationship_roster` (`related_character_id`, `last_interaction_at`, `character_id`),
    CONSTRAINT `fk_bot_relationship_profile`
        FOREIGN KEY (`character_id`) REFERENCES `bot_profiles` (`character_id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_bot_relationship_character`
        FOREIGN KEY (`related_character_id`) REFERENCES `characters` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT 'Companion relationships with native characters';

CREATE TABLE IF NOT EXISTS `bot_memories`
(
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `character_id`        INT(11)         NOT NULL COMMENT 'Companion profile character id',
    `role`                VARCHAR(32)     NOT NULL DEFAULT 'system' COMMENT 'Conversation or memory role',
    `memory_type`         VARCHAR(32)     NOT NULL DEFAULT 'episodic',
    `source_character_id` INT(11)         NULL DEFAULT NULL COMMENT 'Optional native character source',
    `map_id`              INT             NULL DEFAULT NULL,
    `content`             LONGTEXT        NOT NULL,
    `tags`                VARCHAR(512)    NOT NULL DEFAULT '',
    `importance`          SMALLINT        NOT NULL DEFAULT 0,
    `salience`            DECIMAL(5, 4)   NOT NULL DEFAULT 0.0000,
    `strength`            DECIMAL(5, 4)   NOT NULL DEFAULT 1.0000,
    `archived`            TINYINT(1)      NOT NULL DEFAULT 0,
    `occurred_at`         TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `last_recalled_at`    TIMESTAMP(3)    NULL DEFAULT NULL,
    `expires_at`          TIMESTAMP(3)    NULL DEFAULT NULL,
    `created_at`          TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`          TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_bot_memories_role_time` (`character_id`, `role`, `occurred_at`, `id`),
    KEY `idx_bot_memories_recall` (`character_id`, `archived`, `memory_type`, `occurred_at`, `id`),
    KEY `idx_bot_memories_source_time`
        (`character_id`, `source_character_id`, `archived`, `occurred_at`, `id`),
    KEY `idx_bot_memories_map_time` (`character_id`, `map_id`, `archived`, `occurred_at`, `id`),
    KEY `idx_bot_memories_expiry` (`expires_at`),
    CONSTRAINT `fk_bot_memory_profile`
        FOREIGN KEY (`character_id`) REFERENCES `bot_profiles` (`character_id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_bot_memory_source_character`
        FOREIGN KEY (`source_character_id`) REFERENCES `characters` (`id`) ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT 'Time-ordered LLM companion memories';

CREATE TABLE IF NOT EXISTS `bot_knowledge`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `character_id`   INT(11)         NOT NULL COMMENT 'Companion profile character id',
    `knowledge_key`  VARCHAR(191)    NOT NULL COMMENT 'Stable key within a companion profile',
    `category`       VARCHAR(64)     NOT NULL DEFAULT 'general',
    `content`        LONGTEXT        NOT NULL,
    `source`         VARCHAR(255)    NULL DEFAULT NULL,
    `priority`       SMALLINT        NOT NULL DEFAULT 0,
    `enabled`        TINYINT(1)      NOT NULL DEFAULT 1,
    `created_at`     TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`     TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bot_knowledge_key` (`character_id`, `knowledge_key`),
    KEY `idx_bot_knowledge_lookup` (`character_id`, `enabled`, `category`, `priority`),
    CONSTRAINT `fk_bot_knowledge_profile`
        FOREIGN KEY (`character_id`) REFERENCES `bot_profiles` (`character_id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT 'Curated LLM companion knowledge';

CREATE TABLE IF NOT EXISTS `bot_activity_log`
(
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `character_id`       INT(11)         NOT NULL COMMENT 'Companion profile character id',
    `activity_type`      VARCHAR(64)     NOT NULL,
    `outcome`            VARCHAR(32)     NOT NULL DEFAULT 'unknown',
    `actor_role`         VARCHAR(32)     NOT NULL DEFAULT 'system',
    `actor_character_id` INT(11)         NULL DEFAULT NULL COMMENT 'Optional native character actor',
    `summary`            VARCHAR(512)    NOT NULL DEFAULT '',
    `details`            LONGTEXT        NULL,
    `occurred_at`        TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_at`         TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_bot_activity_role_time` (`character_id`, `actor_role`, `occurred_at`, `id`),
    KEY `idx_bot_activity_type_time` (`character_id`, `activity_type`, `occurred_at`, `id`),
    CONSTRAINT `fk_bot_activity_profile`
        FOREIGN KEY (`character_id`) REFERENCES `bot_profiles` (`character_id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_bot_activity_actor_character`
        FOREIGN KEY (`actor_character_id`) REFERENCES `characters` (`id`) ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT 'Append-only LLM companion activity log';
