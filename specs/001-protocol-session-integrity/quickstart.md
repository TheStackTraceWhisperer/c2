# Quickstart & Verification Guide: Protocol Synchronization, Session Identity Integrity, and Gateway Error Feedback

## Prerequisites
- JDK 25 installed and configured (`java --version`)
- Maven 3.9+ (`./mvnw --version`)
- Running PostgreSQL database instance (or local Docker container on `localhost:5432` with DB `mmo_platform`)

## Setup Commands

1. **Verify Database Availability**:
   ```bash
   docker run --rm -d --name mmo-postgres -p 5432:5432 \
     -e POSTGRES_DB=mmo_platform \
     -e POSTGRES_USER=postgres \
     -e POSTGRES_PASSWORD=postgres \
     postgres:16-alpine
   ```

2. **Generate Crypto Keys (if target/certs missing)**:
   ```bash
   mkdir -p target/certs
   openssl genpkey -algorithm RSA -out target/certs/jwt.key -pkeyopt rsa_keygen_bits:2048
   openssl rsa -pubout -in target/certs/jwt.key -out target/certs/jwt.pub
   ```

3. **Build Modules & Validate Registry Integrity**:
   ```bash
   ./mvnw clean compile
   ```

---

## Runnable Verification Scenarios

### Scenario 1: Wire Registry Unification & Drift Prevention Test
- **Goal**: Verify that all wire IDs in `common-protocol` and `platform-modulith` are unified with zero collisions.
- **Command**:
  ```bash
  ./mvnw test -pl common-protocol,platform-modulith -Dtest=ForyRegistryTest,ArchitectureTest
  ```
- **Expected Outcome**:
  - All serialization provider assertions pass.
  - Zero registration ID drift between client and backend codecs.

### Scenario 2: Defensive Connection Severing on Unauthenticated Packet
- **Goal**: Verify Gateway returns `GatewayErrorResponse(UNAUTHENTICATED)` and terminates the QUIC channel when an unauthenticated client sends in-world packets.
- **Command**:
  ```bash
  ./mvnw test -pl platform-modulith -Dtest=GatewaySecurityIT#unauthenticatedPacketTriggersRejectionAndDisconnect
  ```
- **Expected Outcome**:
  - Client receives `errorCode = 1001 (UNAUTHENTICATED)`.
  - Underlying QUIC transport channel closes immediately (< 50ms).
  - Zero Netty buffer leaks reported.

### Scenario 3: Defensive Connection Severing on Rate Limit Breach
- **Goal**: Verify Gateway returns `GatewayErrorResponse(RATE_LIMITED)` and severs the channel when client bursts past token bucket capacity.
- **Command**:
  ```bash
  ./mvnw test -pl platform-modulith -Dtest=GatewaySecurityIT#rateLimitExceededTriggersRejectionAndDisconnect
  ```
- **Expected Outcome**:
  - Client receives `errorCode = 1002 (RATE_LIMITED)`.
  - Connection is severed immediately on first violation packet.

### Scenario 4: Authoritative Actor Character Resolution
- **Goal**: Verify `JoinWorldRequestHandler` and zone simulation derive character identity strictly from the authenticated session context, with zero reliance on client-supplied actor IDs.
- **Command**:
  ```bash
  ./mvnw test -pl platform-modulith -Dtest=PlayerJourneyIT#testFullPlayerLifecycleJourney,ZoneSimulationIT
  ```
- **Expected Outcome**:
  - Client joins world and moves character.
  - Simulation attributes movements strictly to the character bound in session context.
  - Multi-client concurrent tests attribute positions without cross-session bleed.
