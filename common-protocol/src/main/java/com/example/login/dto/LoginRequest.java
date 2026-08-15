package com.example.login.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record LoginRequest(String username, String password) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.AUTH; }
  @Override public short messageId() { return MessageType.LOGIN_REQUEST; }
}