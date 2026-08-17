-- 1. Accounts (Login Server)
CREATE TABLE accounts
(
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(255)       NOT NULL,
    created_at    TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_username ON accounts (username);

-- 2. Realms / Worlds (Lobby & World Coordinator)
CREATE TABLE realms
(
    id           INT PRIMARY KEY,
    name         VARCHAR(64) UNIQUE NOT NULL,
    realm_type   VARCHAR(16)        NOT NULL DEFAULT 'STANDARD',
    max_capacity INT                NOT NULL DEFAULT 2000
);

-- Seed baseline development realms
INSERT INTO realms (id, name, realm_type, max_capacity)
VALUES
    (1, 'Development Realm 1', 'STANDARD', 2000),
    (2, 'Development Realm 2', 'STANDARD', 2000)
    ON CONFLICT (id) DO NOTHING;

-- 3. Characters (Lobby, World, & Zone Persistence)
CREATE TABLE characters
(
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT             NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    realm_id        INT                NOT NULL REFERENCES realms (id),
    name            VARCHAR(32) UNIQUE NOT NULL,
    level           INT                NOT NULL DEFAULT 1,
    character_class VARCHAR(32)        NOT NULL,
    zone_id         INT                NOT NULL DEFAULT 1,
    pos_x           REAL               NOT NULL DEFAULT 0.0,
    pos_y           REAL               NOT NULL DEFAULT 0.0,
    pos_z           REAL               NOT NULL DEFAULT 0.0,
    yaw             REAL               NOT NULL DEFAULT 0.0,
    pitch           REAL               NOT NULL DEFAULT 0.0,
    last_saved_at   TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_characters_account_id ON characters (account_id);
CREATE INDEX idx_characters_realm_account ON characters (realm_id, account_id);

-- Sequence starting offset
SELECT setval('accounts_id_seq', (SELECT GREATEST(MAX(id), 100000000) FROM accounts));
SELECT setval('characters_id_seq', (SELECT GREATEST(MAX(id), 100000000) FROM characters));