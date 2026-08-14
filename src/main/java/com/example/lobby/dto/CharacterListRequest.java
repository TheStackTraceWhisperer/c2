package com.example.lobby.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record CharacterListRequest(long accountId, String authToken) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.LOBBY; }
  @Override public short messageId() { return MessageType.CHARACTER_LIST_REQUEST; }
}