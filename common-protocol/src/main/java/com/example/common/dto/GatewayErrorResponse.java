package com.example.common.dto;

import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;

public record GatewayErrorResponse(
  short errorCode,
  short rejectedServiceId,
  short rejectedMessageId,
  String message
) implements PacketMessage {

  // Error Code Constants
  public static final short ERROR_UNAUTHENTICATED = 1001;
  public static final short ERROR_RATE_LIMITED = 1002;
  public static final short ERROR_INVALID_SESSION_STATE = 1003;
  public static final short ERROR_MALFORMED_PAYLOAD = 1004;
  public static final short ERROR_SERVICE_UNAVAILABLE = 2001;

  @Override
  public short serviceId() {
    return ServiceType.SYSTEM;
  }

  @Override
  public short messageId() {
    return MessageType.GATEWAY_ERROR_RESPONSE;
  }
}
