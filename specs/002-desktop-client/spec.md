# Feature Specification: Desktop Client Baseline & Character Creation Pipeline

**Feature Branch**: `002-desktop-client`

**Created**: 2026-09-06

**Status**: Draft

**Input**: User description: "Harvest and integrate the desktop client presentation layer, Dear ImGui UI panels, reactive state machine, and complete character creation pipeline from ffxi-java into netty-quic-gateway-demo while adhering to clean networking architecture and constitutional standards."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Desktop Authentication & Gateway Connection (Priority: P1)

As a player launching the game desktop client, I want to view a login screen where I can enter my account credentials and connect to the game gateway, so that I can authenticate my account and transition to character management.

**Why this priority**: Authentication is the mandatory entry gateway for any user session; without successful desktop connection and credential submission, no downstream character or world interactions can occur.

**Independent Test**: Can be tested independently by launching the desktop application, submitting credentials through the login panel against a running gateway, and verifying that successful authentication transitions the interface to the character selection view, while invalid credentials display a clear, readable error message.

**Acceptance Scenarios**:

1. **Given** the desktop application is launched and connected to the gateway, **When** the user enters valid username and password and clicks "Login", **Then** the interface transitions to "Connecting & Authenticating..." and subsequently loads the Character Selection screen.
2. **Given** the login panel is active, **When** the user submits invalid credentials, **Then** the screen displays a prominent error banner indicating authentication failure, and the login button remains available for retry.
3. **Given** the gateway is unreachable, **When** the user attempts to log in, **Then** a descriptive connection error is displayed without crashing the desktop application.

---

### User Story 2 - Comprehensive Character Creation & Customization (Priority: P1)

As an authenticated player, I want to create a new character using the full Final Fantasy XI customization options—including race, size constraints, facial styling variants, starting job, and allegiance nation—so that my character accurately reflects authentic game rules.

**Why this priority**: Character creation is the core creative pipeline for players and embodies high-value domain logic that must be preserved with 100% fidelity.

**Independent Test**: Can be tested independently on the character screen by opening the creation form, selecting various race and gender combinations, verifying that race-specific size locking is enforced, toggling facial style variants, choosing starting jobs and nations, and submitting the creation request.

**Acceptance Scenarios**:

1. **Given** the user is on the character screen and clicks "Create Character", **When** the creation form opens, **Then** options for Name, Race, Size, Face, Starting Job, and Starting Nation are presented.
2. **Given** the user selects the "Tarutaru" race (Male or Female), **When** evaluating the size option, **Then** the size is automatically locked to "Small (fixed)" and cannot be changed to Medium or Large.
3. **Given** the user selects the "Galka" race, **When** evaluating the size option, **Then** the size is automatically locked to "Large (fixed)".
4. **Given** the user selects "Hume", "Elvaan", or "Mithra", **When** selecting size, **Then** the user can freely choose between Small, Medium, and Large.
5. **Given** the user is customizing face options, **When** adjusting the face number (1–8) and toggling the secondary hair/style variant option, **Then** the selection encodes one of the 16 unique facial variations (0–15).
6. **Given** the user selects from the 6 starting jobs (Warrior, Monk, White Mage, Black Mage, Red Mage, Thief) and 3 starting nations (San d'Oria, Bastok, Windurst), **When** submitting a valid unique character name, **Then** the character creation request is dispatched and, upon success, the new character appears in the character roster.

---

### User Story 3 - Character Roster Management & World Entry (Priority: P2)

As an authenticated player with existing characters, I want to view my character roster displaying each character's name, race, primary job, level, and current zone, select a character to enter the world, or delete an unwanted character, so that I can manage my adventures.

**Why this priority**: Roster selection and world entry transition players from lobby management into active gameplay.

**Independent Test**: Can be tested independently by logging in with an account having existing characters, verifying that all character summary metadata renders accurately, selecting a character, clicking "Play", and verifying transition to the in-world state.

**Acceptance Scenarios**:

1. **Given** an authenticated session, **When** the character list loads, **Then** all characters belonging to the account are displayed with their Name, Race label, Main Job label, Level, and Current Zone ID.
2. **Given** a displayed character, **When** the user clicks "Select", **Then** the character is highlighted as active, and a "Play" button becomes enabled.
3. **Given** an active character selection, **When** the user clicks "Play", **Then** the application dispatches a world entry request and transitions into the In-Game state upon confirmation.
4. **Given** a displayed character, **When** the user clicks "Delete", **Then** a deletion request is processed, removing the character from the active roster.
5. **Given** the character screen is active, **When** the user clicks "Sign Out", **Then** the session terminates and returns to the initial Login screen.

---

### User Story 4 - In-World Session HUD & Real-Time Latency Monitoring (Priority: P2)

As a player active in the game world, I want to see an in-game HUD displaying my character name, session status, live keepalive heartbeat health, and round-trip network latency (RTT in milliseconds), with controls to ping or return to character select, so that I have complete visibility over connection quality.

**Why this priority**: In-world presence requires continuous connection monitoring and clean controls to exit or re-select characters.

**Independent Test**: Can be tested independently by entering the in-game state, observing live RTT latency figures updating continuously, clicking "Ping Now" for an immediate latency probe, and using "Character Select" or "Logout" to gracefully exit.

**Acceptance Scenarios**:

1. **Given** the player has entered the world, **When** the In-Game HUD renders, **Then** it shows the character name, session identifier, and live connection status ("connected" or "ok").
2. **Given** an active world session, **When** periodic background keepalive pings are exchanged, **Then** the HUD displays the latest round-trip latency in milliseconds (e.g., "RTT=12ms").
3. **Given** an active world session, **When** the user clicks "Ping Now", **Then** an immediate ping probe is dispatched and the latency measurement updates promptly.
4. **Given** an active world session, **When** the user clicks "Character Select", **Then** the in-world session closes gracefully and the interface returns to the Character Selection screen.
5. **Given** an active world session, **When** the user clicks "Logout", **Then** the world session terminates and returns to the Login screen.

---

### User Story 5 - Live Rolling Debug Console & Session Diagnostics (Priority: P3)

As a developer or player diagnosing connection or game events, I want an on-screen scrolling debug log window that records network events, state transitions, and server responses with timestamps and auto-scroll, so that I can troubleshoot issues during testing.

**Why this priority**: Crucial for diagnostics and developmental observability, but secondary to the core gameplay loop.

**Independent Test**: Can be tested by triggering various client actions (login, character select, ping) and verifying that the debug console captures and renders chronological event logs with auto-scroll behavior.

**Acceptance Scenarios**:

1. **Given** any desktop client screen, **When** network events, packet deliveries, or state changes occur, **Then** formatted log entries with timestamps are appended to the debug log window.
2. **Given** multiple log entries exceeding the window height, **When** new entries are added, **Then** the window automatically scrolls to the latest message unless the user has scrolled up.

---

### Edge Cases

- **Gateway Disconnection**: If the network connection to the gateway is abruptly dropped while on the character screen or in-world, the HUD must signal a disconnected status and provide a clear path back to the login screen.
- **Duplicate Character Name**: If a character creation request uses a name already taken by another player, the creation form remains open, the entered fields are preserved, and an error message ("Character name already in use") is displayed.
- **Concurrent/Spam Clicks**: Clicking "Login", "Create", or "Play" multiple times in rapid succession must disable the action trigger until the pending network request resolves or times out.
- **Headless & Off-Screen Execution**: When executed in automated test runners or environments without a physical display server, the client engine must detect the environment and handle it gracefully without crashing test suites.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The client MUST manage the desktop application lifecycle including window creation, input polling, frame timing updates, and clean OS termination.
- **FR-002**: The client MUST render graphical user interface panels overlaid onto the application display viewport.
- **FR-003**: The client MUST execute all UI rendering, input handling, and window buffer swapping strictly on the primary display thread.
- **FR-004**: The client MUST provide an asynchronous task scheduler that offloads network communication to background tasks while safely queueing completion callbacks to be executed on the display thread during frame updates.
- **FR-005**: The client MUST maintain a deterministic state machine transitioning across distinct application phases: Unauthenticated (Login), Authenticated (Character Roster), Character Selected, and In-Game.
- **FR-006**: The Login screen MUST accept a username and password, display connection status, and communicate with the game gateway.
- **FR-007**: The Character Selection screen MUST render a roster of all active characters associated with the authenticated account, showing character name, race, primary job, level, and current zone ID.
- **FR-008**: The Character Creation form MUST support all 8 canonical player race/gender archetypes, transmitting `raceId` and `gender` as distinct, separate attributes on the network wire.
- **FR-009**: The Character Creation form MUST enforce race-specific size constraints: Tarutaru (races 5 and 6) MUST be locked to Small (0); Galka (race 8) MUST be locked to Large (2); all other races MUST permit selection of Small (0), Medium (1), or Large (2).
- **FR-010**: The Character Creation form MUST provide 8 base facial selections and a secondary style/hair variant toggle, producing a deterministic face index in the range 0 to 15.
- **FR-011**: The Character Creation form MUST permit selection among the 6 starting jobs: Warrior (1), Monk (2), White Mage (3), Black Mage (4), Red Mage (5), and Thief (6).
- **FR-012**: The Character Creation form MUST permit selection among the 3 starting nations: San d'Oria (0), Bastok (1), and Windurst (2).
- **FR-013**: The client MUST validate that the character name is between 3 and 16 characters in length and contains only alphabetic characters.
- **FR-014**: The client MUST permit deleting an existing character with immediate roster update upon server confirmation.
- **FR-015**: The client MUST permit selecting a character from the roster and dispatching a world play request to enter the designated zone.
- **FR-016**: The In-Game HUD MUST display the active character name, current session identity, and keepalive status.
- **FR-017**: The In-Game HUD MUST display real-time round-trip latency (RTT in milliseconds) updated from periodic keepalive exchanges.
- **FR-018**: The In-Game HUD MUST provide a manual "Ping Now" action that immediately measures network latency.
- **FR-019**: The In-Game HUD MUST provide actions to return to Character Selection or perform a complete account logout.
- **FR-020**: The client MUST provide a floating, collapsible Debug Log window displaying chronological events with timestamps and auto-scrolling capabilities.
- **FR-021**: The client MUST communicate with the server backend exclusively through the project's standardized client transport, adhering strictly to shared wire protocols without bypasses.

### Key Entities

- **Account Credentials**: Username and password combination used to establish authenticated identity.
- **Character Summary**: The lightweight player identity record comprising character identity, name, world identity, race identity, gender identity, main job, job level, sub job, nation identity, and zone identity.
- **Character Creation Payload**: Comprehensive data required to instantiate a new character: session authentication token, target world identity, name, race identity, gender identity, size identity, facial style identity, hair style identity, starting job identity, and starting nation identity.
- **Session Context**: The active client session state tracking authenticated account identity, selected character identity, bound world address, and current zone.
- **Keepalive Telemetry**: Network health metrics including current connection status, last measured round-trip time (RTT in ms), and timestamp of the last successful heartbeat.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The desktop client initializes its window, graphics context, and UI subsystem and displays the login screen in under 2.0 seconds from launch.
- **SC-002**: 100% of the character creation domain rules (8 races, race-locked sizes, 16 face permutations, 6 starting jobs, 3 starting nations) are strictly validated before dispatch.
- **SC-003**: Background network events and responses are dispatched and processed on the main UI thread with zero graphical frame freezing or frame drops.
- **SC-004**: Real-time round-trip latency (RTT) displayed in the in-game HUD accurately reflects heartbeat response timings within a 5ms margin of error.
- **SC-005**: All client actions (Login, Create, Select, Delete, Play, Logout) complete their round-trip UI state transition in under 500 milliseconds under standard local network conditions.
- **SC-006**: The desktop application shuts down cleanly upon window closure or logout, terminating all background tasks and releasing OS window resources with zero resource leaks.

## Assumptions

- The target runtime environment is a desktop machine running Linux, macOS, or Windows with hardware or software graphics acceleration support.
- 3D scene rendering, DAT asset decoding, skeletal animations, and spatial collision simulation are explicitly deferred to subsequent client engine feature iterations.
- Network communication connects through the project's standard gateway proxy.
- The backend data stores and domain services support the full character creation parameters (race, size, face, job, nation) defined in the domain schema.
