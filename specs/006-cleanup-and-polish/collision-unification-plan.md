# Dual Collision Stack Unification Plan (`game-asset-pipeline` Phase 4)

**Status**: Planned follow-up after `006-cleanup-and-polish` Phases 1–3  
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

## 3. Incremental Migration Sequence

1. **Step A — Harden `CollisionMap`**: Port `ZoneCollisionMap`'s zero-subblock clamping and `getObjectsAlongSegment` into `com.example.asset.collision.CollisionMap`, backed by unit tests migrated from `ZoneCollisionMapTest`.
2. **Step B — Unify Parser Output**: Have `DatParser.parseZoneDefSection(...)` delegate collision section decoding to the unified `ZoneDefParser` / `CollisionMap` builder (carrying `TriFlags` and `TerrainType`) and attach `CollisionMap` to `ZoneDefResource`.
3. **Step C — Adapt Client Consumers**: Update `ZoneCollisionSystem` and `CollisionMeshRenderer` in `client-desktop` to wrap `CollisionMap` (`findFloorY`, `raycastSegmentDistance`, `raycastWallDistance`, and `getAllWorldTriangles` reading pre-transformed `worldSpaceTriangles` directly).
4. **Step D — Verify Equivalence**: Run deterministic comparison assertions over retail Zone #230 and Zone #100 verifying `findFloorY` and `raycastSegmentDistance` parity between the old `ZoneCollisionMap` and unified `CollisionMap` across a grid of coordinates.
5. **Step E — Retire Legacy Types**: Delete `ZoneCollisionMap`, `ZoneCollisionObject`, `ZoneCollisionObjectGroup`, and `ZoneCollisionTriangle` once `DatParser`, `ZoneDefResource`, `ZoneCollisionSystem`, and `ZoneModelLoader` compile cleanly with zero references remaining.
