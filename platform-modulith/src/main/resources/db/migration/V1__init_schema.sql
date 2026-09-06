-- ============================================================================
-- 0. SCHEMA ISOLATION
-- ============================================================================
CREATE SCHEMA IF NOT EXISTS game;
CREATE SCHEMA IF NOT EXISTS player;
CREATE SCHEMA IF NOT EXISTS infra;

-- ============================================================================
-- 1. GAME SCHEMA (Static FFXI World, Zone Geometries, and Transitions)
-- ============================================================================

-- 1.1 Zones (Combined Map Geometry, DAT Asset Container, and Coordinate Bounds)
CREATE TABLE game.zones (
                            id           INT PRIMARY KEY,
                            name         VARCHAR(64) UNIQUE NOT NULL,
                            dat_path     VARCHAR(255)       NOT NULL,
                            is_seamless  BOOLEAN            NOT NULL DEFAULT false,
                            min_x        REAL               NOT NULL DEFAULT -500.0,
                            min_y        REAL               NOT NULL DEFAULT -100.0,
                            min_z        REAL               NOT NULL DEFAULT -500.0,
                            max_x        REAL               NOT NULL DEFAULT 500.0,
                            max_y        REAL               NOT NULL DEFAULT 100.0,
                            max_z        REAL               NOT NULL DEFAULT 500.0
);

INSERT INTO game.zones (id, name, dat_path, is_seamless)
VALUES
    -- San d'Oria (Starter City)
    (230, 'Southern San d''Oria', 'ROM/1/31.DAT', false),
    (231, 'Northern San d''Oria', 'ROM/1/32.DAT', false),
    (232, 'Port San d''Oria',     'ROM/1/33.DAT', false),

    -- Bastok (Starter City)
    (234, 'Bastok Mines',         'ROM/1/34.DAT', false),
    (235, 'Bastok Markets',       'ROM/1/35.DAT', false),
    (236, 'Port Bastok',          'ROM/1/36.DAT', false),
    (237, 'Metalworks',           'ROM/1/37.DAT', false),

    -- Windurst (Starter City)
    (238, 'Windurst Waters',      'ROM/0/78.DAT', false),
    (239, 'Windurst Walls',       'ROM/0/79.DAT', false),
    (240, 'Port Windurst',        'ROM/0/80.DAT', false),
    (241, 'Windurst Woods',       'ROM/0/81.DAT', false)
    ON CONFLICT (id) DO NOTHING;

-- 1.2 Zone Lines (Oriented Bounding Box Trigger Volumes)
CREATE TABLE game.zone_lines (
                                 id                INT PRIMARY KEY,
                                 source_zone_id    INT          NOT NULL REFERENCES game.zones (id) ON DELETE CASCADE,
                                 target_zone_id    INT          NOT NULL REFERENCES game.zones (id) ON DELETE CASCADE,
                                 name              VARCHAR(64)  NOT NULL,
                                 transition_mode   VARCHAR(32)  NOT NULL DEFAULT 'DISCRETE',

    -- OBB Trigger Volume in Source Zone's coordinate system
                                 center_x          REAL         NOT NULL,
                                 center_y          REAL         NOT NULL,
                                 center_z          REAL         NOT NULL,
                                 extent_x          REAL         NOT NULL,
                                 extent_y          REAL         NOT NULL,
                                 extent_z          REAL         NOT NULL,
                                 rotation_yaw      REAL         NOT NULL DEFAULT 0.0,

    -- Target Entry Coordinates in Destination Zone
                                 dest_x            REAL         NOT NULL,
                                 dest_y            REAL         NOT NULL,
                                 dest_z            REAL         NOT NULL,
                                 dest_yaw          REAL         NOT NULL DEFAULT 0.0,
                                 dest_pitch        REAL         NOT NULL DEFAULT 0.0,

    -- Coordinate Deltas for SEAMLESS transitions
                                 offset_dx         REAL         NOT NULL DEFAULT 0.0,
                                 offset_dy         REAL         NOT NULL DEFAULT 0.0,
                                 offset_dz         REAL         NOT NULL DEFAULT 0.0,

                                 fade_duration_ms  INT          NOT NULL DEFAULT 1000,
                                 buffer_margin_m   REAL         NOT NULL DEFAULT 20.0
);

CREATE INDEX idx_zone_lines_source ON game.zone_lines (source_zone_id);

INSERT INTO game.zone_lines (
    id, source_zone_id, target_zone_id, name, transition_mode,
    center_x, center_y, center_z, extent_x, extent_y, extent_z, rotation_yaw,
    dest_x, dest_y, dest_z, dest_yaw, dest_pitch,
    fade_duration_ms
) VALUES
      (1, 230, 231, 'S. San d''Oria to N. San d''Oria Gate', 'DISCRETE',
       80.0, -2.0, 140.0, 8.0, 10.0, 4.0, 0.0,
       80.0, -2.0, -140.0, 0.0, 0.0,
       1000),
      (2, 231, 230, 'N. San d''Oria to S. San d''Oria Gate', 'DISCRETE',
       80.0, -2.0, -145.0, 8.0, 10.0, 4.0, 0.0,
       80.0, -2.0, 135.0, 180.0, 0.0,
       1000),
      (3, 234, 235, 'Bastok Mines to Bastok Markets', 'DISCRETE',
       -20.0, 0.0, -100.0, 6.0, 8.0, 4.0, 90.0,
       -40.0, 0.0, -100.0, 90.0, 0.0,
       1000),
      (4, 235, 234, 'Bastok Markets to Bastok Mines', 'DISCRETE',
       -45.0, 0.0, -100.0, 6.0, 8.0, 4.0, 270.0,
       -15.0, 0.0, -100.0, 270.0, 0.0,
       1000),
      (5, 238, 239, 'Windurst Waters to Windurst Walls', 'DISCRETE',
       -10.0, -5.0, -120.0, 6.0, 8.0, 4.0, 0.0,
       -10.0, -5.0, -150.0, 0.0, 0.0,
       1000),
      (6, 239, 238, 'Windurst Walls to Windurst Waters', 'DISCRETE',
       -10.0, -5.0, -155.0, 6.0, 8.0, 4.0, 180.0,
       -10.0, -5.0, -115.0, 180.0, 0.0,
       1000)
    ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- 2. PLAYER SCHEMA (Live Account, Session & Character State)
-- ============================================================================

-- 2.1 Accounts
CREATE TABLE player.accounts (
                                 id            BIGSERIAL PRIMARY KEY,
                                 username      VARCHAR(64) UNIQUE NOT NULL,
                                 email         VARCHAR(255) UNIQUE NOT NULL,
                                 password_hash VARCHAR(255)       NOT NULL,
                                 status        VARCHAR(32)        NOT NULL DEFAULT 'ACTIVE',
                                 created_at    TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
                                 updated_at    TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_username ON player.accounts (username);
CREATE INDEX idx_accounts_email ON player.accounts (email);

-- 2.2 Active Sessions (Distributed Gateway Session Concurrency Lock)
CREATE TABLE player.active_sessions (
                                        account_id    BIGINT PRIMARY KEY REFERENCES player.accounts (id) ON DELETE CASCADE,
                                        session_id    BIGINT             NOT NULL,
                                        gateway_node  INT                NOT NULL,
                                        character_id  BIGINT,
                                        login_time    TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
                                        last_ping     TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_active_sessions_character ON player.active_sessions (character_id);

-- 2.3 Characters
CREATE TABLE player.characters (
                                   id              BIGSERIAL PRIMARY KEY,
                                   account_id      BIGINT             NOT NULL REFERENCES player.accounts (id) ON DELETE CASCADE,
                                   world_id        INT                NOT NULL,
                                   name            VARCHAR(32) UNIQUE NOT NULL,

    -- FFXI Identity & Appearance
                                   race_id         INT2               NOT NULL DEFAULT 1,
                                   gender          INT2               NOT NULL DEFAULT 0,
                                   face_id         INT2               NOT NULL DEFAULT 1,
                                   hair_id         INT2               NOT NULL DEFAULT 1,
                                   size_id         INT2               NOT NULL DEFAULT 1,
                                   nation_id       INT2               NOT NULL DEFAULT 0,

    -- Active Job Loadout
                                   main_job_id     INT2               NOT NULL DEFAULT 1, -- 1: WAR
                                   main_job_level  INT2               NOT NULL DEFAULT 1,
                                   sub_job_id      INT2               NOT NULL DEFAULT 0, -- 0: None
                                   sub_job_level   INT2               NOT NULL DEFAULT 0,

    -- Active Transform & Zone
                                   zone_id         INT                NOT NULL REFERENCES game.zones (id),
                                   pos_x           REAL               NOT NULL,
                                   pos_y           REAL               NOT NULL,
                                   pos_z           REAL               NOT NULL,
                                   rot_yaw         REAL               NOT NULL DEFAULT 0.0,
                                   rot_pitch       REAL               NOT NULL DEFAULT 0.0,

    -- Homepoint Bind Coordinates (Return point on death)
                                   hp_zone_id      INT                NOT NULL REFERENCES game.zones (id),
                                   hp_pos_x        REAL               NOT NULL,
                                   hp_pos_y        REAL               NOT NULL,
                                   hp_pos_z        REAL               NOT NULL,

                                   last_saved_at   TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_characters_account_id ON player.characters (account_id);
CREATE INDEX idx_characters_world_account ON player.characters (world_id, account_id);

-- 2.4 Character Job Levels & Unlocks
CREATE TABLE player.character_jobs (
                                       character_id BIGINT REFERENCES player.characters (id) ON DELETE CASCADE,
                                       job_id       INT2    NOT NULL,
                                       level        INT2    NOT NULL DEFAULT 1,
                                       exp          INT     NOT NULL DEFAULT 0,
                                       is_unlocked  BOOLEAN NOT NULL DEFAULT false,
                                       PRIMARY KEY (character_id, job_id)
);

CREATE INDEX idx_character_jobs_char ON player.character_jobs (character_id);

SELECT setval('player.accounts_id_seq', (SELECT GREATEST(MAX(id), 100000000) FROM player.accounts));
SELECT setval('player.characters_id_seq', (SELECT GREATEST(MAX(id), 100000000) FROM player.characters));

-- ============================================================================
-- 3. INFRA SCHEMA (Cluster Fleet Topology & K8s Dynamic State)
-- ============================================================================

CREATE TABLE infra.worlds (
                              id           INT PRIMARY KEY,
                              name         VARCHAR(64) UNIQUE NOT NULL,
                              host         VARCHAR(128)       NOT NULL DEFAULT '',
                              port         INT                NOT NULL DEFAULT 9996,
                              status       VARCHAR(32)        NOT NULL DEFAULT 'PENDING',
                              max_capacity INT                NOT NULL DEFAULT 4000,
                              last_updated TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

INSERT INTO infra.worlds (id, name, max_capacity)
VALUES
    (1, 'Asura (Prime)',   4000),
    (2, 'Bahamut (Test)',  4000)
    ON CONFLICT (id) DO NOTHING;

CREATE TABLE infra.zone_instances (
                                      id             SERIAL PRIMARY KEY,
                                      world_id       INT                NOT NULL REFERENCES infra.worlds (id) ON DELETE CASCADE,
                                      zone_id        INT                NOT NULL REFERENCES game.zones (id),
                                      instance_tag   VARCHAR(64)        NOT NULL DEFAULT 'DEFAULT',
                                      host           VARCHAR(128)       NOT NULL DEFAULT '',
                                      port           INT                NOT NULL DEFAULT 9997,
                                      status         VARCHAR(32)        NOT NULL DEFAULT 'PENDING',
                                      is_static      BOOLEAN            NOT NULL DEFAULT true,
                                      last_heartbeat TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

-- Guarantees strictly ONE static instance per (world_id, zone_id),
-- while allowing UNLIMITED dynamic instances (is_static = false)!
CREATE UNIQUE INDEX uq_static_world_zone ON infra.zone_instances (world_id, zone_id)
    WHERE (is_static = true);

CREATE INDEX idx_zone_instances_lookup ON infra.zone_instances (world_id, zone_id, is_static);

-- Seed Starter City Static Shards on World 1 (Asura)
INSERT INTO infra.zone_instances (world_id, zone_id, instance_tag, is_static)
VALUES
    (1, 230, 'DEFAULT', true), -- S. San d'Oria
    (1, 231, 'DEFAULT', true), -- N. San d'Oria
    (1, 234, 'DEFAULT', true), -- Bastok Mines
    (1, 235, 'DEFAULT', true), -- Bastok Markets
    (1, 238, 'DEFAULT', true), -- Windurst Waters
    (1, 239, 'DEFAULT', true)  -- Windurst Walls
    ON CONFLICT DO NOTHING;