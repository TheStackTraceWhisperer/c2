package com.example.common.backend.protocol;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record SetSessionAuthStateControlMessage(boolean authenticated, long accountId) implements PacketMessage {
  @Override public short serviceId() { return ServiceType.GATEWAY; }
  @Override public short messageId() { return MessageType.CTRL_SET_AUTH_STATE; }
}