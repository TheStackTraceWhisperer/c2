package com.example.common.protocol;

public interface PacketMessage {
  short serviceId();
  short messageId();
}