package com.example.server.protocol;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record SessionDisconnectedEvent(
  long sessionId,
  long timestamp,
  boolean zoneTransfer
) implements PacketMessage {

  public SessionDisconnectedEvent(long sessionId, long timestamp) {
    this(sessionId, timestamp, false);
  }

  @Override public short serviceId() { return ServiceType.AUTH; } // System broadcast default
  @Override public short messageId() { return MessageType.SESSION_DISCONNECTED; }
}