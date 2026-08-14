package com.example.common.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record PongMessage(long timestamp, String message) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.AUTH; }
  @Override public short messageId() { return MessageType.PONG; }
}