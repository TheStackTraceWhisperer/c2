# Implementation Plan: Core Convergence, Real OpenGL 4.6 Verification & Cleanup (`006-cleanup-and-polish`)

**Branch**: `006-cleanup-and-polish` | **Baseline Commit**: `00d92fd` | **Date**: `2026-11-21`

## 1. North-Star Invariants & Environment Baseline

- **Toolchain & Graphics Target**: **JDK 25**, **OpenGL 4.6 Core**, and **GLSL `#version 460 core`** — no backleveling to Java 21 or OpenGL 3.x.
- **Primary Build Workflow**: Native host builds via `./mvnw clean verify` (not Docker/GitHub Actions).
- **UI Architecture**: Dear ImGui stays for developer/inspector/utility panels (`DebugLogPanel`, `InGamePanel`, `WorldRadarPanel`, `InGameMapPanel`, `ffxi-tools`); `FontMojiRenderer` / `FontMojiGlRenderer` owns authentic in-scene and in-game player typography.
- **No Fabricated Test Renderers**: Tests and tools must exercise core production rendering (`WorldScene`, `ZoneShader`, `CharacterShader`, `ShadowMapRenderer`, `SsaoRenderer`, `AuthenticSkyDome`, `WeatherParticleSystem`, `FontMojiGlRenderer`, `InGameHudOverlay`, `ScreenshotCapture`) rather than maintaining parallel CPU rasterizers or one-off engine loops.
- **Local Reference Implementations (Git-Ignored)**: `references/server` (LandSandBoat), `references/vana-client` (Rust), and `references/xim-source` (XIM) are present locally under `references/` for behavior verification.

---

## 2. Already Completed on `006-cleanup-and-polish`

- **`FontMojiGlRenderer` GPU Integration** (`client-desktop`): Added `GlyphQuad` layout and RGBA atlas export in `FontMojiRenderer`, implemented batched OpenGL `FontMojiGlRenderer`, wired lifecycle into `WorldScene`, and upgraded `InGameHudOverlay` to render scaled actor nameplates (`feetOffsetY * 2.0f * effectiveScale() + 0.35f`) and zoneline prompts in-scene with clean ImGui fallback.
- **Capture Utilities Wired** (`client-desktop`): Initialized `RenderDocCapture` in `GlfwContextService.start()`; added `Screenshot [F11]` and `RenderDoc [F12]` intents to `DebugLogPanel` and hotkeys/triggers in `InGameState`.
- **Tool Exporter Relocated** (`ffxi-tools`): Moved `DatResourceGraphJsonExporter` out of `game-asset-pipeline` into `com.ffxi.viewer.debug`, leaving `game-asset-pipeline` with zero tool-only exporters (Tier 1 detached production classes reduced from 4 to 0).
- **Gallery Server Reports** (`scripts/serve-snapshots.py`): Added `target/reports` HTML report serving and direct Table of Contents links (`/?report=detached-classes-report.html`).

---

## 3. Phase 1 — Replace Fabricated CPU Rasterizers with Real OpenGL 4.6 Core Snapshots

1. **Enable Headless OpenGL 4.6 Core on Host**:
   - Install `xvfb` on Debian 13 (Mesa 25.0.7 `libgl1-mesa-dri` / `swrast_dri.so` is already installed).
   - Provide a lightweight host Xvfb + Mesa wrapper (`scripts/with-xvfb.sh`, modeled on the `Xvfb :99` socket-polling, `XDG_RUNTIME_DIR`, `xkb/symbols/inet`, and Mesa override pattern) setting:
     - `MESA_GL_VERSION_OVERRIDE=4.6`
     - `MESA_GLSL_VERSION_OVERRIDE=460`
     - `LIBGL_ALWAYS_SOFTWARE=1`
   - Add a reusable headless hidden GLFW 4.6 Core context test helper so `./mvnw clean verify` under Xvfb exercises real OpenGL 4.6 without creating visible windows.
2. **Align All Shaders to `#version 460 core`**:
   - Bump `ZoneShader`, `CharacterShader`, `ShadowMapRenderer`, `SsaoRenderer`, `AuthenticSkyDome`, `WeatherParticleSystem`, and `FontMojiGlRenderer` in `client-desktop` from `#version 330 core` to `#version 460 core` (matching `WindowService`, `ImGuiService`, and `CollisionMeshRenderer`).
3. **Migrate 3D Snapshot Suites onto `WorldScene.captureFrame(...)`**:
   - Migrate `LiveServerClientSnapshotIT` first so E2E gallery images render directly through `WorldScene` + `InGameHudOverlay` + `FontMojiGlRenderer` + `ScreenshotCapture` (verifying authentic GLSL shaders, shadows, SSAO, sky dome, actor scaling, and FontMoji nameplates end-to-end).
   - Migrate the remaining 6 3D snapshot suites (`TownWalkthroughCameraSnapshotSuiteTest`, `ThirdPersonCameraSnapshotSuiteTest`, `ZoneRenderingSnapshotSuiteTest`, `CharacterSnapshotSuiteTest`, `CharacterAnimationSnapshotSuiteTest`, `NpcAndMonsterSnapshotSuiteTest`) onto `WorldScene` / `AuthenticCharacterInstance` / `ZoneShader` / `CharacterShader` offscreen FBO rendering.
   - Delete `SnapshotCharacterTestHelper` and all per-test `rasterizeTriangle(...)` / `rasterizeZoneTriangle(...)` CPU loops once migrated.

---

## 4. Phase 2 — Rebuild `ffxi-tools` on Core Primitives

- Upgrade `ZoneViewer`, `CharacterViewer`, `MapShaderSandbox`, `GlbRenderer`, and `SandboxMapShader` from GLFW 3.3 / `#version 330` to **OpenGL 4.6 Core / `#version 460 core`**.
- Replace duplicate `com.ffxi.viewer.TaskScheduler` in `ffxi-tools` with `client-desktop`'s `TaskSchedulerService` and delete the duplicate class.
- Apply `LoadedCharacter.effectiveScale()` in `CharacterViewer` (closing the `0x45` size-scaling gap in standalone tool previews).
- Converge standalone viewer scene/camera loops onto `WorldScene` and `SceneCamera` where practical.

---

## 5. Phase 3 — Core Runtime & Subsystem Consolidation

- **Unify `PolarCamera` and `SceneCamera`**: Remove per-frame field copying between `PolarCamera` and `SceneCamera` in `InGameState` / `WorldScene` by sharing one `Camera` instance (retaining `CameraMode.CUTSCENE` support).
- **Wire Client Collision Queries**: Integrate `ZoneCollisionSystem.findFloorY(...)` into client-side movement prediction (`MovementInputHandler` / `InGameState`) and camera wall-collision clamping.
- **Cache Lifecycle & Frustum/Bounds Integration**:
  - Add eviction/cleanup hooks (or LRU cap) to `CharacterAssetCache` on zone transitions to prevent unbounded VRAM growth.
  - Evaluate consuming parsed `DatParser` metadata: `ZoneSpacePartitionNode` / `ZoneSpacePartitionBounds` for `WorldScene` frustum culling, and `SkeletonBoundingBox` / `SkeletonJointReference` for exact nameplate/attachment anchoring.
- **Dormant Gameplay & Dual Collision Stacks**:
  - Seed initial patrol routes into `NpcPatrolSimulation` via `ZoneSimulationEngine` or document/gate until combat/AI milestones.
  - Plan unification of `game-asset-pipeline`'s dual `com.example.asset.collision.*` (`ZoneDefParser`) and `com.ffxi.model.ZoneCollision*` (`DatParser`) stacks.
