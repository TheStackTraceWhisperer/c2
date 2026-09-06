# Research & Architectural Decisions: Desktop Client Baseline

**Feature**: `002-desktop-client`  
**Date**: 2026-09-06

---

## 1. UI Rendering Framework & Windowing Engine

### Context
The client needs a lightweight, cross-platform graphical desktop windowing system capable of rendering reactive UI panels (Login, Character Creation/Selection, In-Game HUD, Debug Console) with high frame rates, low latency, and zero HTML/Electron/heavy framework overhead.

### Decision
Harvest the proven stack from `reference/ffxi-java/catalyst/client/engine`:
- **LWJGL 3.4.2** (Core, GLFW, OpenGL).
- **Dear ImGui 1.86.11** via `io.github.spair:imgui-java-binding` + `io.github.spair:imgui-java-lwjgl3`.
- Modern OpenGL 4.6 context (`#version 460`).

### Rationale
- Native performance, low memory footprint (< 60 MB heap), and 60+ FPS immediate-mode UI rendering.
- Code is already written, battle-tested, and preserved in `reference/ffxi-java/catalyst/client/engine`:
  - `GlfwContextService`: Handles GLFW lifecycle, error callbacks, and thread termination.
  - `WindowService`: Handles window creation, resizing, vsync, and double buffering.
  - `ImGuiService`: Integrates Dear ImGui with GLFW input and OpenGL 3/4 shaders.
  - `FrameTimeService`: High-resolution delta time and FPS tracking.

### Alternatives Considered
- *JavaFX*: Heavy runtime footprint, complex styling pipeline, slower than immediate-mode Dear ImGui for game tools.
- *Swing/AWT*: Obsolete graphics pipeline, poor modern HiDPI/game integration, dated aesthetics.
- *Web/Electron*: Hundreds of megabytes of overhead, excessive CPU/RAM usage, high latency for game HUDs.

---

## 2. Main-Thread Concurrency & Thread-Affinity Bridge

### Context
GLFW, OpenGL contexts, and Dear ImGui require all graphical rendering, input polling, and buffer swapping to occur strictly on the operating system main display thread (calling GLFW/OpenGL functions from background threads causes fatal SIGSEGV or thread-affinity assertions). Conversely, Netty QUIC networking in `client-sdk` executes packet processing and callbacks asynchronously on Netty `EventLoopGroup` worker threads.

### Decision
Harvest `TaskSchedulerService` from `reference/ffxi-java/catalyst/common/concurrency`:
- Background asynchronous tasks (network dispatch, IO) execute on Java 25 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- Completion callbacks destined for the UI are queued into a concurrent `foregroundQueue` (`ConcurrentLinkedQueue<Runnable>`).
- Inside `Engine.tick()`, `taskScheduler.processForegroundTasks()` drains all queued UI callbacks on the main render thread before running `service.update()`.

### Rationale
- Completely eliminates lock contention between the graphics render loop and Netty event loops.
- Virtual threads provide instant, non-blocking asynchronous execution without carrier thread exhaustion.
- Preserves responsive UI frame rates even during network bursts or latency spikes.

### Alternatives Considered
- *Direct Synchronization on `ClientState`*: Causes UI stutter and lock contention if Netty threads block while the render thread is painting.
- *RxJava / Project Reactor*: Unnecessary dependency bloat and GC allocation pressure compared to a lean `ConcurrentLinkedQueue` drain loop.

---

## 3. Network Integration with `client-sdk`

### Context
`ffxi-java` used an obsolete `ClientTransport` that spawned bidirectional QUIC streams per packet, causing stream lifecycle leaks. In `netty-quic-gateway-demo`, we have `client-sdk` featuring `QuicClient`, `ClientCallback`, `KeepaliveTask`, and structured binary packet codecs.

### Decision
Create a clean adapter `QuicClientNetworkAdapter` inside `client-desktop` that:
1. Implements `ClientCallback` from `client-sdk`.
2. Forwards network events (`onPacketReceived`, `onDisconnected`, `onConnected`) directly into `taskScheduler.runOnMainThread(...)`.
3. Injects `QuicClient` to send typed wire packets asynchronously (`sendLogin`, `sendCharacterList`, `sendCharacterCreate`, `sendCharacterSelect`, `sendPlay`, `sendLogout`, `sendPing`).

### Rationale
- Honors Constitution Principle I (Client SDK Separation) by treating `client-sdk` as an external library.
- Zero network transport reimplementation in `client-desktop`.
- Preserves all defensive severing and session integrity mechanisms built in Feature 001.

### Alternatives Considered
- *Embedding Netty QUIC directly in `client-desktop`*: Violates DRY and modular isolation; bypasses `client-sdk`.

---

## 4. Cross-Platform Maven Natives & Headless CI Strategy

### Context
LWJGL 3 and Dear ImGui require native shared libraries (`.so`, `.dylib`, `.dll`). Automated CI environments and headless unit tests do not possess a physical display server (X11/Wayland), which would cause `glfwInit()` to fail during automated builds.

### Decision
1. **Maven Native Classifiers**:
   - Declare native classifiers for Linux (`natives-linux`, `linux-x86_64`), macOS (`natives-macos`, `macos-x86_64`, `macos-arm64`), and Windows (`natives-windows`, `windows-x86_64`).
2. **Headless Test Guard**:
   - In `GlfwContextService` and unit tests, verify whether a graphical display is available (`GLFW.glfwInit()` success or headless system property `java.awt.headless=true` / environment variable check).
   - Core domain logic, UI panels, state machines, and task schedulers are decoupled from the physical window and can be unit-tested headlessly without opening an OS window.
   - The full interactive desktop loop runs when invoked explicitly via `ClientDesktopApplication.main()`.

### Rationale
- Guarantees `mvn clean test` and GitHub Actions CI pass cleanly without requiring a virtual framebuffer or failing on headless test nodes.

---

## 5. Domain Fidelity & Wire Protocol Alignment

### Context
The character creation form requires:
- 8 races, race-locked sizes (Tarutaru=0, Galka=2, others 0-2), 16 face permutations (1-8 with A/B variant), 6 starting jobs, 3 starting nations.

### Decision
- Update `common-protocol` records (`CharacterCreateRequest`) and wire registry to carry:
  `String name, int raceId, int sizeId, int faceId, int jobId, int nationId`.
- Update `CharacterSummary` to carry:
  `long characterId, String name, int raceId, int mainJobId, int mainJobLevel, int zoneId`.
- Add canonical domain enums in `common-protocol` (`RaceId`, `JobId`, `NationId`) so both `client-desktop` and `platform-modulith` share typed constants.

### Rationale
- Ensures 100% domain parity with `CharacterPanel.java` and `001-schema.sql` without cutting corners.
