package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.handler.PacketHandler;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class GatewayErrorResponseHandler implements PacketHandler<GatewayErrorResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, GatewayErrorResponse packet) {
    log.error("[CLIENT-HANDLER] GatewayErrorResponse received: code={}, svc={}, msg={}, text='{}'",
      packet.errorCode(), packet.rejectedServiceId(), packet.rejectedMessageId(), packet.message());

    clientState.setLastErrorCode(packet.errorCode());
    clientState.setLastErrorMessage(packet.message());

    if (packet.errorCode() != GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE) {
      clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
    }

    clientState.notifyListeners();
  }
}
