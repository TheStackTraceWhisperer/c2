package com.example.server.protocol;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record SessionDisconnectedEvent(long sessionId, long timestamp) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.AUTH; } // System broadcast default
  @Override public short messageId() { return MessageType.SESSION_DISCONNECTED; }
}