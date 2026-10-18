# Project C2 Development Roadmap

This roadmap outlines the milestones for harvesting and porting the FFXI game asset pipeline, world geometry, collision systems, rendering engine, and entity simulation into `c2` (Java 25, Micronaut Modulith, LWJGL 3, Dear ImGui, Netty QUIC).

---

## Milestone Overview

| Milestone | Title | Scope & Deliverables | Status | Primary Modules |
|---|---|---|---|---|
| **Milestone 1** | Asset Foundation & Collision Mesh | DAT file decoding, bit-rotation decryption (`0x1B`), spatial collision grid, 3D math primitives, Khronos-validated binary `.glb` export, offline 3D collision exploration demo in `client-desktop` | **COMPLETED** ([`058a2e4`](https://github.com/TheStackTraceWhisperer/c2/commit/058a2e4), [`2689b45`](https://github.com/TheStackTraceWhisperer/c2/commit/2689b45)) | `game-asset-pipeline`, `client-desktop` |
| **Milestone 2** | Authoritative Server Collision & Kinematics | `ZoneCollisionService` with authoritative zone mesh loading, floor clamping, slope/stair validation, floor penetration prevention, anti-flyhack validation, horizontal wall collision checks in `ZoneSimulationEngine` | **COMPLETED** ([`8897ea7`](https://github.com/TheStackTraceWhisperer/c2/commit/8897ea7)) | `platform-modulith`, `game-asset-pipeline` |
| **Milestone 3** | Visual Zone Geometry & Textures | `S20_TEXTURE` parser (DXT1, DXT3, DXT5, 8-bit paletted textures), `S2E_ZONE_MESH` parser (vertex buffers, normals, UVs, materials), static scenery props, textured visual rendering in `client-desktop` | **IN PROGRESS** | `game-asset-pipeline`, `client-desktop` |
| **Milestone 4** | Character & Town NPC Visuals & Animation | `S29_SKELETON` (64-bone hierarchy, bind poses), `S2A`/`S25` skinned meshes (dual-bone weighted vertices), `S2B_SKELETON_ANIMATION` (LERP translation, SLERP quaternions), multi-slot equipment compositing (`ItemModelTable`), town NPC spawns & static poses (`NpcTable-V3.DAT`), GPU vertex skinning shader (`BonePalette` UBO) | **SPECIFIED** | `game-asset-pipeline`, `client-desktop`, `common-protocol` |
| **Milestone 5** | Entity Simulation, Interpolation & Polish | Remote entity 20 Hz to 60+ FPS visual interpolation buffer, animation state machine transitions (idle, run, combat), basic server-side NPC patrolling & line-of-sight aggro | **PLANNED** | `client-desktop`, `platform-modulith` |

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

### Milestone 3: Visual Zone Geometry & Textures (In Progress)
- **Texture Decompression (`S20_TEXTURE`)**:
  - DXT1 (BC1), DXT3 (BC2), DXT5 (BC3) block decompression to RGBA8888.
  - 8-bit paletted textures (color lookup table to RGBA).
- **Visual Mesh Parsing (`S2E_ZONE_MESH`)**:
  - Vertex buffers with interleaved positions, vertex normals, UV texture coordinates, and diffuse vertex colors.
  - Submesh material definitions and texture references.
- **Scene Assembly**:
  - Placement of static scenery props and building geometry via zone object transforms.
- **Visual Rendering**:
  - Modern GLSL shaders supporting textured meshes, alpha testing / blending, and directional lighting in `client-desktop`.

### Milestone 4: Character & Town NPC Visuals, Skeletal Animation & Poses (Specified)
- **Skeletal Hierarchy (`S29_SKELETON`)**:
  - Bone tree hierarchy, parent indices, local translations, rotations, and inverse bind pose matrices (up to 64 bones).
- **Skinned Mesh Decoding (`S2A_SKELETON_MESH` & `S25_WEIGHTED_MESH`)**:
  - Vertex attributes: `[Pos: vec3 | Normal: vec3 | UV: vec2 | BoneIndices: ivec2 | BoneWeights: vec2]`.
- **Multi-Mesh Equipment Compositing**:
  - 5 body slots (`Head`, `Body`, `Hands`, `Legs`, `Feet`) + Weapons assembled onto a shared race/gender skeletal rig via `ItemModelTable.DAT`.
- **Skeletal Keyframe Animation (`S2B_SKELETON_ANIMATION`)**:
  - LERP for translation tracks, SLERP for rotation quaternions.
  - Bone matrix evaluation: $M_{\text{bone}} = M_{\text{parent}} \times T_{\text{anim}} \times R_{\text{anim}} \times M_{\text{invBind}}$.
- **GPU Hardware Skinning**:
  - GLSL 3.30+ core shader with uniform buffer bone palettes (`layout(std140) uniform BonePalette { mat4 uBones[64]; };`).
- **Town NPCs (Visuals & Static Poses)**:
  - Read `NpcTable-V3.DAT` to spawn town NPC entities at static map coordinates with looped idle, standing, or seated poses (strictly visual scenery; no scripts or interaction dialogue).

### Milestone 5: Entity Simulation, Interpolation & Polish (Planned)
- **Client Interpolation Buffer**:
  - Smooth visual interpolation of remote player positions and rotations from 20 Hz server ticks to 60+ FPS render frames.
- **Animation State Machine**:
  - Transitions between idle, running, walking, jumping, sitting, and combat poses based on entity movement flags and actions.
- **Server Patrol Simulation**:
  - Server-side NPC/monster wandering and line-of-sight detection using authoritative collision maps.
