ALTER TABLE `bot_profiles`
    ADD COLUMN `career_build` VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT 'Immutable v083 career build selected at provisioning'
        AFTER `persona_seed`;

UPDATE `bot_profiles` bp
    JOIN `characters` c ON c.id = bp.character_id
SET bp.career_build = CASE
    WHEN c.job BETWEEN 110 AND 112 THEN 'hero-sword'
    WHEN c.job BETWEEN 120 AND 122 THEN 'paladin-sword'
    WHEN c.job BETWEEN 130 AND 132 THEN 'dark-knight-spear'
    WHEN c.job BETWEEN 210 AND 212 THEN 'fire-poison-archmage'
    WHEN c.job BETWEEN 220 AND 222 THEN 'ice-lightning-archmage'
    WHEN c.job BETWEEN 230 AND 232 THEN 'bishop'
    WHEN c.job BETWEEN 310 AND 312 THEN 'bowmaster'
    WHEN c.job BETWEEN 320 AND 322 THEN 'marksman'
    WHEN c.job BETWEEN 410 AND 412 THEN 'night-lord'
    WHEN c.job BETWEEN 420 AND 422 THEN 'shadower'
    WHEN c.job BETWEEN 510 AND 512 THEN 'buccaneer'
    WHEN c.job BETWEEN 520 AND 522 THEN 'corsair'
    WHEN c.job = 100 THEN ELT(MOD(ABS(bp.persona_seed), 3) + 1,
        'hero-sword', 'paladin-sword', 'dark-knight-spear')
    WHEN c.job = 200 THEN ELT(MOD(ABS(bp.persona_seed), 3) + 1,
        'fire-poison-archmage', 'ice-lightning-archmage', 'bishop')
    WHEN c.job = 300 THEN ELT(MOD(ABS(bp.persona_seed), 2) + 1,
        'bowmaster', 'marksman')
    WHEN c.job = 400 THEN ELT(MOD(ABS(bp.persona_seed), 2) + 1,
        'night-lord', 'shadower')
    WHEN c.job = 500 THEN ELT(MOD(ABS(bp.persona_seed), 2) + 1,
        'buccaneer', 'corsair')
    ELSE ELT(MOD(ABS(bp.persona_seed), 12) + 1,
        'hero-sword', 'paladin-sword', 'dark-knight-spear',
        'fire-poison-archmage', 'ice-lightning-archmage', 'bishop',
        'bowmaster', 'marksman', 'night-lord', 'shadower',
        'buccaneer', 'corsair')
END;
