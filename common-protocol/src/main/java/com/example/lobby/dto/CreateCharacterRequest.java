package com.example.lobby.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record CreateCharacterRequest(
  String authToken,
  int worldId,
  String name,
  short raceId,
  short gender,
  short faceId,
  short hairId,
  short sizeId,
  short nationId,
  short mainJobId
) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.LOBBY; }
  @Override public short messageId() { return MessageType.CREATE_CHARACTER_REQUEST; }
}