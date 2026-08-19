package com.example.world.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record ZoneTransferNotification(
  int targetZoneId,
  String zoneHost,
  int zonePort,
  float spawnX,
  float spawnY,
  float spawnZ
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.WORLD; }
  @Override public short messageId() { return MessageType.ZONE_TRANSFER_NOTIFICATION; }
}