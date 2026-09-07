package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.world.dto.JoinWorldResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class JoinWorldResponseHandler implements PacketHandler<JoinWorldResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, JoinWorldResponse packet) {
    log.info("[CLIENT-HANDLER] JoinWorldResponse received: success={}, initialZoneId={}",
      packet.success(), packet.initialZoneId());
    if (packet.success()) {
      clientState.clearRemoteEntities();
      clientState.setCurrentZoneId(packet.initialZoneId());
      clientState.setConnectionState(ClientConnectionState.IN_ZONE);
      clientState.notifyListeners();
    }
  }
}