package com.example.lobby.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

import java.util.List;

public record CharacterListResponse(long accountId, List<CharacterSummary> characters) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.LOBBY; }
  @Override public short messageId() { return MessageType.CHARACTER_LIST_RESPONSE; }
}