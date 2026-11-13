#!/usr/bin/env python3
import os
import glob
import yaml

BASE_DIR = '/home/samuel_l_hyman/workspace/c2/game-asset-pipeline/src/test/resources/dats/tables/landsandboat/zones'
OUTPUT_SQL = '/home/samuel_l_hyman/c2/platform-modulith/src/main/resources/db/migration/V2__npc_list.sql'

def escape_sql(val):
    if val is None:
        return "''"
    s = str(val)
    return "'" + s.replace("'", "''") + "'"

def main():
    print(f"Reading LSB zones from {BASE_DIR}...")
    zone_dirs = sorted([d for d in os.listdir(BASE_DIR) if os.path.isdir(os.path.join(BASE_DIR, d))])

    # Extra zones to ensure exist in game.zones
    supplementary_zones = [
        (100, "West Ronfaure", "ROM/1/30.DAT"),
        (102, "La Theine Plateau", "ROM/1/2.DAT"),
        (233, "Chateau d'Oraguille", "ROM/1/38.DAT"),
        (244, "Upper Jeuno", "ROM/1/40.DAT"),
        (245, "Lower Jeuno", "ROM/1/41.DAT"),
        (248, "Selbina", "ROM/1/44.DAT"),
        (249, "Mhaura", "ROM/1/45.DAT")
    ]

    all_rows = []

    for z_name in zone_dirs:
        z_path = os.path.join(BASE_DIR, z_name)
        npc_file = os.path.join(z_path, 'npcs.yaml')
        if not os.path.isfile(npc_file):
            continue

        with open(npc_file, 'r', encoding='utf-8') as f:
            data = yaml.safe_load(f)
        npcs = data.get('npcs', {}) if data else {}

        for nid_raw, n in npcs.items():
            nid = int(nid_raw)
            zone_id = (nid >> 12) & 0xFFF
            name = n.get('display_name') or n.get('script') or f"NPC_{nid}"

            at = n.get('at')
            if at is not None:
                pos_x = float(at[0])
                pos_y = float(at[1])
                pos_z = float(at[2])
                pos_rot = int(at[3]) if len(at) >= 4 else 0
            else:
                pos_x = 0.0
                pos_y = 0.0
                pos_z = 0.0
                pos_rot = 0

            status = n.get('status') or ('cutscene_only' if at is None else 'normal')
            widescan = bool(n.get('widescan', False))

            render = n.get('render') or {}
            entity_flags = int(render.get('entity_flags', 0))
            animation_sub = int(render.get('animation_sub', 0))
            name_prefix = int(render.get('name_prefix', 0))
            moving = int(render.get('moving', 0))

            look = render.get('look') or {}
            if not isinstance(look, dict):
                look = {}

            look_type = look.get('type')
            race_id = int(look.get('race', 0))
            face_id = int(look.get('face', 0))
            head = int(look.get('head', 0))
            body = int(look.get('body', 0))
            hands = int(look.get('hands', 0))
            legs = int(look.get('legs', 0))
            feet = int(look.get('feet', 0))
            main_weapon = int(look.get('main', 0))
            sub_shield = int(look.get('sub', 0))
            ranged = int(look.get('ranged', 0))
            model_id = int(look.get('model', 0))

            all_rows.append((
                nid,
                name,
                pos_x,
                pos_y,
                pos_z,
                pos_rot,
                zone_id,
                status,
                entity_flags,
                animation_sub,
                name_prefix,
                moving,
                look_type,
                race_id,
                face_id,
                head,
                body,
                hands,
                legs,
                feet,
                main_weapon,
                sub_shield,
                ranged,
                model_id,
                widescan
            ))

    print(f"Collected {len(all_rows)} total authentic NPCs across all zones.")

    os.makedirs(os.path.dirname(OUTPUT_SQL), exist_ok=True)
    with open(OUTPUT_SQL, 'w', encoding='utf-8') as out:
        out.write("-- ============================================================================\n")
        out.write("-- V2: AUTHENTIC LANDSANDBOAT NPC LIST & ZONE REFINEMENT\n")
        out.write("-- ============================================================================\n\n")

        out.write("-- 1. Supplementary Zone Definitions\n")
        out.write("INSERT INTO game.zones (id, name, dat_path, is_seamless)\nVALUES\n")
        zone_val_lines = []
        for zid, zname, zdat in supplementary_zones:
            escaped_name = zname.replace("'", "''")
            zone_val_lines.append(f"    ({zid}, '{escaped_name}', '{zdat}', false)")
        out.write(",\n".join(zone_val_lines))
        out.write("\nON CONFLICT (id) DO NOTHING;\n\n")

        out.write("-- 2. NPC Master Table (1:1 with authentic LandSandBoat schema & spawn state)\n")
        out.write("CREATE TABLE game.npc_list (\n")
        out.write("    id            BIGINT PRIMARY KEY,\n")
        out.write("    name          VARCHAR(64) NOT NULL,\n")
        out.write("    pos_x         REAL NOT NULL DEFAULT 0.0,\n")
        out.write("    pos_y         REAL NOT NULL DEFAULT 0.0,\n")
        out.write("    pos_z         REAL NOT NULL DEFAULT 0.0,\n")
        out.write("    pos_rot       SMALLINT NOT NULL DEFAULT 0,\n")
        out.write("    zone_id       INT NOT NULL REFERENCES game.zones (id) ON DELETE CASCADE,\n")
        out.write("    status        VARCHAR(32) NOT NULL DEFAULT 'normal',\n")
        out.write("    entity_flags  INT NOT NULL DEFAULT 0,\n")
        out.write("    animation_sub SMALLINT NOT NULL DEFAULT 0,\n")
        out.write("    name_prefix   SMALLINT NOT NULL DEFAULT 0,\n")
        out.write("    moving        SMALLINT NOT NULL DEFAULT 0,\n")
        out.write("    look_type     VARCHAR(32),\n")
        out.write("    race_id       SMALLINT NOT NULL DEFAULT 0,\n")
        out.write("    face_id       SMALLINT NOT NULL DEFAULT 0,\n")
        out.write("    head          INT NOT NULL DEFAULT 0,\n")
        out.write("    body          INT NOT NULL DEFAULT 0,\n")
        out.write("    hands         INT NOT NULL DEFAULT 0,\n")
        out.write("    legs          INT NOT NULL DEFAULT 0,\n")
        out.write("    feet          INT NOT NULL DEFAULT 0,\n")
        out.write("    main_weapon   INT NOT NULL DEFAULT 0,\n")
        out.write("    sub_shield    INT NOT NULL DEFAULT 0,\n")
        out.write("    ranged        INT NOT NULL DEFAULT 0,\n")
        out.write("    model_id      INT NOT NULL DEFAULT 0,\n")
        out.write("    widescan      BOOLEAN NOT NULL DEFAULT false\n")
        out.write(");\n\n")

        out.write("CREATE INDEX idx_npc_list_zone_id ON game.npc_list(zone_id);\n\n")

        out.write("-- 3. Seed Canonical NPC Records\n")
        batch_size = 500
        for i in range(0, len(all_rows), batch_size):
            batch = all_rows[i:i + batch_size]
            out.write("INSERT INTO game.npc_list (\n")
            out.write("    id, name, pos_x, pos_y, pos_z, pos_rot, zone_id, status,\n")
            out.write("    entity_flags, animation_sub, name_prefix, moving, look_type,\n")
            out.write("    race_id, face_id, head, body, hands, legs, feet, main_weapon, sub_shield, ranged, model_id, widescreen_or_widescan\n" if False else "    race_id, face_id, head, body, hands, legs, feet, main_weapon, sub_shield, ranged, model_id, widescan\n")
            out.write(") VALUES\n")

            row_strs = []
            for r in batch:
                look_t_sql = escape_sql(r[12]) if r[12] is not None else "NULL"
                widescan_sql = "true" if r[24] else "false"
                row_strs.append(
                    f"    ({r[0]}, {escape_sql(r[1])}, {r[2]:.3f}, {r[3]:.3f}, {r[4]:.3f}, {r[5]}, {r[6]}, {escape_sql(r[7])}, "
                    f"{r[8]}, {r[9]}, {r[10]}, {r[11]}, {look_t_sql}, {r[13]}, {r[14]}, "
                    f"{r[15]}, {r[16]}, {r[17]}, {r[18]}, {r[19]}, {r[20]}, {r[21]}, {r[22]}, {r[23]}, {widescan_sql})"
                )
            out.write(",\n".join(row_strs))
            out.write(";\n\n")

    print(f"Successfully generated {OUTPUT_SQL} with {len(all_rows)} NPC records.")

if __name__ == '__main__':
    main()
