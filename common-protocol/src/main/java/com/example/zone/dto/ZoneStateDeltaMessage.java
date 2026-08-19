package com.example.zone.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

import java.util.List;

public record ZoneStateDeltaMessage(
  long serverTick,
  List<EntitySpawnRecord> spawns,
  List<EntityMovementUpdate> updates,
  List<Long> despawns
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.ZONE; }
  @Override public short messageId() { return MessageType.ZONE_STATE_DELTA_BROADCAST; }
}