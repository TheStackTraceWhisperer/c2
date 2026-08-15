package com.example.common.protocol;

public final class ProtocolConstants {

  /**
   * Application-Layer Protocol Negotiation (ALPN) token for QUIC TLS 1.3 handshakes.
   */
  public static final String ALPN_PROTOCOL = "ping-pong-quic";

  /**
   * Maximum frame length permitted by the transport framing decoder (64 KB).
   */
  public static final int MAX_FRAME_LENGTH = 65535;

  /**
   * Maximum allowed payload length for a single application packet (16 KB).
   * Any packet claiming more readable bytes than this limit is rejected immediately at the edge.
   */
  public static final int MAX_PAYLOAD_SIZE = 16 * 1024; // 16,384 bytes

  /**
   * Header byte overhead:
   * ClientEnvelope: 2 bytes (serviceId) + 2 bytes (messageId) = 4 bytes.
   * BackendEnvelope: 8 bytes (sessionId) + 2 bytes (serviceId) + 2 bytes (messageId) = 12 bytes.
   */
  public static final int CLIENT_HEADER_SIZE = 4;
  public static final int BACKEND_HEADER_SIZE = 12;

  private ProtocolConstants() {}
}