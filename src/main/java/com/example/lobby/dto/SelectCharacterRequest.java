package com.example.lobby.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record SelectCharacterRequest(long characterId) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.LOBBY; }
  @Override public short messageId() { return MessageType.SELECT_CHARACTER_REQUEST; }
}