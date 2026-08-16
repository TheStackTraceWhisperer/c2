package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.world.dto.ZoneTransferResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneTransferResponseHandler implements PacketHandler<ZoneTransferResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, ZoneTransferResponse packet) {
    log.info("[CLIENT-HANDLER] ZoneTransferResponse received: success={}, targetZoneId={}",
      packet.success(), packet.targetZoneId());
    if (packet.success()) {
      clientState.setCurrentZoneId(packet.targetZoneId());
      clientState.setConnectionState(ClientConnectionState.IN_ZONE);
      clientState.notifyListeners();
    }
  }
}