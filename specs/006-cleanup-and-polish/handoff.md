# Session Handoff — `006-cleanup-and-polish`

**Date**: 2026-11-24  
**Workspace**: `/home/samuel_l_hyman/c2`  
**Branch**: `006-cleanup-and-polish` (tracking `origin/006-cleanup-and-polish`, prior baseline `c9f0f74`)  
**Status**: Clean working tree — all changes committed and pushed to `origin/006-cleanup-and-polish`.

---

## 1. Current Status Summary

All requested work on `006-cleanup-and-polish` — production-only snapshot convergence, Northern San d'Oria water/fountain fixes, live-server walkthrough migration, production ortho maps with an independent fog toggle, and the full retail FFXI rendering pipeline (diurnal `S2F` lighting/sky/fog, nighttime `S05` point lights & streetlamp cones, celestial sky layers, retail actor ground shadows, and Dawn/Noon/Dusk/Midnight gallery coverage) — is **implemented, verified, visually inspected, committed, and pushed to `origin/006-cleanup-and-polish`**.

---

## 2. Implemented Changes Breakdown

### A. Production Snapshot Convergence & Walkthrough Migration
- Converged all snapshot test suites onto production OpenGL 4.6 Core / `WorldScene` / `ImGuiService` / `InGameMapPanel` / `FontMojiGlRenderer` / `ScreenshotCapture` pipelines with zero test-side `Graphics2D` painting or custom test shaders:
  - Updated `CharacterAnimationSnapshotSuiteTest.java`, `NpcAndMonsterSnapshotSuiteTest.java`, `ThirdPersonCameraSnapshotSuiteTest.java`, `ZoneIngameMapSnapshotSuiteTest.java`, `ZoneRenderingSnapshotSuiteTest.java`, `LiveServerClientSnapshotIT.java`, `HeadlessGlContext.java`.
  - Replaced Java2D `UiTextureSnapshotSuiteTest.java` (deleted) with `client-desktop/src/test/java/com/ffxi/viewer/DesktopUiSnapshotSuiteTest.java`.
  - Migrated city walkthrough captures into `LiveServerClientSnapshotIT.java` and deleted `TownWalkthroughCameraSnapshotSuiteTest.java`.
  - Removed non-production tautological test from `CharacterCreationValidationTest.java`.
  - Overhead orthographic maps render in production `WorldScene`, with `WorldScene.setEnableFog(boolean)` kept as an explicit toggle independent of orthographic camera mode.

### B. Retail `S2F_Environment` (`0x2F`) Diurnal Lighting, Sky Dome & Fog
- **`game-asset-pipeline`**:
  - Added `game-asset-pipeline/src/main/java/com/ffxi/model/ZoneEnvironmentResource.java` (`0xAC`-byte payload: `flags`, `actorGroup`/`bgGroup` `LightGroup`s, clear RGBA, `drawDist`, `hasDome`, `spokeCount`, `skyRadius`, 8-slice `skyRgba` & `skyStops`).
  - Added `parseEnvironmentSection` in `game-asset-pipeline/src/main/java/com/ffxi/parser/DatParser.java`.
- **`client-desktop`**:
  - Added `client-desktop/src/main/java/com/ffxi/viewer/environment/ZoneEnvironmentTable.java` for `weat/<tag>/<HHMM>` and sub-area (`ev01..`) lookup, `VanadielTime` keyframe interpolation, retail `sun_moon` travel vectors, indoor directional light slerp, and non-cancelling `06:00`/`18:00` directional light transitions.
  - Updated `client-desktop/src/main/java/com/ffxi/viewer/environment/AuthenticSkyDome.java` (`updateFromEnvironment` with horizon-to-nadir skirt and `GL_DEPTH_TEST` disabled during dome draw).
  - Updated `ZoneShader.java`, `CharacterShader.java`, and `WorldScene.java` for retail two-light scenery lighting, retail `lit_actor` illumination, per-area overrides, and linear fog controlled by independent `enableFog`.

### C. Retail `S05` Water/Fountains, Nighttime Point Lights, Streetlamp Cones & Celestial Sky Layers
- **`game-asset-pipeline`**:
  - Added `game-asset-pipeline/src/main/java/com/ffxi/model/ParticleKeyFrameResource.java` (`S19`) and `game-asset-pipeline/src/main/java/com/ffxi/model/ZoneEffectGeneratorResource.java` (`S05`: `attachType`, `hasInitialVelocity`, `lifeAlphaKeyFrameId`, point-light fields `0x58`/`0x6C`/`0x49`, `isPointLight()`, `followCamera()`, `isStationaryLampConeMesh()`, `billboardCamera()` for retail `0x01C0 == 0x00C0` separate from `billboardXYZ()`).
  - Fixed `mapZonePointLight` in `DatParser.java` (`0 -> -1` instead of `0`).
  - Extended `CollisionTransform.java`, `ZoneDefParser.java`, and `CollisionMap.java` to expose `lightIndicesPacked`, 4-char `environmentId`, and `pointLightIndex(slot)`.
- **`client-desktop`**:
  - Added `client-desktop/src/main/java/com/ffxi/viewer/environment/ZonePointLight.java` (`rgb / 128`, `evaluateIntensity(clock)` via `pttm` ToD curves).
  - Added `client-desktop/src/main/java/com/ffxi/viewer/renderer/ZoneEffectParams.java` (`BillboardMode.NONE/XZ/XYZ/CAMERA`, live `S19` ToD color evaluation).
  - Extended `ZoneCollisionSystem.java` (`findFloorTransform`), `LoadedZone.java`, `ZoneRenderChunk.java`, and `ZoneModelLoader.java` to load per-chunk point lights (up to 4 slots), floor-lamp actor illumination, ambient `effe/` water/fountain/streetlamp cones (`0x2D` `spaf` × `0x63` `optm`), and `weat/<tag>/` celestial `S05` layers (`sun1`, `kasa`, `cld1`/`cld2`, `star`/`sta1`).
  - Implemented retail `FUN_10044350` camera-facing orientation (`BillboardMode.CAMERA`, aligning local `+X` toward camera via `rotateY(-atan2(dz, dx)).rotateZ(atan2(dy, hypot(dx, dz)))`) and retail `FUN_10180700` placement distances (`1000.0f` for sun, `900.0f` for moon) in `WorldScene.java`.
  - Fixed opaque vs blended pass ordering/masking so Northern San d'Oria courtyard fountain masonry renders fully opaque with flowing water cascades.

### D. Retail Actor Ground Shadows
- Added `client-desktop/src/main/java/com/ffxi/viewer/renderer/RetailActorShadowRenderer.java`:
  - Loads retail `system  kage_tex` (`0x1b8c` -> `ROM/27/81.DAT`, 256×256).
  - Renders `HIGH` skinned actor silhouettes via `AuthenticCharacterInstance.renderSilhouette` and `CharacterShader` (`uSilhouetteMode`) into a `GL_R8` `GL_TEXTURE_2D_ARRAY` (`256×256×16`), with `NORMAL` `kage_tex` blob/feet fallback.
  - Projects shadows onto 3×3 `CollisionMap` floor triangles with `GL_ZERO, GL_ONE_MINUS_SRC_COLOR` blending, Z-bias, and no fog.
- Updated `WorldScene.java` retail defaults: `enableShadows=false`, `enableSsao=false`, `enableActorShadows=true`.

### E. Diurnal Gallery Captures & Server Category
- Added 16 diurnal snapshots (`dawn` `06:00`, `noon` `12:00`, `dusk` `18:30`, `midnight` `00:00`) across:
  - Northern San d'Oria Victory Square (`Zone 231`, live NPCs + fountain + lamps + shadows)
  - Northern San d'Oria Cathedral Moat (`Zone 231`)
  - Windurst Waters (`Zone 238`)
  - West Ronfaure (`Zone 100`)
- Added `"diurnal"` category (`/?cat=diurnal`) in `scripts/serve-snapshots.py`.

---

## 3. Latest Verification Results (All Green After Final Edit)

| Check | Command | Result |
|---|---|---|
| Full reactor build & unit/ArchUnit/smoke tests | `./scripts/with-xvfb.sh ./mvnw clean verify` | **BUILD SUCCESS** (`common-protocol` 16/16, `game-asset-pipeline` 99/99, `client-sdk` 21/21, `platform-modulith` 91/91 surefire & 15 failsafe with 12 skipped, `client-desktop` 121/121 incl. 4 ArchUnit checks, `ffxi-tools` 5/5) |
| Live server E2E snapshot IT | `./scripts/with-xvfb.sh ./mvnw -pl client-desktop failsafe:integration-test failsafe:verify -Dit.test=LiveServerClientSnapshotIT` | **7/7 passed** (0 failures, 0 skipped) |
| Visual snapshot suite | `./scripts/with-xvfb.sh ./mvnw -pl client-desktop -Psnapshots test` | **14/14 passed** (0 failures, 0 skipped) |
| Snapshot server syntax | `python3 -m py_compile scripts/serve-snapshots.py` | **Exit 0** |
| Pure-pipeline snapshot audit | `find client-desktop/src/test -name "*Snapshot*.java" -exec grep -HnE "Graphics2D\|#version\|renderPose\|GlLineBatch\|ImageIO\.write" {} +` | **Clean** (0 matches) |
| Visual PNG QA | Inspected regenerated PNGs in `client-desktop/target/snapshots/` | Confirmed clean sky gradients, soft circular dusk glow (`kasa`/`sun1`), opaque fountains, directional actor silhouette shadows, fogless ortho maps, and zero sky-arc artifacts |

---

## 4. Environment, Conventions & Pitfalls

- **Retail FFXI Data**: `/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI`
- **Local Git-Ignored Retail References**:
  - `references/vana-client` (Rust retail-matching reference)
  - `references/xim-source` (XIM reference)
  - `references/server` (LandSandBoat)
- **Headless OpenGL 4.6 Core**: Always wrap GL tests/snapshots with `./scripts/with-xvfb.sh ...` (sets `MESA_GL_VERSION_OVERRIDE=4.6`, `MESA_GLSL_VERSION_OVERRIDE=460`, `LIBGL_ALWAYS_SOFTWARE=1`).
- **Upstream Reactor Jars**: After modifying `game-asset-pipeline` (or `common-protocol`/`client-sdk`/`platform-modulith`), run `./mvnw -pl common-protocol,game-asset-pipeline,client-sdk,platform-modulith install -DskipTests -q` (or full `./mvnw clean verify`) before running single-module `-pl client-desktop` commands.
- **Snapshot Gallery Server**: Launch (if not already running in background) with:
  ```bash
  python3 /home/samuel_l_hyman/c2/scripts/serve-snapshots.py --port 8088 --dir /home/samuel_l_hyman/c2/client-desktop/target/snapshots
  ```
  - Accessible at `http://localhost:8088/` (`http://10.128.0.12:8088/`, `http://100.81.163.176:8088/`) and `/?cat=diurnal` for the 16 diurnal captures.
- **Non-Negotiable Architecture Rules**:
  - JDK 25, OpenGL 4.6 Core, GLSL `#version 460 core` (never downgrade).
  - Strictly retail-accurate rendering grounded in DATs / `references/vana-client` / `references/xim-source` (no made-up effects).
  - Keep `WorldScene.setEnableFog(boolean)` independent of orthographic projection.
  - ArchUnit enforces no AWT/LWJGL/JOML in `game-asset-pipeline`, and no `com.example.client`/ImGui/Netty in `com.ffxi.viewer`.
  - No `rg` binary installed on host — use `grep -rnE`.

---

## 5. Suggested Next Steps for the New Session

1. **Check PR / Merge Readiness of `006-cleanup-and-polish`**:
   - Review `origin/006-cleanup-and-polish` against `main`, update [`plan.md`](file:///home/samuel_l_hyman/c2/specs/006-cleanup-and-polish/plan.md) if desired to mark completion of the retail rendering pillars, and open/merge the PR when ready.
2. **Potential Follow-On Retail Rendering Features** (if continuing rendering polish):
   - Retail lens flares (`lf01`/`lf03`, depth-tested sky visibility as in `references/vana-client/.../render/flare.wgsl`).
   - Retail moon disc sprite (`0x0E` `moon` with 12-phase UV selection alongside `kasa` glow).
   - Retail weather transitions (3.33s fade between weather tags, roof coverage check stopping rain/snow under roofs).
   - Interactive in-client debug/UI controls for Vana'diel time override, weather tag selection, independent fog toggle, and actor shadow mode (`OFF` / `NORMAL` / `HIGH`).
