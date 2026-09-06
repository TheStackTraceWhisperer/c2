# Contract: UI Panel Lifecycle & Intent Protocol

**Module**: `client-desktop`

---

## 1. Design Pattern: Stateless Render + Intent Polling

Every Dear ImGui panel follows an explicit, decoupled lifecycle pattern:
1. **Rendering**: The active `ApplicationState` calls `panel.render(clientState)` once per tick.
2. **Intent Polling**: User clicks inside the panel set boolean or value flags (`intents`) on the panel instance.
3. **Intent Consumption**: The `ApplicationState` inspects panel intents (e.g. `panel.isLoginRequested()`), invokes the appropriate `ClientState` method, and calls `panel.clearIntents()`.

This guarantees that ImGui widgets never invoke network calls or state machine transitions directly from within the immediate-mode rendering tree.

---

## 2. Panel Contracts

### 2.1 `LoginPanel`
- **Inputs**: `ClientState` (to read `lastErrorMessage` and `phase == AUTHENTICATING`)
- **Intents**:
  - `isLoginRequested()`: `boolean`
  - `getUsername()`: `String`
  - `getPassword()`: `String`
- **Method**: `clearIntents()`

### 2.2 `CharacterPanel`
- **Inputs**: `ClientState` (reads `characterList`, `selectedCharacterId`, `lastErrorMessage`)
- **Intents**:
  - `isCreateSubmitted()`: `boolean`
  - `isRefreshRequested()`: `boolean`
  - `isSignOutRequested()`: `boolean`
  - `getSelectCharacterId()`: `Long`
  - `getDeleteCharacterId()`: `Long`
  - `isPlayRequested()`: `boolean`
- **Creation Form Accessors**:
  - `getNewName()`: `String`
  - `getRaceId()`: `int` (1..8)
  - `getSizeId()`: `int` (0..2)
  - `getFaceId()`: `int` (0..15)
  - `getJobId()`: `int` (1..6)
  - `getNationId()`: `int` (0..2)
- **Methods**:
  - `clearIntents()`
  - `hideCreateForm()`

### 2.3 `InGamePanel`
- **Inputs**: `characterName`, `sessionId`, `keepAliveStatus`, `lastRttMs`
- **Intents**:
  - `isPingRequested()`: `boolean`
  - `isCharacterSelectRequested()`: `boolean`
  - `isLogoutRequested()`: `boolean`
- **Method**: `clearIntents()`

### 2.4 `DebugLogPanel`
- **Inputs**: Chronological log buffer (`List<LogEntry>`)
- **Behavior**: Renders floating console window; auto-scrolls to bottom on new entry unless user has scrolled up.
