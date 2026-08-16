package com.example.server.protocol;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record SetSessionZoneControlMessage(
  int zoneId,
  String host,
  int port
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.GATEWAY; }
  @Override public short messageId() { return MessageType.CTRL_SET_ZONE; }
}