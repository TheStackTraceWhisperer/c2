package com.example.lobby.handler;

import com.example.common.backend.protocol.SetSessionCharacterControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.lobby.dto.SelectCharacterResponse;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class SelectCharacterRequestHandler implements PacketHandler<SelectCharacterRequest> {

  @Override
  public void handle(ChannelHandlerContext ctx, SelectCharacterRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOBBY-SERVER] Character selection for Session #{}: characterId={}", sessionId, packet.characterId());

    // Character validation logic (e.g. check character belongs to account)
    boolean valid = packet.characterId() == 1001L || packet.characterId() == 1002L;
    String err = valid ? "" : "Character not found";

    if (valid) {
      ctx.write(new SetSessionCharacterControlMessage(packet.characterId()));
    }

    ctx.writeAndFlush(new SelectCharacterResponse(valid, packet.characterId(), err));
  }
}