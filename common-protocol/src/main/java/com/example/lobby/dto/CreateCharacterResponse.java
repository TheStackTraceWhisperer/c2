package com.example.lobby.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record CreateCharacterResponse(
  boolean success,
  long characterId,
  String errorMessage
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.LOBBY; }
  @Override public short messageId() { return MessageType.CREATE_CHARACTER_RESPONSE; }
}