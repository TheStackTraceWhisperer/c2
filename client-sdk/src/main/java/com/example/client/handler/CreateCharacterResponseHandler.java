package com.example.client.handler;

import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.CreateCharacterResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class CreateCharacterResponseHandler implements PacketHandler<CreateCharacterResponse> {

  private final ClientState clientState;

  public CreateCharacterResponseHandler() {
    this(null);
  }

  @Inject
  public CreateCharacterResponseHandler(ClientState clientState) {
    this.clientState = clientState;
  }

  @Override
  public void handle(ChannelHandlerContext ctx, CreateCharacterResponse packet) {
    log.info("[CLIENT-HANDLER] CreateCharacterResponse received: success={}, characterId={}, error='{}'",
      packet.success(), packet.characterId(), packet.errorMessage());
    if (clientState != null) {
      if (packet.success()) {
        clientState.setLastErrorMessage("");
      } else {
        clientState.setLastErrorMessage(packet.errorMessage());
      }
    }
  }
}