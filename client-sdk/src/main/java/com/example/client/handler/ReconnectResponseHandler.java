package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.login.dto.ReconnectResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ReconnectResponseHandler implements PacketHandler<ReconnectResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, ReconnectResponse packet) {
    log.info("[CLIENT-HANDLER] ReconnectResponse received: success={}, characterId={}",
      packet.success(), packet.characterId());

    if (packet.success()) {
      clientState.setAccountId(packet.accountId());
      if (packet.characterId() > 0L) {
        clientState.setSelectedCharacterId(packet.characterId());
        clientState.setConnectionState(ClientConnectionState.IN_WORLD);
      } else {
        clientState.setConnectionState(ClientConnectionState.AUTHENTICATED);
      }
      clientState.notifyListeners();
    } else {
      log.error("[CLIENT-HANDLER] Reconnection rejected: {}", packet.errorMessage());
      clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
    }
  }
}