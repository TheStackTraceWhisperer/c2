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

-- 2. Realms / Worlds (Reconciled into World Pods by Operator)
CREATE TABLE realms
(
    id           INT PRIMARY KEY,
    name         VARCHAR(64) UNIQUE NOT NULL,
    realm_type   VARCHAR(16)        NOT NULL DEFAULT 'STANDARD',
    host         VARCHAR(128)       NOT NULL DEFAULT '',
    port         INT                NOT NULL DEFAULT 9996,
    status       VARCHAR(32)        NOT NULL DEFAULT 'PENDING',
    max_capacity INT                NOT NULL DEFAULT 2000,
    last_updated TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

INSERT INTO realms (id, name, realm_type, max_capacity)
VALUES
    (1, 'Realm 1 - Prime', 'STANDARD', 2000),
    (2, 'Realm 2 - Secondary', 'STANDARD', 2000)
    ON CONFLICT (id) DO NOTHING;

-- 3. Zones (Reconciled into Zone Pods by Operator)
CREATE TABLE zones
(
    id             INT PRIMARY KEY,
    realm_id       INT                NOT NULL REFERENCES realms (id) ON DELETE CASCADE,
    name           VARCHAR(64)        NOT NULL,
    host           VARCHAR(128)       NOT NULL DEFAULT '',
    port           INT                NOT NULL DEFAULT 9997,
    status         VARCHAR(32)        NOT NULL DEFAULT 'PENDING',
    is_static      BOOLEAN            NOT NULL DEFAULT true,
    last_heartbeat TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

INSERT INTO zones (id, realm_id, name, is_static)
VALUES
    (1, 1, 'Starter Forest', true),
    (2, 1, 'Highland Plains', true)
    ON CONFLICT (id) DO NOTHING;

-- 4. Characters (Lobby, World, & Zone Persistence)
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