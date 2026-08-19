package com.example.zone.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record PlayerMovementResponse(
  long characterId,
  float x, float y, float z,
  float vx, float vy, float vz,
  float yaw, float pitch,
  int moveFlags,
  long serverTick,
  long clientTick,
  boolean reconciled
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.ZONE; }
  @Override public short messageId() { return MessageType.PLAYER_MOVEMENT_RESPONSE; }
}