# Quickstart & Validation Guide: Desktop Client Baseline

**Feature**: `002-desktop-client`  
**Date**: 2026-09-06

---

## 1. Prerequisites

- **Java 25** with Virtual Threads enabled.
- **Maven 3.9+** (or repository `./mvnw`).
- Desktop environment with hardware or software OpenGL 4.6 (or virtual display server for automated tests).
- Running instance of the gateway and backend modulith (or Docker compose / k3d cluster).

---

## 2. Build & Automated Test Execution

Build the complete repository including the new `client-desktop` module:

```bash
# Build all modules and execute headless tests
./mvnw clean install -DskipTests=false
```

To run only the desktop client test suite:

```bash
./mvnw test -pl client-desktop
```

---

## 3. End-to-End Manual Verification Scenario

### Step 1: Start Backend Platform & Gateway Modulith
In a dedicated terminal:
```bash
./mvnw mn:run -pl platform-modulith
```
*Verify output*: Gateway listening on UDP/QUIC port `9000` (or configured gateway port).

### Step 2: Launch Desktop Client Application
In another terminal:
```bash
./mvnw exec:java -pl client-desktop -Dexec.mainClass="com.example.client.desktop.Launcher"
```
*Expected Outcome*: A desktop window opens (`1280x720`) with dark theme background, displaying the `Login` panel and `Debug Log` console.

### Step 3: Test Authentication
1. In the `Login` panel, enter credentials:
   - Username: `dev`
   - Password: `dev`
2. Click **Login**.
3. *Expected Outcome*:
   - Button briefly disables with "Connecting & Authenticating...".
   - State machine advances to `AuthenticatedState`.
   - `Character Selection` panel appears.
   - `Debug Log` shows `LOGIN_OK account=<accountId>`.

### Step 4: Test Character Creation Pipeline
1. In the `Character Selection` panel, click **Create Character**.
2. Test race and size constraints:
   - Select **Tarutaru Male** or **Tarutaru Female**: Size automatically locks to `Small (fixed)`.
   - Select **Galka**: Size automatically locks to `Large (fixed)`.
   - Select **Hume Male**: Size unlocks and permits selecting `Small`, `Medium`, or `Large`.
3. Test face selector:
   - Set Face to `1` through `8`, toggle `Variant B`.
4. Select Starting Job (`Warrior`) and Starting Nation (`San d'Oria`).
5. Enter a valid character name (e.g. `Valerius`) and click **Create**.
6. *Expected Outcome*:
   - Character creation succeeds.
   - Character card appears in roster: `Valerius  Hume Male  Lvl 1  Warrior  Zone: 0`.

### Step 5: Test Character Selection & World Entry
1. Click **Select** next to `Valerius`.
2. Notice the "Ready" indicator and click **Play**.
3. *Expected Outcome*:
   - State advances to `InGameState`.
   - `In Game` HUD opens displaying character name, session ID, and `KeepAlive: ok  RTT=...ms`.

### Step 6: Test Latency Monitoring & Session Return
1. Click **Ping Now** in the HUD: RTT latency updates immediately.
2. Click **Character Select**: Session returns smoothly to `Character Selection`.
3. Click **Sign Out**: Session resets to `Login` panel.
4. Close window or press `Escape`: Clean application exit with 0 error logs.
