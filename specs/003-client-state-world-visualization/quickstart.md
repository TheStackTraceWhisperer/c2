# Quickstart Validation Guide: ClientState Unification & World Visualization

**Feature Branch**: `003-client-state-world-visualization`  
**Date**: 2026-09-07  

This guide provides step-by-step instructions to validate the single canonical `ClientState` and the in-game 2D tactical world radar viewport.

---

## Prerequisites

1. **Java 25 JDK** installed and configured (`java -version`).
2. **Docker & K3d** running locally (if testing full cluster e2e).
3. **Maven Wrapper** (`./mvnw`).

---

## Scenario 1: Automated Verification (Unit, Architecture & Leak Tests)

Verify that duplicate `ClientState` classes have been eliminated and all client unit tests pass with clean architecture boundaries.

```bash
# 1. Run all unit and architectural fitness tests across modules
./mvnw clean test

# 2. Verify zero duplicate ClientState classes exist
git grep -l "class ClientState"
# Expected output: EXACTLY ONE match: client-sdk/src/main/java/com/example/client/state/ClientState.java
```

**Expected Outcome**:
- All unit and architecture tests pass across `common-protocol`, `platform-modulith`, `client-sdk`, and `client-desktop`.
- Only `client-sdk/.../ClientState.java` exists.

---

## Scenario 2: Launch Desktop Client & Inspect In-Game Tactical Radar

Validate interactive world visualization and movement in the desktop client application.

### 1. Launch Server Cluster or Local Stack
```bash
# Start the backend infrastructure and gateway cluster
./scripts/up-cluster.sh
```

### 2. Launch Desktop Client
```bash
# Run the LWJGL/ImGui desktop client launcher
./scripts/launch-client.sh
```

### 3. Execution Steps
1. **Login**: Authenticate with username `admin` and password `password`.
2. **Character Select**: Select character (e.g. `ElvaanKnight`) and click **Play**.
3. **In-World Radar Viewport**:
   - Verify the **World Radar** viewport is rendered on screen.
   - Observe the local player marker positioned at the center $(C_x, C_y)$ with a bright green chevron/arrow pointing in the character's facing yaw direction.
   - Verify coordinate telemetry in the radar HUD: `Zone ID`, `X`, `Y`, `Z`, `Yaw`.
   - Verify radar range rings (25m, 50m, 100m) and coordinate grid.
4. **Interactive Movement (WASD)**:
   - Press **W** to walk forward; observe the player coordinate updates and smooth radar displacement.
   - Press **A** / **D** to turn or strafe; observe yaw rotation and heading vector adjustments.
   - Release keys; verify movement ceases smoothly without jitter.
5. **Radar Controls**:
   - Click `[+]` and `[-]` zoom buttons to toggle between 25m, 50m, 100m, and 200m range views.
   - Toggle checkboxes: `Show Grid`, `Show AoI (50m)`, `Show Nameplates`.

---

## Scenario 3: Multi-Player Spatial AoI Observation

Validate that remote player characters appear dynamically on the radar viewport when multiple clients enter the same Area of Interest.

### 1. Run Multi-Player AoI Integration Test
```bash
# Run headless multi-client simulation verifying AoI delta streaming
./mvnw test -pl platform-modulith -Dtest=ZoneSimulationIT#testMultiplayerSpatialAoIBroadcast
```

**Expected Outcome**:
- Observer client receives `ZoneStateDeltaMessage` broadcasts.
- Remote player character marker appears in `clientState.getRemoteEntities()` with character name, job, level, and position within 16ms of packet arrival.
