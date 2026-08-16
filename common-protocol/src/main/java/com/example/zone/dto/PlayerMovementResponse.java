package com.example.zone.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record PlayerMovementResponse(long characterId, float x, float y, float z, long serverTick) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.ZONE; }
  @Override public short messageId() { return MessageType.PLAYER_MOVEMENT_RESPONSE; }
}