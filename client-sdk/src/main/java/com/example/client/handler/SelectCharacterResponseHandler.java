package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.SelectCharacterResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class SelectCharacterResponseHandler implements PacketHandler<SelectCharacterResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, SelectCharacterResponse packet) {
    log.info("[CLIENT-HANDLER] SelectCharacterResponse received: success={}, characterId={}",
      packet.success(), packet.characterId());

    if (packet.success()) {
      clientState.resetZoneSession();
      clientState.setSelectedCharacterId(packet.characterId());
      clientState.setConnectionState(ClientConnectionState.IN_WORLD);
      clientState.notifyListeners();
    } else {
      log.error("[CLIENT-HANDLER] Character selection failed: {}", packet.errorMessage());
    }
  }
}