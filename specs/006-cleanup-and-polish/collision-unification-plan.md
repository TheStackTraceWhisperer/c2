# Dual Collision Stack Unification Plan (`game-asset-pipeline` Phase 4)

**Status**: Completed in Phase 4 on `006-cleanup-and-polish`  
**Modules Impacted**: `game-asset-pipeline`, `platform-modulith`, `client-desktop`

---

## 1. Current State & Divergence Inventory

Two independent collision representations currently live side-by-side inside `game-asset-pipeline`:

| Dimension | Legacy `com.example.asset.collision.*` (Server-side) | Viewer `com.ffxi.model.ZoneCollision*` (Client-side) |
|---|---|---|
| **Parser Entry Point** | `com.example.asset.parser.ZoneDefParser.parseFromDat(...)` | `com.ffxi.parser.DatParser.parseCollisionMeshData(...)` |
| **Zone Transform Matrix** | Stores column-major `Matrix4f` in `CollisionTransform`, pre-transforms triangles into `CollisionObject.worldSpaceTriangles()` at load time | Stores raw 16-float `toWorldSpace` array on `ZoneCollisionObject` and local-space `ZoneCollisionTriangle`; client `ZoneCollisionSystem` transforms per raycast |
| **Material / Surface Metadata** | Preserves `TriFlags` (`isHitWall()`) and `TerrainType` per `CollisionTriangle` | Omits `TriFlags` and `TerrainType`; classifies walls vs. floors solely via transformed normal Y |
| **Spatial Broadphase** | `CollisionMap.getNearbyGroups(...)` + `raycastFloor(...)` + `raycastSegment(...)` (`RayTriangleCollider`) | `ZoneCollisionMap.getGroups(...)` + `getObjectsAlongSegment(...)` queried by client `ZoneCollisionSystem` |
| **Consumers** | `platform-modulith` (`ZoneCollisionService`, `ZoneSimulationEngine`), `ZoneDefParserTest` | `client-desktop` (`ZoneModelLoader`, `ZoneCollisionSystem`, `CollisionMeshRenderer`), `ffxi-tools` (`CharacterViewer`) |

Because client and server parse `S1C_ZONE_DEF` independently through two code paths, subtle edge cases (for example `TriFlags.isHitWall()`, sub-block grid stride edge cases, or step/wall tolerances) can drift between client prediction and authoritative server reconciliation.

---

## 2. Target Architecture (Single Unified Collision Model)

Converge onto **`com.example.asset.collision.CollisionMap`** parsed from a single shared `S1C_ZONE_DEF` parser path in `game-asset-pipeline`, retaining all FFXI triangle metadata (`TriFlags`, `TerrainType`) and broadphase queries:

1. **Preserve Rich Triangle & Transform Metadata**:
   - Keep `TriFlags` (`isHitWall()`), `TerrainType`, `BoundingBox`, and precomputed `worldSpaceTriangles` on the unified `CollisionObject` / `CollisionMap`.
   - Ensure `CollisionMap` handles degenerate `subBlocksX == 0` / `subBlocksZ == 0` guards currently implemented in `ZoneCollisionMap` (preventing division by zero on malformed DAT headers).
2. **Add Missing Shared Queries to `CollisionMap`**:
   - Add `getObjectsAlongSegment(float x0, float z0, float x1, float z1)` to `CollisionMap` alongside `allWorldSpaceTriangles()`.
   - Expose double-sided `wallsOnly` / full-geometry segment raycast helpers on `CollisionMap` so both `ZoneCollisionService` (server) and `SceneCamera` / `MovementInputHandler` (client) evaluate identical triangle sets.
3. **Eliminate Double DAT Scan in Client Zone Loading**:
   - Update `DatParser.parseZoneDefSection(...)` to emit `CollisionMap` directly on `ZoneDefResource` (eliminating duplicate collision mesh parsing between `DatParser` and `ZoneDefParser`).
   - Route `ZoneCollisionService.loadZoneCollision(...)` through the shared `CollisionMap` parser entry point so server and client invoke the exact same binary reader code.

---

## 3. Completed Migration Sequence

1. **Step A — Harden `CollisionMap` (Done)**: Ported zero-subblock clamping, null/bounds safety, `getBlockCenterXZ(...)`, `allObjects()`, `getObjectsAlongSegment(...)`, and segment-wide `raycastSegment(start, end, minDistance, triangleFilter)` into `com.example.asset.collision.CollisionMap`, plus zero-area degenerate triangle rejection in `RayTriangleCollider`, backed by `CollisionMapTest`.
2. **Step B — Unify Parser Output (Done)**: Extracted decryption-free `ZoneDefParser.parseCollisionMeshSection(...)` so both `ZoneDefParser` and `DatParser.parseZoneDefSection(...)` invoke one binary collision decoder carrying `TriFlags` and `TerrainType` into `ZoneDefResource.collisionMap()`.
3. **Step C — Adapt Client Consumers (Done)**: Updated `ZoneCollisionSystem`, `ZoneModelLoader`, `CollisionMeshRenderer`, and `WorldScene` to consume `CollisionMap` directly and deleted redundant per-raycast matrix math and `TransformedTriangle`.
4. **Step D — Verify Equivalence (Done)**: Added retail DAT parity tests across Zone #230 and Zone #100 in `ZoneDefParserTest` and `ZoneModelLoaderIntegrationTest`.
5. **Step E — Retire Legacy Types (Done)**: Deleted `ZoneCollisionMap`, `ZoneCollisionObject`, `ZoneCollisionObjectGroup`, `ZoneCollisionTriangle`, and `ZoneCollisionMapTest`.
