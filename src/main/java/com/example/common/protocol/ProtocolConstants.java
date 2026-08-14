package com.example.common.protocol;

public final class ProtocolConstants {

  /**
   * Application-Layer Protocol Negotiation (ALPN) token for QUIC TLS 1.3 handshakes.
   */
  public static final String ALPN_PROTOCOL = "ping-pong-quic";

  private ProtocolConstants() {}
}