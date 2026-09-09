# Quickstart: Gateway Transport Hardening

This quickstart provides a runnable validation guide to test the implementation of the Gateway Transport Hardening feature.

## Prerequisites
- JDK 25 installed with Virtual Threads enabled.
- A running Gateway instance.
- A test client capable of generating QUIC traffic and simulating errors.

## Running Leak Detection Tests

The primary verification mechanism is Netty's leak detection framework.

1. Ensure you run the application or tests with the paranoid leak detection flag:
   ```bash
   -Dio.netty.leakDetection.level=PARANOID
   ```
2. Run the integration test suite covering the gateway components:
   ```bash
   ./gradlew :platform-modulith:test --tests "com.example.gateway.*"
   ```
3. Monitor the test output. Any memory leaks will cause the leak detector to print an error stack trace referencing "LEAK: ByteBuf". A successful test run will have zero such reports.

## Validating Rate-Limit Disconnects
To validate that over-quota packets do not leak buffers:
1. Connect a test client to the gateway.
2. Rapidly send requests exceeding the configured rate capacity.
3. Assert that the client receives a structured `GatewayErrorResponse` with `ERROR_RATE_LIMITED`.
4. Assert the connection is cleanly severed and that leak detection logs remain clean.

## Validating Malformed Frames
To validate decoder exception handling:
1. Connect a test client.
2. Send a packet with a manipulated length field (e.g. larger than `MAX_FRAME_LENGTH`).
3. Assert the server returns a `GatewayErrorResponse` with `ERROR_MALFORMED_PAYLOAD` and immediately closes the stream.
4. Verify leak detection logs remain clean.
