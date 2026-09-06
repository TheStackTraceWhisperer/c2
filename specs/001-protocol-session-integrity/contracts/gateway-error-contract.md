# Contract: Gateway Error Response

## Protocol Specification
- **Message Type**: Binary Encoded (Apache Fory)
- **Service ID**: `1` (`ServiceType.SYSTEM`)
- **Message ID**: `52` (`MessageType.GATEWAY_ERROR_RESPONSE`)
- **Transport**: Netty QUIC Bidirectional Stream

## Wire Schema

```java
package com.example.common.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record GatewayErrorResponse(
  short errorCode,
  short rejectedServiceId,
  short rejectedMessageId,
  String message
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.SYSTEM; }
  @Override public short messageId() { return MessageType.GATEWAY_ERROR_RESPONSE; }
}
```

## Error Codes

| Error Code Constant | Value | HTTP Equivalent | Termination Policy | Client Action |
| :--- | :---: | :---: | :--- | :--- |
| `UNAUTHENTICATED` | `1001` | 401 | Immediate Disconnect | Prompt user to log in again; restart handshake |
| `RATE_LIMITED` | `1002` | 429 | Immediate Disconnect | Back off connection attempts; inspect client tick rate |
| `INVALID_SESSION_STATE` | `1003` | 403 | Immediate Disconnect | Restart client state machine at login / character select |
| `MALFORMED_PAYLOAD` | `1004` | 400 | Immediate Disconnect | Verify client binary version; discard bad frames |
| `SERVICE_UNAVAILABLE` | `2001` | 503 | Connection Preserved | Display "World/Zone server unavailable; retrying..." |

## Gateway Teardown Pipeline
When an error with `Immediate Disconnect` policy is emitted:
1. Allocate and serialize `GatewayErrorResponse` into output ByteBuf.
2. Flush frame to `clientStream`.
3. Add listener to the write future:
   ```java
   writeFuture.addListener(f -> {
     clientStream.close();
     clientStream.parent().close(); // Closes parent QUIC Channel
   });
   ```
4. In the `finally` block of the ingress handler, ensure `msg.payload().release()` is called.
