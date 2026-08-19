package com.example.client.handler;

import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.zone.dto.PlayerMovementResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class PlayerMovementResponseHandler implements PacketHandler<PlayerMovementResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, PlayerMovementResponse packet) {
    log.debug("[CLIENT-HANDLER] Ack Movement: charId={}, pos=({}, {}, {}), tick={}, reconciled={}",
      packet.characterId(), packet.x(), packet.y(), packet.z(), packet.serverTick(), packet.reconciled());
    clientState.updatePosition(packet.x(), packet.y(), packet.z(), packet.serverTick());
  }
}