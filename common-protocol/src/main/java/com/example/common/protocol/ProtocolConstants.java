package com.example.common.protocol;

public final class ProtocolConstants {

  public static final String ALPN_PROTOCOL = "ping-pong-quic";
  public static final int MAX_FRAME_LENGTH = 65535;
  public static final int MAX_PAYLOAD_SIZE = 16 * 1024; // 16,384 bytes

  public static final int CLIENT_HEADER_SIZE = 4;   // 2 (serviceId) + 2 (messageId)
  public static final int BACKEND_HEADER_SIZE = 20; // 8 (sessionId) + 8 (characterId) + 2 (serviceId) + 2 (messageId)

  private ProtocolConstants() {}
}