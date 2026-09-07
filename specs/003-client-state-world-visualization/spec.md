# Feature Specification: Canonical ClientState Unification & World Visualization

**Feature Branch**: `003-client-state-world-visualization`

**Created**: 2026-09-07

**Status**: Draft

**Input**: User description: "Consolidate duplicate ClientState into client-sdk to adhere to SDD architectural boundaries, and add client-side world visualization for in-game presence and surrounding entities."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Single Canonical Client State (Priority: P1)

As a developer and client application operator, I want all client connection, authentication, character roster, spatial coordinates, and network telemetry to reside in a single authoritative client state model, so that there is no duplicate caching, state desynchronization, or architectural ambiguity between client subsystems.

**Why this priority**: Eliminates dual-state caching bugs, ensures UI panels and network handlers observe the exact same state updates, and strictly aligns the client application with clean architecture standards.

**Independent Test**: Can be tested independently by running client authentication, character creation, and world entry, verifying that all UI panels read directly from the authoritative client state, and ensuring state mutations (connection changes, roster updates, latency measurements) propagate reactively across the entire application without redundant parallel stores.

**Acceptance Scenarios**:

1. **Given** the client application is running, **When** examining the client architecture, **Then** only one authoritative client state model exists, shared seamlessly across network adapters and presentation panels.
2. **Given** an authentication response, character list update, or gateway error arrives from the network, **When** the authoritative state updates, **Then** all observing UI panels reflect the update immediately on the next render frame.
3. **Given** a user logs out or disconnects, **When** the state reset occurs, **Then** all cached credentials, character references, and world entity snapshots are cleared from the single state store in a single atomic operation.

---

### User Story 2 - In-World Spatial Radar & Entity Visualization (Priority: P1)

As an active player in the game world, I want to see a real-time spatial visualization (radar viewport) of my surroundings, displaying my character's position and orientation alongside nearby players and entities within my Area of Interest (AoI), so that I can visually perceive the game world rather than staring at a blank screen.

**Why this priority**: Transforms in-world presence from a static text HUD into a living, visual game experience that reflects active zone simulation and multi-entity presence.

**Independent Test**: Can be tested independently by logging into an active zone shard, opening the world visualization viewport, and verifying that the player's position is accurately rendered at the viewport center with a heading indicator, and that any spawned entities or simulated NPCs appear as distinct markers with nameplates and job/level details.

**Acceptance Scenarios**:

1. **Given** the player has entered the game world, **When** the in-game screen renders, **Then** a dedicated spatial visualization viewport displays the local terrain grid, spatial axes, and the player character positioned at their current coordinates.
2. **Given** the player character is positioned in the world, **When** viewing the player marker, **Then** it clearly indicates the character's heading/facing angle (yaw).
3. **Given** other entities (players or NPCs) are present in the player's Area of Interest, **When** spatial delta updates arrive from the zone service, **Then** those entities appear dynamically on the radar viewport with distinct visual markers, character names, and job/level identifiers.
4. **Given** an entity moves within the Area of Interest, **When** movement updates are received, **Then** the entity's visual marker moves smoothly across the visualization viewport to its new coordinates.
5. **Given** an entity moves out of the Area of Interest or despawns, **When** a despawn notice is processed, **Then** the entity marker is immediately removed from the visualization viewport.

---

### User Story 3 - Interactive Movement & Position Synchronization (Priority: P2)

As a player in the game world, I want to move my character forward, backward, and rotate using standard directional controls (WASD / arrow keys), with my new coordinates sent to the server and reflected smoothly on my screen, so that I can explore the zone.

**Why this priority**: Completes the core interactive loop of an MMO client by enabling self-directed spatial movement and position synchronization with the zone simulation engine.

**Independent Test**: Can be tested independently by pressing movement keys while in-world, verifying that local coordinates update continuously, movement request packets are transmitted to the gateway, and authoritative server acknowledgments reconcile the player's position without rubber-banding under normal conditions.

**Acceptance Scenarios**:

1. **Given** the player is in-world with the visualization active, **When** pressing forward/backward/strafe keys (W/S/A/D) or turning, **Then** the character's coordinates and facing angle update responsively on the client display.
2. **Given** active player movement, **When** movement occurs, **Then** movement update packets containing timestamp, target position, velocity, and facing angle are dispatched to the server at regular tick intervals.
3. **Given** the server acknowledges or corrects player position, **When** server position updates are received, **Then** the client reconciles local position with the authoritative coordinates smoothly.

---

### User Story 4 - Zone Telemetry & Spatial Viewport Controls (Priority: P3)

As a player or developer inspecting world state, I want to view detailed zone metadata (zone name, zone ID, active instance shard) and adjust viewport zoom and visual overlays, so that I can examine entity density and spatial boundaries effectively.

**Why this priority**: Enhances usability, testing, and debugging by providing clear contextual awareness and adjustable camera magnification for different zone scales.

**Independent Test**: Can be tested by toggling zoom controls (zoom in, zoom out, reset) on the radar viewport, toggling coordinate grid overlays, and verifying zone name and instance metrics render correctly.

**Acceptance Scenarios**:

1. **Given** the in-world visualization screen, **When** viewing zone telemetry, **Then** the current Zone Name (e.g., "West Ronfaure", "South San d'Oria"), Zone ID, World ID, and current coordinate readouts (X, Y, Z, Yaw) are prominently displayed.
2. **Given** the radar viewport is displayed, **When** the user clicks zoom in or zoom out controls, **Then** the spatial scale adjusts smoothly to display a wider or narrower radius of the surrounding area.
3. **Given** multiple entities are within the Area of Interest, **When** inspecting the entities overlay, **Then** an active entity counter displays the total count of tracked entities in range.

---

### Edge Cases

- **Zone Boundaries & Void Movement**: If a player attempts to move beyond valid zone extents, the client visualizer bounds the cursor and honors server boundary clamps without crashing or rendering invalid coordinates.
- **Zero Surrounding Entities**: When entering an empty zone or shard with no other active players or NPCs, the radar displays a clean grid with only the local player marker and indicates "No remote entities in range".
- **Rapid Entity Spawn/Despawn Bursts**: Under high entity churn (e.g. crossing instance thresholds or crowded gateways), entity blips must add and remove cleanly without UI lag, dangling markers, or concurrent modification errors.
- **Clock Skew on Movement Ticks**: If the client's local system clock drifts relative to the server tick timeline, movement interpolation must clamp elapsed time deltas to prevent exponential position jumps or visual glitches.
- **Window Resizing During World Visualization**: If the user resizes or maximizes the desktop application window, the world radar viewport must scale its aspect ratio and keep the local player centered without distortion.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST maintain exactly one canonical client state model across the client codebase, housing all connection states, credentials, character rosters, spatial coordinates, and telemetry.
- **FR-002**: The presentation layer MUST read reactive state directly from the canonical client state model without maintaining parallel, duplicate state caches.
- **FR-003**: The presentation layer MUST separate state storage from network actions, dispatching outbound requests through dedicated network adapters while observing inbound events from the canonical state.
- **FR-004**: The client MUST render a real-time 2D spatial visualization (radar viewport) of the active zone whenever the player is in the in-world state.
- **FR-005**: The spatial visualization MUST render the local player marker at the center of the viewport, with a distinct visual heading indicator representing the current facing yaw angle.
- **FR-006**: The spatial visualization MUST render remote entities (other players and NPCs) located within the player's Area of Interest as distinct visual markers positioned relative to the local player.
- **FR-007**: Each remote entity marker in the spatial visualization MUST display relevant identity metadata, including character name, primary job abbreviation, and level.
- **FR-008**: The spatial visualization MUST smoothly update remote entity positions upon receiving spatial delta messages from the zone simulation service.
- **FR-009**: The spatial visualization MUST immediately remove remote entity markers when entities despawn or move beyond the Area of Interest radius.
- **FR-010**: The client MUST capture keyboard movement inputs (WASD / arrow keys) while the in-world viewport is focused, updating local character coordinates and facing angle.
- **FR-011**: The client MUST dispatch player movement packets to the zone service at regular intervals during movement, conveying coordinates, velocity, and facing direction.
- **FR-012**: The client MUST reconcile local player coordinates with authoritative server position updates, maintaining smooth visual motion.
- **FR-013**: The in-world interface MUST display real-time spatial telemetry including current Zone ID, canonical Zone Name, World ID, active entity count, and current coordinates (X, Y, Z, Yaw).
- **FR-014**: The spatial visualization MUST provide adjustable zoom controls allowing the player to scale the visible radar radius (e.g., 25m, 50m, 100m, 200m).
- **FR-015**: The spatial visualization MUST support toggling a coordinate grid overlay and Area of Interest boundary circle.
- **FR-016**: The client MUST handle abrupt network disconnection during world visualization by gracefully freezing visualization updates, displaying a disconnection banner, and offering clean navigation back to the login screen.

### Key Entities

- **Canonical Client State**: The single source of truth for client-side state, encapsulating connection lifecycle, authenticated player credentials, account character roster, active zone coordinates, latency telemetry, and remote entity registry.
- **Spatial Entity Record**: Representation of a dynamic game entity within the local player's Area of Interest, containing entity ID, name, class/job, level, spatial coordinates (X, Y, Z), velocity vector (VX, VY, VZ), and orientation (yaw, pitch).
- **Radar Viewport**: The visual representation surface rendering coordinate axes, range rings, entity markers, direction indicators, and tactical overlays for the player's immediate spatial surroundings.
- **Movement Input Intent**: Local player directional and rotational intent captured from input hardware, translating into velocity deltas and outbound wire movement packets.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Exactly zero duplicate state classes or redundant caching models exist across the client modules, verified by automated codebase analysis.
- **SC-002**: The spatial radar viewport renders smoothly at the display's target frame rate (60+ FPS) with zero frame stutter during continuous entity updates.
- **SC-003**: Inbound entity spawns, movements, and despawns reflect on the spatial radar within 1 frame (under 16ms) of packet receipt on the client main thread.
- **SC-004**: Keyboard movement inputs translate to responsive character position updates on screen with under 50ms perceptible input latency.
- **SC-005**: The spatial radar accurately plots entity positions relative to the local player with a spatial fidelity margin under 0.5 coordinate units.
- **SC-006**: Entity churn tests (simulating 50+ concurrent entity spawns/despawns per second) execute without memory leaks, visual artifacts, or UI thread freezes.

## Assumptions

- The initial world visualization is implemented as a high-performance 2D tactical spatial radar viewport with coordinate grid, range rings, heading indicators, and entity nameplates, providing immediate spatial awareness before 3D model/mesh rendering is introduced in subsequent milestones.
- Standard QWERTY directional keyboard controls (W: forward, S: backward, A: turn left / strafe, D: turn right / strafe) serve as the primary movement input scheme.
- Zone coordinate spaces follow authentic FFXI Cartesian standards (X: East-West, Y: Height/Altitude, Z: North-South).
- The zone simulation service continues broadcasting `ZoneStateDeltaMessage` packets governing entity spawns, movements, and despawns within the player's Area of Interest.
