package com.example.login.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record ReconnectResponse(
  boolean success,
  long accountId,
  long characterId,
  String errorMessage
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.AUTH; }
  @Override public short messageId() { return MessageType.RECONNECT_RESPONSE; }
}