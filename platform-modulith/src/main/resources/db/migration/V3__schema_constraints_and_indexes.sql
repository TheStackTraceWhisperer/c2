-- ============================================================================
-- V3: SCHEMA CONSTRAINTS & INDEX OPTIMIZATION
-- ============================================================================

-- Index active_sessions by session_id for disconnect / routing lookups
CREATE INDEX IF NOT EXISTS idx_active_sessions_session_id
    ON player.active_sessions (session_id);

-- Enforce referential integrity from player.characters(world_id) to infra.worlds(id)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_characters_world_id') THEN
        ALTER TABLE player.characters
            ADD CONSTRAINT fk_characters_world_id
            FOREIGN KEY (world_id) REFERENCES infra.worlds (id);
    END IF;
END $$;

-- Drop redundant indexes duplicating UNIQUE constraints or leading PK columns
DROP INDEX IF EXISTS player.idx_accounts_username;
DROP INDEX IF EXISTS player.idx_accounts_email;
DROP INDEX IF EXISTS player.idx_character_jobs_char;
