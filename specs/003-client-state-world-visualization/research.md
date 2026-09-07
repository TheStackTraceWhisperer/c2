# Research: Canonical ClientState Unification & World Visualization

**Feature Branch**: `003-client-state-world-visualization`  
**Date**: 2026-09-07  
**Status**: Completed  

## 1. Context & Objectives

Feature 003 addresses two critical architectural needs:
1. **Single Canonical Client State**: Eliminate the duplicate `ClientState` in `client-desktop` by consolidating state tracking into `client-sdk`'s `com.example.client.state.ClientState`, strictly separating state representation from outbound network I/O per Spec-Driven Development (SDD) and clean architecture principles.
2. **In-World Spatial Radar & Player Character Visualization**: Implement a real-time, high-performance 2D tactical radar viewport inside the desktop client's in-game screen (`InGameState`) using Dear ImGui `ImDrawList`. The visualizer must display the local player marker with facing heading (yaw), remote player characters within the Area of Interest (AoI) with nameplates/job/level, support keyboard movement (WASD/arrows) with throttled network synchronization (20Hz), server position reconciliation, and viewport telemetry/controls.

---

## 2. Research Topics & Architectural Decisions

### Decision 1: Canonical `ClientState` Consolidation & Network Separation

- **Problem**: `client-sdk` already provided `com.example.client.state.ClientState` (tracking `connectionState`, `accountId`, `authToken`, `selectedCharacterId`, `currentWorldId`, `currentZoneId`, `x`, `y`, `z`, `lastServerTick`, and `remoteEntities`). During early UI development, a redundant copy was added to `client-desktop/src/main/java/com/example/client/desktop/net/ClientState.java` that also embedded outbound network methods (`sendLogin`, `sendCharacterCreate`, etc.) and duplicate character lists.
- **Decision**:
  1. Remove `com.example.client.desktop.net.ClientState` entirely.
  2. Enhance `com.example.client.state.ClientState` in `client-sdk` with:
     - Local player orientation: `yaw` and `pitch` (volatile floats).
     - Telemetry: `currentLatencyMs` (volatile long).
     - Thread-safe getters/setters and notification hooks for UI reactivity.
  3. Relocate all outbound network actions to `DesktopNetworkAdapter` (e.g., `sendLogin`, `sendCharacterList`, `sendCharacterCreate`, `sendCharacterDelete`, `sendCharacterSelect`, `sendPlay`, `sendMovement`, `sendPing`, `sendLogout`).
  4. Presentation layer components (`LoginPanel`, `CharacterPanel`, `InGamePanel`, `WorldRadarPanel`, and `ApplicationState` implementations) inject `ClientState` for state queries and `DesktopNetworkAdapter` for user-initiated actions.
- **Rationale**:
  - Aligns directly with Principle I (Modular Isolation) and FR-001/FR-002/FR-003.
  - Eliminates state desynchronization and dual-caching bugs.
  - Keeps `ClientState` pure and reactive while delegating I/O to the network adapter.
- **Alternatives Considered**:
  - *Keep a desktop-specific wrapper state class*: Rejected because it maintains unnecessary indirection and re-introduces the risk of duplicate cached fields.

---

### Decision 2: 2D Tactical Radar Viewport via Dear ImGui `ImDrawList`

- **Problem**: Players entering the game world previously saw only a basic status HUD with no visual awareness of their character or surroundings. A 3D mesh rendering pipeline (skeletal animation, terrain meshes, shaders) is planned for future milestones, but the client requires immediate, performant, and intuitive spatial awareness.
- **Decision**:
  - Implement `WorldRadarPanel` utilizing Dear ImGui's low-level 2D vector drawing API (`ImDrawList` accessed via `ImGui.getWindowDrawList()`).
  - Render features:
    - **Radar Canvas**: Dark circular/rectangular viewport background with boundary ring.
    - **Spatial Grid & Concentric Range Rings**: 25m, 50m, 100m, 200m range circles with distance labels in meters.
    - **Cardinal Axes**: Subtle North (+Z), South (-Z), East (+X), West (-X) axis lines.
    - **Local Player Marker**: Prominent central marker (bright green circle/chevron) with a directional vector indicating current facing yaw angle.
    - **Remote Player Markers**: Distinct blue/cyan circular markers positioned at their relative offset from the local player, accompanied by floating nameplates (Character Name, Job abbreviation, Level).
    - **Telemetry Overlay**: Top-left / bottom-left HUD showing Zone Name, Zone ID, World ID, tracked players count, and precise coordinates `(X, Y, Z, Yaw)`.
    - **Interactive Controls**: Zoom buttons (+ / -), zoom slider, grid toggle checkbox, and AoI boundary circle toggle.
- **Rationale**:
  - `ImDrawList` renders with zero additional OpenGL context switches or separate pipeline overhead. It executes on the existing ImGui rendering pass at 60+ FPS.
  - Provides instant tactical clarity and complete spatial debugging capability for developers and players alike.
- **Alternatives Considered**:
  - *Raw OpenGL 4.6 2D shader pipeline*: Rejected because it requires separate vertex buffer management, projection matrices, and font rendering shaders for nameplates, duplicating what ImGui already does seamlessly.

---

### Decision 3: Coordinate System & Projection Transformation

- **Problem**: Server simulation (`ZoneSimulationEngine`) uses FFXI Cartesian standards:
  - `X`: East (+X) / West (-X)
  - `Y`: Altitude / Height (+Y Up, -Y Down)
  - `Z`: North (+Z) / South (-Z)
  - `Yaw`: Rotation angle in radians/degrees (0 = North, $\pi/2$ = East, etc.)
  Screen coordinates in ImGui use:
  - Origin `(0, 0)` at top-left of the window/canvas
  - Screen X increases rightward
  - Screen Y increases downward
- **Decision**:
  - Local player is anchored at radar canvas center $(C_x, C_y)$.
  - Given local player world position $(X_{local}, Z_{local})$ and remote entity world position $(X_{remote}, Z_{remote})$:
    $$\Delta X = X_{remote} - X_{local}$$
    $$\Delta Z = Z_{remote} - Z_{local}$$
  - Let $S$ be the scale factor in pixels per world unit (e.g. at 100m radius on a 400px diameter radar, $S = 200 / 100 = 2.0\text{ px/unit}$).
  - In FFXI coordinates, $+Z$ is North (up on map), $+X$ is East (right on map).
  - Therefore, screen projection:
    $$P_x = C_x + (\Delta X \times S)$$
    $$P_y = C_y - (\Delta Z \times S)$$
  - Facing yaw: Drawn as a directional tick/arrow from the player marker at angle $\theta$ (aligned with FFXI yaw convention).
  - Out-of-bounds clamping: Remote entities beyond the visible radar radius can either be clipped or clamped to the perimeter with a small arrow indicator.
- **Rationale**: Standard top-down tactical radar projection preserving correct cardinal directions without confusing inversions.

---

### Decision 4: Keyboard Input & Movement Network Synchronization

- **Problem**: When a player moves using WASD or arrow keys, movement must be smooth locally (client-side responsiveness) while adhering to server tick rates without flooding the network.
- **Decision**:
  1. **Input Polling**: Capture W/S (forward/backward) and A/D (turn left/right or strafe) during `onUpdate(dt)` in `InGameState` when the radar/in-game viewport is focused.
  2. **Local Integration**:
     - Speed: e.g. 5.0 units/sec walking/running speed.
     - Rotation speed: e.g. $\pi$ radians/sec (180 deg/sec).
     - Update local coordinates $(X, Y, Z, Yaw)$ each frame based on delta time $dt$.
  3. **Throttled Network Dispatch**:
     - Do not send packets every 60 FPS frame.
     - Dispatch `PlayerMovementRequest` at a target frequency of **20 Hz** (every 50ms) matching the server tick rate, or immediately upon stopping movement (transition to idle).
     - `PlayerMovementRequest` payload: `x, y, z, vx, vy, vz, yaw, pitch, moveFlags, clientTick`.
  4. **Server Reconciliation**:
     - When `PlayerMovementResponse` arrives with `reconciled = true` or a significant position divergence (> 2.0 units), smoothly interpolate the local player position to the server authoritative coordinates to eliminate visual popping.
- **Rationale**:
  - Matches the 20 Hz simulation tick loop in `ZoneSimulationEngine`.
  - Ensures sub-50ms input responsiveness while preventing UDP packet flooding.
- **Alternatives Considered**:
  - *Full Lockstep Client Authoritative without Reconciliation*: Rejected; violates Constitution Principle V (server is authoritative for physics and spatial boundaries).

---

### Decision 5: Threading & Main-Thread ImGui Safety

- **Problem**: Inbound QUIC packets arrive on Netty event loop threads, while ImGui must only be read and updated on the main GLFW thread.
- **Decision**:
  - `ClientState` uses `ConcurrentHashMap` for `remoteEntities`, `CopyOnWriteArrayList` for collections, and `volatile` fields for coordinates/telemetry. This makes concurrent reads from the UI thread safe and non-blocking.
  - Inbound packet handlers in `DesktopNetworkAdapter` schedule state-updating and UI-notifying callbacks onto the main thread via `TaskScheduler.runOnMainThread(...)`.
  - State changes trigger `clientState.notifyListeners()`, allowing UI panels to react safely during the next frame's `render()` cycle.
- **Rationale**: Completely prevents GLFW/OpenGL threading violations and `ConcurrentModificationException`s during packet arrival bursts.

---

## 3. Summary of Design Decisions

| Aspect | Decision | Rationale |
|---|---|---|
| **Client State Architecture** | Consolidate into `client-sdk/.../ClientState.java`; delete `client-desktop/.../ClientState.java` | SDD compliance, single source of truth, eliminates state drift |
| **Network Dispatch** | Relocate outbound `send*` methods to `DesktopNetworkAdapter` | Clean separation of reactive state storage from active I/O transport |
| **World Visualization** | Dear ImGui `ImDrawList` tactical 2D radar viewport | High performance (60+ FPS), zero additional GL pipeline complexity, crisp 2D text & vectors |
| **Entity Scope** | Player characters exclusively (no NPCs/monsters) | Aligns strictly with backend `ZoneSimulationEngine` simulation support |
| **Movement & Sync** | WASD input + local integration + 20 Hz throttled `PlayerMovementRequest` | Low input latency with deterministic server synchronization |
| **Reconciliation** | Smooth client-side adjustment on authoritative server delta | Prevents rubber-banding while enforcing server boundary authority |
