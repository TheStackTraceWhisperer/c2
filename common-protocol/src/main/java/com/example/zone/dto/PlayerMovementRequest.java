package com.example.zone.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record PlayerMovementRequest(float x, float y, float z, float yaw, float pitch) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.ZONE; }
  @Override public short messageId() { return MessageType.PLAYER_MOVEMENT_REQUEST; }
}