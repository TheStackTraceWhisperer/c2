# Project C2 Development Roadmap

This roadmap outlines the milestones for harvesting and porting the FFXI game asset pipeline, world geometry, collision systems, rendering engine, and entity simulation into `c2` (Java 25, Micronaut Modulith, LWJGL 3, Dear ImGui, Netty QUIC).

---

## Milestone Overview

| Milestone | Title | Scope & Deliverables | Status | Primary Modules |
|---|---|---|---|---|
| **Milestone 1** | Asset Foundation & Collision Mesh | DAT file decoding, bit-rotation decryption (`0x1B`), spatial collision grid, 3D math primitives, Khronos-validated binary `.glb` export, offline 3D collision exploration demo in `client-desktop` | **COMPLETED** ([`058a2e4`](https://github.com/TheStackTraceWhisperer/c2/commit/058a2e4), [`2689b45`](https://github.com/TheStackTraceWhisperer/c2/commit/2689b45)) | `game-asset-pipeline`, `client-desktop` |
| **Milestone 2** | Authoritative Server Collision & Kinematics | `ZoneCollisionService` with authoritative zone mesh loading, floor clamping, slope/stair validation, floor penetration prevention, anti-flyhack validation, horizontal wall collision checks in `ZoneSimulationEngine` | **COMPLETED** ([`8897ea7`](https://github.com/TheStackTraceWhisperer/c2/commit/8897ea7)) | `platform-modulith`, `game-asset-pipeline` |
| **Milestone 3** | Visual Zone Geometry & Textures | `S20_TEXTURE` parser (DXT1, DXT3, DXT5, 8-bit paletted textures), `S2E_ZONE_MESH` two-pass decryption & parser (interleaved vertices, normals, UVs, vertex colors, material assignments), `VisualZoneScene` / `VisualZoneLoader`, high-performance textured batch renderer `VisualZoneRenderer` with alpha discard & material sorting in `client-desktop` | **COMPLETED** | `game-asset-pipeline`, `client-desktop` |
| **Milestone 4** | Character & Town NPC Visuals & Animation | `Quaternion` (SLERP/NLERP), `Joint` / `Skeleton` (`S29_SKELETON` forward kinematics), `SkinnedMesh` (`S2A`/`S25` dual-bone weights), `SkeletonAnimation` (`S2B_SKELETON_ANIMATION` keyframed tracks), `CompositeCharacterModel` (multi-slot equipment compositing), `NpcTable` (31,470 authentic NPCs across 279 zones), town NPC visual markers & proximity HUD in `OfflineWorldState` | **COMPLETED** | `game-asset-pipeline`, `client-desktop` |
| **Milestone 5** | Entity Simulation, Interpolation & Polish | Remote entity 20 Hz to 60+ FPS visual interpolation buffer, animation state machine transitions (idle, run, combat), basic server-side NPC patrolling & line-of-sight aggro | **COMPLETED** | `client-desktop`, `platform-modulith` |

---

## Detailed Milestone Specifications

### Milestone 1: Asset Foundation & Collision Mesh (Completed)
- **Shared Module**: Created `game-asset-pipeline` with zero server/client cyclic coupling.
- **Math Primitives**: Immutable records (`Vector3f`, `Matrix3f`, `Matrix4f`, `BoundingBox`, `CollisionTriangle`, `Ray`, `RayHit`).
- **Binary & Decryption**: `DatByteReader` (little-endian), `DatBlockDecoder`, `ZoneDecrypt` with exact 256-byte static key tables from engine DLL for `0x1B` decoding.
- **Spatial Collision Grid**: `CollisionMap`, `CollisionMesh`, `CollisionObjectGroup`, `CollisionObject`, `CollisionTransform`.
- **glTF Exporter**: `GltfWriter` generating Khronos-validated binary `.glb` models (0 errors, 0 warnings across 106,462 triangles).
- **Offline World Demo**: Modern OpenGL 3.3 core renderer with terrain color coding, real-time ground raycasting, slope/stair climbing, jumping, and third-person camera in `client-desktop`.

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
  - `VisualZoneRenderer` in `client-desktop` with GLSL 3.30 core shaders, material-based batching (reducing 956 submeshes to ~50 GPU draw calls), directional sunlight, double-sided lighting fallbacks, and alpha discard tests (`uAlphaThreshold = 0.375`).
  - Integrated into `OfflineWorldState` with real-time HUD toggles: F1 (Wireframe), F2 (Visual Textured Zone), F3 (Collision Mesh Overlay).

### Milestone 4: Character & Town NPC Visuals, Skeletal Animation & Poses (Completed)
- **Quaternion 3D Rotations & Interpolation**:
  - Implemented `Quaternion` record supporting identity, normalization, dot product, multiplication, and Matrix4f conversion.
  - Implemented spherical linear interpolation (`slerp`) and normalized linear interpolation (`nlerp`) for smooth keyframed rotational tracks.
- **Skeletal Hierarchy (`S29_SKELETON`)**:
  - `Joint` record with bone index, parent index, rotation quaternion, and translation vector.
  - `JointReference` sockets and standard anchor points.
  - `Skeleton` record with hierarchical forward kinematics (`computeBindPoseMatrices`) traversing parent chains ($M_i = M_{\text{parent}} \times T_i \times R_i$).
  - `SkeletonParser` for `S29_SKELETON` sections.
- **Skinned Meshes (`S2A_SKELETON_MESH` & `S25_WEIGHTED_MESH`)**:
  - `SkinnedVertex` with dual-bone skinning weights ($w_0 + w_1 = 1.0$), bone indices ($b_0, b_1$), positions, normals, and UV texture coordinates.
  - `SkinnedSubMesh` and `SkinnedMesh` with material definitions and triangle indices.
  - `SkeletonMeshParser` decoding `TriMesh` (`0x0054`) and `TriStrip` (`0x5453`) opcodes.
- **Skeletal Keyframe Animation & Compositing**:
  - `KeyFrameTransform` and `SkeletonAnimation` evaluating continuous joint poses at arbitrary floating-point frame times.
  - `CompositeCharacterModel` assembling a shared race/gender skeleton with 8 equipment pieces (`Head`, `Body`, `Hands`, `Legs`, `Feet`, `Main`, `Sub`, `Ranged`) and evaluating FK pose matrices.
- **Town NPCs (`NpcTable-V3.DAT`)**:
  - `NpcTable` parser indexing 31,470 authentic NPCs across 279 zones with exact 3D coordinates, rotations, equipment slots, and flags.
  - Extracted 668 town NPCs in South San d'Oria (Zone 230).
  - Integrated into `OfflineWorldState` and `CollisionMeshRenderer` with real-time batched GPU rendering of NPC markers, proximity radar tracking nearest NPC name, distance, and model ID, and HUD toggles (F4).

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
