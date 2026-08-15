package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.login.dto.LoginResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class LoginResponseHandler implements PacketHandler<LoginResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, LoginResponse packet) {
    log.info("[CLIENT-HANDLER] LoginResponse received: success={}, accountId={}", packet.success(), packet.accountId());

    if (packet.success()) {
      clientState.setAccountId(packet.accountId());
      clientState.setAuthToken(packet.token());
      clientState.setConnectionState(ClientConnectionState.AUTHENTICATED);
      clientState.notifyListeners();
    } else {
      log.error("[CLIENT-HANDLER] Login rejected: {}", packet.errorMessage());
      clientState.setConnectionState(ClientConnectionState.CONNECTED);
    }
  }
}