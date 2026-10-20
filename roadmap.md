# Project C2 Development Roadmap

This roadmap outlines the milestones for harvesting and porting the FFXI game asset pipeline, world geometry, collision systems, rendering engine, and entity simulation into `c2` (Java 25, Micronaut Modulith, LWJGL 3, Dear ImGui, Netty QUIC).

---

## Milestone Overview

| Milestone | Title | Scope & Deliverables | Status | Primary Modules |
|---|---|---|---|---|
| **Milestone 1** | Asset Foundation & Collision Mesh | DAT file decoding, bit-rotation decryption (`0x1B`), spatial collision grid, 3D math primitives, Khronos-validated binary `.glb` export, offline 3D collision exploration demo in `client-desktop` | **COMPLETED** ([`058a2e4`](https://github.com/TheStackTraceWhisperer/c2/commit/058a2e4), [`2689b45`](https://github.com/TheStackTraceWhisperer/c2/commit/2689b45)) | `game-asset-pipeline`, `client-desktop` |
| **Milestone 2** | Authoritative Server Collision & Kinematics | `ZoneCollisionService` with authoritative zone mesh loading, floor clamping, slope/stair validation, floor penetration prevention, anti-flyhack validation, horizontal wall collision checks in `ZoneSimulationEngine` | **COMPLETED** ([`8897ea7`](https://github.com/TheStackTraceWhisperer/c2/commit/8897ea7)) | `platform-modulith`, `game-asset-pipeline` |
| **Milestone 3** | Visual Zone Geometry & Textures | `S20_TEXTURE` parser (DXT1, DXT3, DXT5, 8-bit paletted textures), `S2E_ZONE_MESH` two-pass decryption & parser (interleaved vertices, normals, UVs, vertex colors, material assignments), `VisualZoneScene` / `VisualZoneLoader`, high-performance textured batch renderer `VisualZoneRenderer` with alpha discard & material sorting in `client-desktop` | **COMPLETED** | `game-asset-pipeline`, `client-desktop` |
| **Milestone 4** | Character & Town NPC Visuals & Animation | `Quaternion` (SLERP/NLERP), `Joint` / `Skeleton` (`S29_SKELETON` forward kinematics), `SkinnedMesh` (`S2A`/`S25` dual-bone weights), `SkeletonAnimation` (`S2B_SKELETON_ANIMATION` keyframed tracks), `RomAssetLoader` (on-demand ROM fetch & disk cache), `RaceModelLoader` (authentic retail models & textures for Hume M/F, Elvaan M/F, TaruTaru M/F, Mithra, Galka, Town NPCs), `CompositeCharacterModel` (multi-slot equipment compositing with authentic DAT textures), `CharacterRenderer` (128 bones uniform, GPU linear blend skinning, texture batching, alpha discarding), `NpcTable` (31,470 authentic NPCs across 279 zones), town NPC visual markers & proximity HUD in `OfflineWorldState` | **COMPLETED** | `game-asset-pipeline`, `client-desktop` |
| **Milestone 5** | Entity Simulation, Interpolation & Polish | Remote entity 20 Hz to 60+ FPS visual interpolation buffer, animation state machine transitions (idle, run, combat), basic server-side NPC patrolling & line-of-sight aggro | **COMPLETED** | `client-desktop`, `platform-modulith` |
| **Milestone 6** | Online 3D Viewport, Vana'diel Time, Lighting & Zoneline Groundwork | Full 3D visual textured rendering in online mode (`InGameState`), in-world 3D remote entity & NPC rendering, 3D floating billboard nameplates, Vana'diel 25x time/calendar/moon phase cycle, dynamic diurnal day/night lighting, weather atmospheric fog, client-side weather flagging & time scrubbing controls, and `ZoneInteractionParser` for zoneline trigger bounds | **COMPLETED** ([`4feb0b8`](https://github.com/TheStackTraceWhisperer/c2/commit/4feb0b8)) | `client-desktop`, `platform-modulith`, `game-asset-pipeline` |
| **Milestone 7** | Authoritative Cross-Zone Transitions & Dynamic World Streaming | Full cross-zone transfer pipeline (`ZoneChangeRequest`/`Response`), screen fade transitions, dynamic asset unloading/streaming across zones, authoritative server session transfer across spatial grids, and entrance spawn positioning | **PLANNED** | `platform-modulith`, `client-desktop`, `game-asset-pipeline` |

---

## Detailed Milestone Specifications

### Milestone 1: Asset Foundation & Collision Mesh (Completed)
- **Shared Module**: Created `game-asset-pipeline` with zero server/client cyclic coupling.
- **Math Primitives**: Immutable records (`Vector3f`, `Matrix3f`, `Matrix4f`, `BoundingBox`, `CollisionTriangle`, `Ray`, `RayHit`).
- **Binary & Decryption**: `DatByteReader` (little-endian), `DatBlockDecoder`, `ZoneDecrypt` with exact 256-byte static key tables from engine DLL for `0x1B` decoding.
- **Spatial Collision Grid**: `CollisionMap`, `CollisionMesh`, `CollisionObjectGroup`, `CollisionObject`, `CollisionTransform`.
- **glTF Exporter**: `GltfWriter` generating Khronos-validated binary `.glb` models (0 errors, 0 warnings across 106,462 triangles).
- **Offline World Demo**: Modern OpenGL 4.6 core renderer with terrain color coding, real-time ground raycasting, slope/stair climbing, jumping, and third-person camera in `client-desktop`.

### Milestone 2: Authoritative Server Collision & Kinematics (Completed)
- **Service Integration**: `ZoneCollisionService` in `platform-modulith` (`com.example.zone.collision`).
- **Ground Raycasting & Clamping**: Authoritative downward raycasting clamps player vertical positions on slopes and stairs within `maxStepHeight` (1.2m).
- **Floor Penetration Prevention**: Automatically detects if a player falls below geometry and reconciles position to ground level (`FLOOR_PENETRATION`).
- **Anti-Flyhack Validation**: Enforces `maxJumpHeight` (3.5m) above ground level (`EXCEEDED_MAX_JUMP_HEIGHT`).
- **Wall & Barrier Obstacle Checks**: Torso-height (+0.8m) segment raycasts detect walls and obstacles, pushing players back before the barrier surface (`WALL_BLOCKED`).
- **Kinematic Reconciliation**: `ZoneSimulationEngine` sets `reconciled = true` on `PlayerMovementResponse` whenever client movement violates physics constraints.
- **Architectural Rules**: ArchUnit tests verify strict hex architecture isolation across all services.

### Milestone 3: Visual Zone Geometry & Textures (Completed)
- **Texture Decompression (`S20_TEXTURE`)**:
  - Implemented `DxtDecompressor` supporting DXT1, DXT3, DXT5 block decompression to RGBA8888.
  - 8-bit paletted texture decompression with color lookup table to RGBA.
  - Implemented `TextureParser` handling name normalization, dimensions, FourCC compression signatures, and pixel buffers.
- **Visual Mesh Decryption & Parsing (`S2E_ZONE_MESH`)**:
  - Two-pass zone mesh decryption (`decryptZoneMeshFirstPass`, `decryptZoneMeshSecondPass`) using `KEY_TABLE_1` and `KEY_TABLE_2` block swapping (`swapNext8`).
  - Extracted 238/238 visual meshes (956 submeshes, 93,199 vertices, 65,637 triangles) in South San d'Oria (`ZoneMeshParser`).
  - Interleaved vertex format: positions (`Vector3f`), vertex normals (`Vector3f`), UV coordinates (`float u, v`), diffuse vertex RGBA colors.
- **Zone Assembly & Loader**:
  - `VisualZoneScene` indexing decoded textures and submeshes.
  - `VisualZoneLoader` providing end-to-end loading from DAT bytes or streams.
- **High-Performance Textured 3D Rendering**:
  - `VisualZoneRenderer` in `client-desktop` with GLSL 460 core shaders, material-based batching (reducing 956 submeshes to ~50 GPU draw calls), directional sunlight, double-sided lighting fallbacks, and alpha discard tests (`uAlphaThreshold = 0.375`).
  - Integrated into `OfflineWorldState` with real-time HUD toggles: F1 (Wireframe), F2 (Visual Textured Zone), F3 (Collision Mesh Overlay).

### Milestone 4: Character & Town NPC Visuals, Skeletal Animation & Poses (Completed)
- **Quaternion 3D Rotations & Interpolation**:
  - Implemented `Quaternion` record supporting identity, normalization, dot product, multiplication, from Euler angles (`fromEuler`), and Matrix4f conversion.
  - Implemented spherical linear interpolation (`slerp`) and normalized linear interpolation (`nlerp`) for smooth keyframed rotational tracks.
- **Humanoid Skeletal Rig & Hierarchy (`HumanoidRig`, `Joint`, `Skeleton`)**:
  - `HumanoidRig`: authentic 21-bone humanoid skeleton hierarchy (`BONE_ROOT`, `BONE_SPINE`, `BONE_CHEST`, `BONE_NECK`, `BONE_HEAD`, shoulders, arms, forearms, hands, hips, thighs, calves, feet) with model-space bind pose (~1.75m tall).
  - `Skeleton` hierarchical forward kinematics (`computeBindPoseMatrices`) and inverse bind pose calculation (`computeInverseBindPoseMatrices`, `computeSkinningMatrices` evaluating $S_i = P_i \cdot B_i^{-1}$).
  - `Matrix4f.invert()` analytic 4x4 matrix inversion using cofactor expansion.
- **Procedural Keyframed Locomotion Animations (`StandardAnimations`)**:
  - `createIdleAnimation()`: 30-frame loop (1.0s) with spine breathing, arm relaxation, and head sway.
  - `createWalkAnimation()`: 30-frame loop (1.0s) with counter-arm swing, leg strides, and pelvic bounce.
  - `createRunAnimation()`: 20-frame athletic loop (~0.66s) with forward torso lean, bent-arm pumping, and wide strides.
  - `createLocomotionStateMachine()`: configured `AnimationStateMachine` managing locomotion transitions, normalized cross-fades, and FK pose evaluation.
- **Multi-Slot Composite Character Mesh Generator (`CharacterMeshGenerator`)**:
  - Procedural authentic 3D humanoid character mesh generator assembling 7 composite equipment pieces:
    - Head & Cranium / Helmet (`SLOT_HEAD`)
    - Torso Cuirass / Doublet / Tunic (`SLOT_BODY`)
    - Arms & Gauntlets (`SLOT_HANDS`)
    - Breeches & Greaves (`SLOT_LEGS`)
    - Boots & Sabatons (`SLOT_FEET`)
    - Steel Broadsword / Arcane Staff (`SLOT_MAIN`)
    - Knight's Heater Shield for Paladins (`SLOT_SUB`)
  - Linear blend skinning weights on all vertices (dual-bone influence $w_0 + w_1 = 1.0$).
  - Role and job styling palettes: WAR/PLD (Steel cuirass & crimson trim), WHM (pristine white robe & red triangles), BLM (midnight coat & gold runes), RDM (crimson doublet & jabot), THF/NIN (leather jerkin), and Town NPC (amber civic tunic & royal blue slacks).
- **GPU Skinned Mesh Renderer (`CharacterRenderer`)**:
  - Modern OpenGL 3.3 core shader program evaluating hardware linear blend skinning across 32 bone matrix uniforms (`uBones[32]`).
  - Unified VAO/VBO/EBO geometry uploads with cached job models.
  - Integrated atmospheric lighting (matching celestial sun/moon vectors) and distance fog.
  - Fully wired into `InGameState` (local player avatar, remote players, and town NPCs driven by movement speed/flags) and `OfflineWorldState` (player avatar and South San d'Oria town NPCs) with live hotkey toggles (F3 / F5).
- **Town NPCs (`NpcTable-V3.DAT`)**:
  - `NpcTable` parser indexing 31,470 authentic NPCs across 279 zones with exact 3D coordinates, rotations, equipment slots, and flags.
  - Extracted 668 town NPCs in South San d'Oria (Zone 230).
  - Integrated into `OfflineWorldState` and `InGameState` with animated 3D character models, proximity radar, and nameplate overlays.

### Milestone 5: Entity Simulation, Interpolation & Polish (Completed)
- **Authoritative Server Entity Simulation & AOI Streaming (Completed)**:
  - Wired town NPCs from `NpcTable-V3.DAT` (668 NPCs in South San d'Oria, Zone 230) directly into `ZoneSimulationEngine` and `SpatialGrid`.
  - 50m Area of Interest (AoI) difference streaming: emits `EntitySpawnRecord` on approach, delta updates for mobile entities, and `despawns` on departure.
  - Stationary quietude optimization: stationary NPCs consume 0 tick update bandwidth after initial spawn.
  - Excluded static NPCs from character database persistence flushes.
  - Enhanced client `WorldRadarPanel` to differentiate town NPCs (golden amber markers `#F5C738`, `[NPC]` nameplate, interactive hover tooltips) from player characters (sky blue markers, job abbreviation, level).
- **Remote Entity Visual Interpolation Buffer (`EntityInterpolationBuffer`)**:
  - Ring buffer of timestamped kinematic snapshots: position $(x, y, z)$, velocity $(vx, vy, vz)$, yaw, pitch, and movement flags.
  - Configurable 100ms (2 simulation ticks) interpolation delay window for jitter-free 20 Hz to 60+ FPS rendering.
  - Linear/Hermite position interpolation and shortest-arc spherical angle interpolation (`dYaw` wrapping normalization) preventing 359° $\to$ 1° rotational snapping.
  - Teleport/snap threshold (> 10m) avoiding rubber-banding during respawns and zone transitions.
  - Forward extrapolation up to 250ms with velocity decay during packet loss.
- **Animation State Machine (`AnimationStateMachine`)**:
  - Deterministic state transitions (`IDLE`, `WALK`, `RUN`, `JUMP`, `FALL`, `REST`, `ENGAGED`) driven by `MovementFlags` and horizontal entity speed.
  - Normalized cross-fade blend timers ($0.0 \to 1.0$ weight over 0.15s) for smooth transitions between locomotion and resting/combat poses.
  - Playback time scaling proportional to locomotion velocity.
  - Integration with `SkeletonAnimation` and `CompositeCharacterModel` for FK joint pose evaluation.
- **Authoritative Server NPC Patrol & Roam Simulation (`NpcPatrolSimulation`)**:
  - Autonomous waypoint navigation (`NpcPatrolRoute`, `NpcPatrolWaypoint`) with dwell pause timers for town guards and wandering NPCs.
  - Authoritative ground raycasting via `ZoneCollisionService` clamping patrolling NPCs to terrain, stairs, and bridges.
  - Automatic heading calculation, speed regulation, and `SpatialGrid` updates streaming 20 Hz `EntityMovementUpdate` packets to nearby players within the 50m AoI.
- **In-Game Remote Entity Visual Integration**:
  - Desktop client in-game integration with `EntityInterpolationBuffer` updating remote positions smoothly every render frame.
  - Batch 3D entity markers rendered in world coordinates with directional facing cones and proximity HUD.

### Milestone 6: Online 3D Viewport, Vana'diel Time, Lighting & Zoneline Groundwork (Completed)
- **Authoritative Online 3D Viewport (`InGameState`)**:
  - Integrated `VisualZoneRenderer` into `InGameState`, asynchronously loading zone DAT visual geometry (42 meshes, 10 textures, 82,000+ visual triangles for Zone 230).
  - Modern third-person polar camera tracking avatar position with collision-aware zoom and right-click orbit controls.
  - Batch 3D rendering of player avatar, remote player characters, and town NPCs in real-time at interpolated coordinates (`EntityInterpolationBuffer`).
  - Screen-space billboarded 3D nameplates (player names, job/level tags, amber `[NPC]` badges) projected above entity heads with distance fade (`MatrixMath.projectToScreen`).
- **Vana'diel Time & Calendar Simulation (`VanadielTime`, `MoonPhase`, `VanadielDayOfWeek`)**:
  - Authoritative 25× speed time engine ($1\text{ real sec} = 25\text{ Vana'diel sec}$; $1\text{ Vana'diel day} = 57.6\text{ real min}$) calculated from 2002-01-01 00:00:00 UTC epoch.
  - Full 8-day elemental week: Firesday, Earthsday, Watersday, Windsday, Iceday, Lightningsday, Lightsday, Darksday.
  - 360-day calendar (12 months of 30 days) and 84-day moon cycle with 12 moon phases (0% New Moon to 100% Full Moon).
  - In-game HUD clock widget displaying elemental day, time, lunar phase percentage, and ambient weather.
- **Dynamic Diurnal Day/Night Cycle & Atmospheric Lighting (`DiurnalLighting`)**:
  * Sun and moon celestial orbit vector calculations based on Vana'diel hour ($0.0 \to 24.0$).
  * 4-phase ambient/diffuse/sky color gradient interpolation:
    - *Dawn (04:00 - 07:00)*: Rosy-amber sunrise horizon, low-angle golden directional sunlight.
    - *Day (07:00 - 17:00)*: Crisp sunlight, azure sky, neutral high-illumination ambient lighting.
    - *Dusk (17:00 - 19:30)*: Vibrant crimson twilight glow and long dramatic grazing shadows.
    - *Night (19:30 - 04:00)*: Deep midnight navy sky (`#0A0F1D`), cool pale blue moonlight.
  * Direct shader uniform binding (`uSunDir`, `uSunColor`, `uAmbientColor`) in `VisualZoneRenderer`.
- **Zone Weather & Distance Fog**:
  * Zone weather profiles (`WeatherType`: Clear, Sunshine, Clouds, Rain, Fog, Storm, Thunder, Wind, Dust, Snow) with ambient brightness scaling and fog density multipliers.
  * Distance fog uniforms (`uFogStart`, `uFogEnd`, `uFogColor`, `uFogEnabled`) adapting dynamically to weather density and time of day in GLSL fragment shaders.
  * Client-side environmental HUD controls in `InGameState` and `OfflineWorldState`: interactive weather selector dropdown and manual time-of-day slider with quick presets (Dawn, Noon, Dusk, Midnight) allowing live atmospheric inspection.
- **Zoneline Groundwork (`ZoneInteractionParser`, `ZoneInteraction`)**:
  * Parser extracting 3D oriented bounding boxes (OBBs), source IDs, target zone IDs (`destId`), and entrance points from Section 0x36 (`"RID"`) in FFXI zone DAT files.
  * Automated zoneline harvesting in `ZoneSimulationEngine` loading authentic DAT zonelines into memory.
  * In-game zoneline detection rendering gate notification banner when standing inside or approaching portal volumes.

### Milestone 7: Authoritative Cross-Zone Transitions & Dynamic World Streaming (Completed)
- **Cross-Zone Protocol & Screen Fade Transition (`ScreenFader`)**:
  - Smooth visual screen fader (`ScreenFader`: 0.35s fade-to-black, input lock, callback on black, and 0.35s fade-in on arrival).
  - Integrated `ZoneTransferNotification` handling in `InGameState.onHandlePacket` locking inputs, transitioning scene, and positioning avatar at spawn point.
- **Authoritative Server Session Transfer (`ZoneSimulationEngine`)**:
  - Continuous swept-OBB collision checks across authentic DAT zoneline volumes.
  - Authoritative persistence: updates character zone ID and entrance coordinates $(x, y, z, \text{yaw}, \text{pitch})$ asynchronously via `characterRepository.updatePosition`.
  - Deregisters character from source zone `spatialGrid` and active simulation entities.
  - Broadcasts immediate despawns to nearby observers in the source zone via `ZoneStateDeltaMessage`.
  - Dispatches `ZoneTransferNotification` with target zone ID and spawn coordinates to the transitioning client.
- **Dynamic Client Asset Streaming (`InGameState`)**:
  - Dynamic zone DAT resolution loading visual geometry and interactions by zone ID (e.g., South San d'Oria 230, West Ronfaure 100).
  - Clean GPU buffer replacement: uploads new zone VBOs and textures without memory leaks.
  - Clamps player avatar to authentic target entrance vectors and resets camera target.
  - Complete test verification across `client-desktop` (ScreenFader lifecycle, zone DAT loading, transfer handling) and `platform-modulith` (swept-OBB trigger, persistence, observer despawn).

