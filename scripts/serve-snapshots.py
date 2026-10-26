#!/usr/bin/env python3
"""
Interactive snapshot gallery web server with Table of Contents (ToC),
category pages, and a fully reactive responsive headerbar for C2 / FFXI visual test captures.
Serves on port 8088 (http://0.0.0.0:8088).
"""

import os
import sys
import glob
import mimetypes
import argparse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import unquote, urlparse, parse_qs
from datetime import datetime

DEFAULT_PORT = 8088
SNAPSHOT_DIRS = [
    os.path.abspath("workspace/c2/client-desktop/target/snapshots"),
    os.path.abspath("client-desktop/target/snapshots"),
    os.path.abspath("target/snapshots"),
    os.path.expanduser("~/workspace/c2/client-desktop/target/snapshots"),
    os.path.abspath("../client-desktop/target/snapshots")
]

CATEGORIES = {
    "camera": {
        "title": "Third-Person Camera Behaviors",
        "icon": "🎥",
        "desc": "XIM Polar Orbit Camera behaviors: behind-player default, 360° orbit trajectory, pitch elevation, boom zoom, cardinal chase, and character turning.",
        "pattern": "camera_*.png",
        "subsections": [
            ("Default Behind View", ["camera_default_behind_facing_away.png"]),
            ("360° Polar Orbit (45° Intervals)", [
                "camera_orbit_000_behind.png", "camera_orbit_045_rear_right.png", "camera_orbit_090_right_profile.png",
                "camera_orbit_135_front_right.png", "camera_orbit_180_front.png", "camera_orbit_225_front_left.png",
                "camera_orbit_270_left_profile.png", "camera_orbit_315_rear_left.png"
            ]),
            ("Vertical Pitch Elevation (-15° to +75°)", [
                "camera_pitch_low_hero_neg15.png", "camera_pitch_level_00.png", "camera_pitch_elevated_25.png",
                "camera_pitch_high_tactical_50.png", "camera_pitch_overhead_75.png"
            ]),
            ("Boom Zoom Distances (2.5m, 6.0m, 13.0m)", [
                "camera_zoom_close_2_5m.png", "camera_zoom_standard_6m.png", "camera_zoom_distance_13m.png"
            ]),
            ("Cardinal Chase Following (North -Z, East +X, South +Z, West -X)", [
                "camera_chase_facing_north.png", "camera_chase_facing_east.png",
                "camera_chase_facing_south.png", "camera_chase_facing_west.png"
            ]),
            ("Stationary Camera Character In-Place Turn", [
                "camera_turn_char_000_north.png", "camera_turn_char_045_northeast.png",
                "camera_turn_char_090_east.png", "camera_turn_char_135_southeast.png",
                "camera_turn_char_180_south.png", "camera_turn_char_270_west.png"
            ])
        ]
    },
    "zones": {
        "title": "Zone Environments & Ortho Maps",
        "icon": "🏰",
        "desc": "Retail zone rendering: architectural geometry, water meshes, and top-down orthographic maps across San d'Oria, Bastok, Windurst, Jeuno, Selbina, and Mhaura.",
        "pattern": "zone_*.png",
        "subsections": [
            ("Perspective Overviews & Landmarks", [
                "zone_230_south_san_doria_overview.png", "zone_230_south_san_doria_fountain.png",
                "zone_231_north_san_doria_cathedral.png", "zone_232_port_san_doria_airship.png",
                "zone_233_chateau_d_oraguille.png", "zone_234_bastok_mines_ironworks.png",
                "zone_235_bastok_markets_plaza.png", "zone_236_port_bastok_wharf.png",
                "zone_238_windurst_waters.png", "zone_241_windurst_woods_tree.png",
                "zone_244_upper_jeuno_ramparts.png", "zone_245_lower_jeuno_bazaar.png",
                "zone_248_selbina_windmill_harbor.png", "zone_249_mhaura_port_pier.png",
                "zone_100_west_ronfaure_overlook.png", "zone_102_la_theine_crag_plateau.png"
            ]),
            ("Top-Down Orthographic Maps (North = -Z)", [
                "zone_230_south_san_doria_topdown_ortho.png", "zone_231_north_san_doria_topdown_ortho.png",
                "zone_232_port_san_doria_topdown_ortho.png", "zone_233_chateau_d_oraguille_topdown_ortho.png",
                "zone_234_bastok_mines_topdown_ortho.png", "zone_235_bastok_markets_topdown_ortho.png",
                "zone_236_port_bastok_topdown_ortho.png", "zone_238_windurst_waters_topdown_ortho.png",
                "zone_241_windurst_woods_topdown_ortho.png", "zone_244_upper_jeuno_topdown_ortho.png",
                "zone_245_lower_jeuno_topdown_ortho.png", "zone_248_selbina_topdown_ortho.png",
                "zone_249_mhaura_topdown_ortho.png", "zone_100_west_ronfaure_topdown_ortho.png",
                "zone_102_la_theine_crag_topdown_ortho.png"
            ])
        ]
    },
    "animations": {
        "title": "Character Animations & Blending",
        "icon": "🏃",
        "desc": "Authentic skeleton kinematics: Hume, Elvaan, Tarutaru, Mithra, and Galka animations, locomotion blending, casting sequences, and dual-layer upper/lower body posing.",
        "pattern": "character_*.png",
        "subsections": [
            ("Playable Races Idle Postures", [
                "character_idle_humem.png", "character_idle_humef.png",
                "character_idle_elvaanm.png", "character_idle_elvaanf.png",
                "character_idle_tarum.png", "character_idle_taruf.png",
                "character_idle_mithra.png", "character_idle_galka.png"
            ]),
            ("Locomotion (Walk, Run, Retreat, Strafe)", [
                "character_anim_humem_walk.png", "character_anim_humem_run.png",
                "character_anim_humem_retreat_back.png", "character_anim_humem_strafe_left.png",
                "character_anim_humem_strafe_right.png", "character_anim_humem_jump.png",
                "character_anim_mithra_walk.png", "character_anim_tarum_run.png", "character_anim_galka_run.png"
            ]),
            ("Dual-Layer & Weighted Blending", [
                "character_anim_blend_walk_to_run_50.png", "character_anim_blend_idle_to_combat_50.png",
                "character_anim_layer_run_legs_idle_arms.png", "character_anim_layer_walk_legs_combat_arms.png",
                "character_anim_layer_run_legs_cast_arms.png", "character_anim_layer_combat_stance_cast_arms.png"
            ]),
            ("Combat & Magic Casting", [
                "character_anim_humem_combat_1h.png", "character_anim_humem_combat_2h.png",
                "character_anim_humem_weapon_skill.png", "character_anim_humem_cast_gather.png",
                "character_anim_humem_cast_channel.png", "character_anim_humem_cast_release.png",
                "character_anim_tarum_magic_cast.png", "character_anim_elvaanm_combat.png", "character_anim_mithra_combat.png"
            ]),
            ("Emotes, Items & Reactions", [
                "character_anim_humem_cheer.png", "character_anim_humem_sit_rest.png",
                "character_anim_humem_item_use.png", "character_anim_humem_hit_recoil.png",
                "character_anim_humem_defeat_collapse.png"
            ])
        ]
    },
    "monsters_npcs": {
        "title": "Monsters & Key NPCs",
        "icon": "🐉",
        "desc": "Authentic models decoded from retail DATs: Mandragora, Goblin, Chocobo, Moogle, Lion, Cid, Town Guards, and Hume Children.",
        "pattern": "*monster_*.png,*npc_*.png",
        "subsections": [
            ("Field & Dungeon Monsters", [
                "monster_mandragora.png", "monster_goblin.png", "monster_chocobo.png",
                "monster_rabbit.png", "monster_crawler.png", "monster_skeleton.png",
                "monster_sheep.png", "monster_cactuar.png"
            ]),
            ("Key Story NPCs & Townsfolk", [
                "npc_moogle.png", "npc_lion.png", "npc_cid.png",
                "npc_town_guard.png", "npc_child_humem.png", "npc_child_humef.png"
            ])
        ]
    },
    "ui": {
        "title": "UI Systems & Font Moji",
        "icon": "💬",
        "desc": "Retail UI mockups: Shift-JIS / ASCII FontMojiRenderer with 2x color modulation, retail window frames, radar compass, status icons, and elemental wheel.",
        "pattern": "ui_*.png",
        "subsections": [
            ("Retail UI Framework", [
                "ui_dialog_window_mockup.png", "ui_menu_frame_styles.png",
                "ui_menu_system_elements.png", "ui_status_and_action_icons.png",
                "ui_elemental_icons.png", "ui_radar_compass.png"
            ])
        ]
    }
}

def find_snapshots_dir():
    for d in SNAPSHOT_DIRS:
        if os.path.isdir(d):
            return d
    d = os.path.abspath("client-desktop/target/snapshots")
    os.makedirs(d, exist_ok=True)
    return d

BASE_PAGE_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
    <title>{page_title} - C2 Visual Engine Gallery</title>
    <style>
        :root {{
            --bg: #090d14;
            --surface: #111824;
            --surface-card: #151f2e;
            --surface-hover: #1c2b3f;
            --border: #223247;
            --border-hover: #3b82f6;
            --accent: #38bdf8;
            --accent-glow: rgba(56, 189, 248, 0.2);
            --gold: #fbbf24;
            --green: #34d399;
            --text-main: #f8fafc;
            --text-dim: #94a3b8;
            --text-dark: #64748b;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            background-color: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            overflow-x: hidden;
        }}

        /* Reactive Sticky Header */
        header.app-header {{
            position: sticky;
            top: 0;
            z-index: 1000;
            background: rgba(13, 19, 30, 0.94);
            backdrop-filter: blur(16px);
            -webkit-backdrop-filter: blur(16px);
            border-bottom: 1px solid var(--border);
            width: 100%;
        }}
        .header-inner {{
            max-width: 1680px;
            margin: 0 auto;
            padding: 10px 20px 8px;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }}
        .top-row {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 12px;
            flex-wrap: wrap;
        }}
        .brand-link {{
            display: flex;
            align-items: center;
            gap: 10px;
            text-decoration: none;
            color: #fff;
            font-weight: 700;
            font-size: 1.15rem;
            letter-spacing: -0.3px;
        }}
        .brand-icon {{
            font-size: 1.3rem;
            filter: drop-shadow(0 0 8px rgba(56, 189, 248, 0.4));
        }}
        .brand-tag {{
            font-size: 0.68rem;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.6px;
            padding: 3px 8px;
            border-radius: 999px;
            background: var(--accent-glow);
            color: var(--accent);
            border: 1px solid rgba(56, 189, 248, 0.4);
        }}
        .top-actions {{
            display: flex;
            align-items: center;
            gap: 10px;
            flex-wrap: wrap;
        }}
        .coord-indicator {{
            display: inline-flex;
            align-items: center;
            gap: 8px;
            background: rgba(251, 191, 36, 0.08);
            border: 1px solid rgba(251, 191, 36, 0.25);
            border-radius: 6px;
            padding: 5px 10px;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 0.74rem;
            color: var(--gold);
        }}
        .coord-indicator span b {{
            color: #fff;
        }}
        .refresh-button {{
            background: var(--surface-card);
            border: 1px solid var(--border);
            color: var(--text-main);
            padding: 6px 13px;
            border-radius: 6px;
            font-size: 0.8rem;
            font-weight: 600;
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            transition: all 0.15s ease;
        }}
        .refresh-button:hover {{
            background: var(--surface-hover);
            border-color: var(--accent);
            color: var(--accent);
        }}

        /* Responsive Chip Navigation Bar */
        .nav-chip-bar {{
            display: flex;
            align-items: center;
            gap: 8px;
            overflow-x: auto;
            white-space: nowrap;
            padding-bottom: 2px;
            -webkit-overflow-scrolling: touch;
            scrollbar-width: none;
        }}
        .nav-chip-bar::-webkit-scrollbar {{
            display: none;
        }}
        .chip {{
            display: inline-flex;
            align-items: center;
            gap: 7px;
            text-decoration: none;
            padding: 6px 14px;
            border-radius: 999px;
            font-size: 0.84rem;
            font-weight: 500;
            color: var(--text-dim);
            background: var(--surface);
            border: 1px solid var(--border);
            flex-shrink: 0;
            transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
        }}
        .chip:hover {{
            color: #fff;
            background: var(--surface-hover);
            border-color: rgba(56, 189, 248, 0.5);
            transform: translateY(-1px);
        }}
        .chip.active {{
            background: rgba(56, 189, 248, 0.15);
            border-color: var(--accent);
            color: #fff;
            font-weight: 600;
            box-shadow: 0 0 14px var(--accent-glow);
        }}
        .chip-badge {{
            font-size: 0.72rem;
            padding: 2px 7px;
            border-radius: 999px;
            background: rgba(255, 255, 255, 0.08);
            color: var(--text-dim);
            font-weight: 600;
        }}
        .chip.active .chip-badge {{
            background: var(--accent);
            color: #040810;
        }}

        /* Main Content Container */
        main {{
            flex: 1;
            max-width: 1680px;
            width: 100%;
            margin: 0 auto;
            padding: 28px 20px 60px;
        }}
        .page-header {{
            margin-bottom: 26px;
            padding-bottom: 18px;
            border-bottom: 1px solid var(--border);
            display: flex;
            justify-content: space-between;
            align-items: flex-end;
            gap: 16px;
            flex-wrap: wrap;
        }}
        .page-header h1 {{
            font-size: 1.65rem;
            font-weight: 700;
            display: flex;
            align-items: center;
            gap: 10px;
        }}
        .page-header p {{
            color: var(--text-dim);
            font-size: 0.92rem;
            margin-top: 6px;
            max-width: 880px;
            line-height: 1.5;
        }}

        /* Table of Contents Category Cards */
        .toc-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(440px, 1fr));
            gap: 22px;
            margin-top: 8px;
        }}
        .toc-card {{
            background: var(--surface-card);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 22px;
            display: flex;
            flex-direction: column;
            gap: 14px;
            text-decoration: none;
            color: inherit;
            transition: transform 0.2s, border-color 0.2s, box-shadow 0.2s;
        }}
        .toc-card:hover {{
            transform: translateY(-3px);
            border-color: var(--accent);
            box-shadow: 0 12px 30px rgba(0, 0, 0, 0.6);
        }}
        .toc-card-header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
        }}
        .toc-card-title {{
            font-size: 1.2rem;
            font-weight: 700;
            display: flex;
            align-items: center;
            gap: 9px;
            color: #fff;
        }}
        .toc-card-badge {{
            font-size: 0.75rem;
            background: var(--surface);
            border: 1px solid var(--border);
            padding: 4px 10px;
            border-radius: 999px;
            color: var(--accent);
            font-weight: 600;
        }}
        .toc-card-desc {{
            color: var(--text-dim);
            font-size: 0.88rem;
            line-height: 1.5;
        }}
        .toc-previews {{
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 8px;
        }}
        .toc-thumb {{
            aspect-ratio: 16/9;
            background: #000;
            border-radius: 6px;
            overflow: hidden;
            border: 1px solid var(--border);
        }}
        .toc-thumb img {{
            width: 100%;
            height: 100%;
            object-fit: cover;
        }}
        .toc-card-footer {{
            display: flex;
            justify-content: flex-end;
            align-items: center;
            gap: 6px;
            color: var(--accent);
            font-size: 0.84rem;
            font-weight: 600;
        }}

        /* Section Title & Snapshot Grid */
        .section-heading {{
            font-size: 1.2rem;
            font-weight: 700;
            margin: 34px 0 16px;
            display: flex;
            align-items: center;
            gap: 10px;
            color: var(--gold);
            padding-bottom: 8px;
            border-bottom: 1px solid rgba(251, 191, 36, 0.18);
        }}
        .section-heading:first-of-type {{
            margin-top: 0;
        }}
        .gallery-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
            gap: 18px;
        }}
        .snap-card {{
            background: var(--surface-card);
            border: 1px solid var(--border);
            border-radius: 10px;
            overflow: hidden;
            display: flex;
            flex-direction: column;
            transition: transform 0.2s, border-color 0.2s, box-shadow 0.2s;
        }}
        .snap-card:hover {{
            transform: translateY(-2px);
            border-color: var(--accent);
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
        }}
        .img-wrap {{
            width: 100%;
            height: 230px;
            background: #05080e;
            display: flex;
            align-items: center;
            justify-content: center;
            overflow: hidden;
            cursor: pointer;
            position: relative;
        }}
        .img-wrap img {{
            max-width: 100%;
            max-height: 100%;
            object-fit: contain;
        }}
        .snap-card-body {{
            padding: 13px 15px;
            display: flex;
            flex-direction: column;
            gap: 6px;
        }}
        .snap-filename {{
            font-weight: 600;
            font-size: 0.9rem;
            word-break: break-all;
            color: #fff;
        }}
        .snap-meta {{
            display: flex;
            justify-content: space-between;
            font-size: 0.77rem;
            color: var(--text-dim);
        }}
        .snap-actions {{
            margin-top: 5px;
            display: flex;
            gap: 10px;
        }}
        .action-btn {{
            font-size: 0.78rem;
            color: var(--accent);
            text-decoration: none;
            font-weight: 500;
        }}
        .action-btn:hover {{
            text-decoration: underline;
        }}

        /* Lightbox modal */
        #lightbox {{
            display: none;
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(3, 6, 11, 0.96);
            z-index: 2000;
            justify-content: center;
            align-items: center;
            padding: 20px;
        }}
        #lightbox-inner {{
            position: relative;
            max-width: 95vw;
            max-height: 95vh;
            display: flex;
            flex-direction: column;
            align-items: center;
        }}
        #lightbox img {{
            max-width: 95vw;
            max-height: 86vh;
            object-fit: contain;
            border-radius: 8px;
            box-shadow: 0 0 50px rgba(0, 0, 0, 0.9);
            border: 1px solid var(--border);
        }}
        #lightbox-bar {{
            margin-top: 12px;
            display: flex;
            gap: 18px;
            align-items: center;
            font-size: 0.92rem;
            color: #fff;
            font-weight: 600;
            flex-wrap: wrap;
            justify-content: center;
        }}
        #lightbox-close {{
            position: absolute;
            top: -42px;
            right: 0;
            color: #fff;
            font-size: 2rem;
            cursor: pointer;
            background: none;
            border: none;
            line-height: 1;
        }}

        /* Responsive Media Queries */
        @media (max-width: 900px) {{
            .top-row {{
                flex-direction: column;
                align-items: flex-start;
                gap: 8px;
            }}
            .top-actions {{
                width: 100%;
                justify-content: space-between;
            }}
            .toc-grid {{
                grid-template-columns: 1fr;
            }}
            .gallery-grid {{
                grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
            }}
        }}
        @media (max-width: 600px) {{
            header.app-header .header-inner {{
                padding: 8px 12px 6px;
            }}
            .brand-name {{
                font-size: 1rem;
            }}
            .coord-indicator {{
                font-size: 0.68rem;
                padding: 4px 6px;
            }}
            .chip {{
                font-size: 0.78rem;
                padding: 5px 11px;
            }}
            main {{
                padding: 16px 12px 40px;
            }}
        }}
    </style>
</head>
<body>
    <header class="app-header">
        <div class="header-inner">
            <div class="top-row">
                <a class="brand-link" href="/">
                    <span class="brand-icon">⚔️</span>
                    <span class="brand-name">C2 FFXI Visual Engine</span>
                    <span class="brand-tag">XIM Authentic</span>
                </a>
                <div class="top-actions">
                    <div class="coord-indicator" title="Retail FFXI Coordinate Kinematics: North is -Z, East is +X, South is +Z, West is -X, Up is +Y">
                        <span><b>N:</b> -Z</span>
                        <span><b>E:</b> +X</span>
                        <span><b>S:</b> +Z</span>
                        <span><b>W:</b> -X</span>
                        <span><b>Up:</b> +Y</span>
                    </div>
                    <button class="refresh-button" onclick="location.reload()" title="Reload captures">↻ Refresh</button>
                </div>
            </div>
            <nav class="nav-chip-bar" aria-label="Category Navigation">
                <a class="chip {active_toc}" href="/">
                    <span>📖</span>
                    <span>Table of Contents</span>
                </a>
                <a class="chip {active_camera}" href="/?cat=camera">
                    <span>🎥</span>
                    <span>Camera</span>
                    <span class="chip-badge">{count_camera}</span>
                </a>
                <a class="chip {active_zones}" href="/?cat=zones">
                    <span>🏰</span>
                    <span>Zones & Ortho</span>
                    <span class="chip-badge">{count_zones}</span>
                </a>
                <a class="chip {active_animations}" href="/?cat=animations">
                    <span>🏃</span>
                    <span>Animations</span>
                    <span class="chip-badge">{count_animations}</span>
                </a>
                <a class="chip {active_monsters_npcs}" href="/?cat=monsters_npcs">
                    <span>🐉</span>
                    <span>Monsters & NPCs</span>
                    <span class="chip-badge">{count_monsters_npcs}</span>
                </a>
                <a class="chip {active_ui}" href="/?cat=ui">
                    <span>💬</span>
                    <span>UI Systems</span>
                    <span class="chip-badge">{count_ui}</span>
                </a>
                <a class="chip {active_all}" href="/?cat=all">
                    <span>📁</span>
                    <span>All Captures</span>
                    <span class="chip-badge">{count_all}</span>
                </a>
            </nav>
        </div>
    </header>

    <main>
        {page_content}
    </main>

    <div id="lightbox" onclick="closeLightbox(event)">
        <div id="lightbox-inner" onclick="event.stopPropagation()">
            <button id="lightbox-close" onclick="closeLightbox()">&times;</button>
            <img id="lightbox-img" src="" alt="Full Snapshot View">
            <div id="lightbox-bar">
                <span id="lightbox-filename"></span>
                <a id="lightbox-open" class="action-btn" href="" target="_blank">Open Full Resolution</a>
                <a id="lightbox-download" class="action-btn" href="" download>Download</a>
            </div>
        </div>
    </div>

    <script>
        function openLightbox(src, name) {{
            document.getElementById('lightbox-img').src = src;
            document.getElementById('lightbox-filename').textContent = name;
            document.getElementById('lightbox-open').href = src;
            document.getElementById('lightbox-download').href = src;
            document.getElementById('lightbox').style.display = 'flex';
        }}
        function closeLightbox() {{
            document.getElementById('lightbox').style.display = 'none';
        }}
        document.addEventListener('keydown', (e) => {{
            if (e.key === 'Escape') closeLightbox();
        }});
    </script>
</body>
</html>
"""

class SnapshotHandler(BaseHTTPRequestHandler):
    snapshots_dir = ""

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        qs = parse_qs(parsed.query)

        if path in ("/", "/index.html", "/toc"):
            cat = qs.get("cat", [""])[0].lower()
            if not cat or cat == "toc":
                self.serve_toc()
            elif cat in CATEGORIES:
                self.serve_category(cat)
            else:
                self.serve_all_gallery()
        elif path.startswith("/snapshots/"):
            filename = os.path.basename(path)
            self.serve_file(filename)
        else:
            self.send_error(404, "Not Found")

    def get_all_snapshots(self):
        files = []
        for ext in ("*.png", "*.jpg", "*.jpeg", "*.webp", "*.glb"):
            files.extend(glob.glob(os.path.join(self.snapshots_dir, ext)))
        files.sort(key=lambda p: os.path.getmtime(p), reverse=True)
        return files

    def count_category(self, cat_key, all_files_set):
        cat = CATEGORIES[cat_key]
        count = 0
        for _, file_list in cat["subsections"]:
            for fname in file_list:
                if fname in all_files_set:
                    count += 1
        return count

    def get_counts(self, all_files_set):
        counts = {}
        for key in CATEGORIES:
            counts[key] = self.count_category(key, all_files_set)
        counts["all"] = len(all_files_set)
        return counts

    def serve_toc(self):
        all_files = {os.path.basename(p): p for p in self.get_all_snapshots()}
        counts = self.get_counts(all_files)

        cards_html = []
        for key, cat in CATEGORIES.items():
            cat_files = []
            for _, file_list in cat["subsections"]:
                for fname in file_list:
                    if fname in all_files:
                        cat_files.append(fname)

            preview_thumbs = []
            for fname in cat_files[:4]:
                preview_thumbs.append(f'<div class="toc-thumb"><img src="/snapshots/{fname}" alt="{fname}" loading="lazy"></div>')
            while len(preview_thumbs) < 4 and cat_files:
                preview_thumbs.append(f'<div class="toc-thumb"><img src="/snapshots/{cat_files[0]}" alt="preview" loading="lazy"></div>')

            thumbs_html = "\n".join(preview_thumbs)
            cards_html.append(f"""
            <a class="toc-card" href="/?cat={key}">
                <div class="toc-card-header">
                    <div class="toc-card-title"><span>{cat['icon']}</span> {cat['title']}</div>
                    <span class="toc-card-badge">{len(cat_files)} Captures</span>
                </div>
                <div class="toc-card-desc">{cat['desc']}</div>
                <div class="toc-previews">
                    {thumbs_html}
                </div>
                <div class="toc-card-footer">
                    <span>View Category &rarr;</span>
                </div>
            </a>
            """)

        content = f"""
        <div class="page-header">
            <div>
                <h1><span>📖</span> Visual Scene Gallery — Table of Contents</h1>
                <p>
                    Interactive test gallery for the C2 / FFXI rendering pipeline.
                    Select a category below or use the navigation chips above to inspect third-person camera polar orbits,
                    top-down ortho maps, character animation blending, monsters, and UI systems.
                </p>
            </div>
        </div>
        <div class="toc-grid">
            {''.join(cards_html)}
        </div>
        """

        html = BASE_PAGE_TEMPLATE.format(
            page_title="Table of Contents",
            active_toc="active",
            active_camera="",
            active_zones="",
            active_animations="",
            active_monsters_npcs="",
            active_ui="",
            active_all="",
            count_camera=counts["camera"],
            count_zones=counts["zones"],
            count_animations=counts["animations"],
            count_monsters_npcs=counts["monsters_npcs"],
            count_ui=counts["ui"],
            count_all=counts["all"],
            page_content=content
        )
        self.send_html(html)

    def serve_category(self, cat_key):
        cat = CATEGORIES[cat_key]
        all_files = {os.path.basename(p): p for p in self.get_all_snapshots()}
        counts = self.get_counts(all_files)

        sections_html = []
        for sub_name, file_list in cat["subsections"]:
            cards = []
            for fname in file_list:
                if fname in all_files:
                    cards.append(self.render_card(fname, all_files[fname]))

            if cards:
                sections_html.append(f'<div class="section-heading"><span>❖</span> {sub_name} ({len(cards)})</div>')
                sections_html.append(f'<div class="gallery-grid">{"".join(cards)}</div>')

        content = f"""
        <div class="page-header">
            <div>
                <h1><span>{cat['icon']}</span> {cat['title']}</h1>
                <p>{cat['desc']}</p>
            </div>
            <div>
                <a class="refresh-button" href="/" style="text-decoration:none;">&larr; Back to ToC</a>
            </div>
        </div>
        {''.join(sections_html)}
        """

        html = BASE_PAGE_TEMPLATE.format(
            page_title=cat['title'],
            active_toc="",
            active_camera="active" if cat_key == "camera" else "",
            active_zones="active" if cat_key == "zones" else "",
            active_animations="active" if cat_key == "animations" else "",
            active_monsters_npcs="active" if cat_key == "monsters_npcs" else "",
            active_ui="active" if cat_key == "ui" else "",
            active_all="",
            count_camera=counts["camera"],
            count_zones=counts["zones"],
            count_animations=counts["animations"],
            count_monsters_npcs=counts["monsters_npcs"],
            count_ui=counts["ui"],
            count_all=counts["all"],
            page_content=content
        )
        self.send_html(html)

    def serve_all_gallery(self):
        files = self.get_all_snapshots()
        all_files = {os.path.basename(p): p for p in files}
        counts = self.get_counts(all_files)
        cards = [self.render_card(os.path.basename(p), p) for p in files]

        content = f"""
        <div class="page-header">
            <div>
                <h1><span>📁</span> All Captured Snapshots ({len(files)})</h1>
                <p>Complete directory listing of all {len(files)} generated test captures from <code>{self.snapshots_dir}</code>.</p>
            </div>
            <div>
                <a class="refresh-button" href="/" style="text-decoration:none;">&larr; Back to ToC</a>
            </div>
        </div>
        <div class="gallery-grid">
            {''.join(cards)}
        </div>
        """

        html = BASE_PAGE_TEMPLATE.format(
            page_title="All Snapshots",
            active_toc="",
            active_camera="",
            active_zones="",
            active_animations="",
            active_monsters_npcs="",
            active_ui="",
            active_all="active",
            count_camera=counts["camera"],
            count_zones=counts["zones"],
            count_animations=counts["animations"],
            count_monsters_npcs=counts["monsters_npcs"],
            count_ui=counts["ui"],
            count_all=counts["all"],
            page_content=content
        )
        self.send_html(html)

    def render_card(self, fname, file_path):
        sz = os.path.getsize(file_path)
        mtime = datetime.fromtimestamp(os.path.getmtime(file_path)).strftime("%Y-%m-%d %H:%M:%S")
        size_str = f"{sz / 1024:.1f} KB" if sz < 1024 * 1024 else f"{sz / (1024 * 1024):.2f} MB"

        if fname.lower().endswith(".glb"):
            return f"""
            <div class="snap-card">
                <div class="img-wrap" style="background:#0c121d;color:#38bdf8;font-size:2.2rem;">
                    <span>📦 3D GLB</span>
                </div>
                <div class="snap-card-body">
                    <div class="snap-filename">{fname}</div>
                    <div class="snap-meta"><span>{size_str}</span><span>{mtime}</span></div>
                    <div class="snap-actions">
                        <a class="action-btn" href="/snapshots/{fname}" download>Download GLB Model</a>
                    </div>
                </div>
            </div>
            """

        return f"""
        <div class="snap-card">
            <div class="img-wrap" onclick="openLightbox('/snapshots/{fname}', '{fname}')">
                <img src="/snapshots/{fname}" alt="{fname}" loading="lazy">
            </div>
            <div class="snap-card-body">
                <div class="snap-filename">{fname}</div>
                <div class="snap-meta"><span>{size_str}</span><span>{mtime}</span></div>
                <div class="snap-actions">
                    <a class="action-btn" href="/snapshots/{fname}" target="_blank">Full Res</a>
                    &bull;
                    <a class="action-btn" href="/snapshots/{fname}" download>Download</a>
                </div>
            </div>
        </div>
        """

    def serve_file(self, filename):
        safe_path = os.path.abspath(os.path.join(self.snapshots_dir, filename))
        if not safe_path.startswith(self.snapshots_dir) or not os.path.isfile(safe_path):
            self.send_error(404, "File Not Found")
            return

        mime_type, _ = mimetypes.guess_type(safe_path)
        if filename.lower().endswith(".glb"):
            mime_type = "model/gltf-binary"
        elif not mime_type:
            mime_type = "application/octet-stream"

        sz = os.path.getsize(safe_path)
        self.send_response(200)
        self.send_header("Content-Type", mime_type)
        self.send_header("Content-Length", str(sz))
        self.send_header("Cache-Control", "public, max-age=60")
        self.end_headers()

        try:
            with open(safe_path, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_html(self, html):
        data = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.end_headers()
        self.wfile.write(data)

def run_server(port, directory):
    SnapshotHandler.snapshots_dir = os.path.abspath(directory)
    server_address = ("0.0.0.0", port)
    httpd = ThreadingHTTPServer(server_address, SnapshotHandler)
    print(f"[SNAPSHOT-SERVER] Serving '{SnapshotHandler.snapshots_dir}' on http://0.0.0.0:{port} ...", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[SNAPSHOT-SERVER] Shutting down server...", flush=True)
        httpd.server_close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Categorized Snapshot Gallery Server with ToC.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to listen on (default: 8088)")
    parser.add_argument("--dir", default=None, help="Directory to serve snapshots from")
    args = parser.parse_args()

    target_dir = args.dir if args.dir else find_snapshots_dir()
    run_server(args.port, target_dir)
