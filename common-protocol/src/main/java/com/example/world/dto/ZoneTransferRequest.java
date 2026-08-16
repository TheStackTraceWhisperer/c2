package com.example.world.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record ZoneTransferRequest(long characterId, int targetZoneId) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.WORLD; }
  @Override public short messageId() { return MessageType.ZONE_TRANSFER_REQUEST; }
}