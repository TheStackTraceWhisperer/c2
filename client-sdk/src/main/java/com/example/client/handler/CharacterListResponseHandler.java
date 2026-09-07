package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.CharacterListResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class CharacterListResponseHandler implements PacketHandler<CharacterListResponse> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, CharacterListResponse packet) {
    int count = (packet.characters() != null) ? packet.characters().size() : 0;
    log.info("[CLIENT-HANDLER] Character list received: {} characters", count);

    if (clientState.getSelectedCharacterId() == null) {
      clientState.setConnectionState(ClientConnectionState.IN_LOBBY);
    }
    clientState.updateCharacters(packet.characters());
  }
}