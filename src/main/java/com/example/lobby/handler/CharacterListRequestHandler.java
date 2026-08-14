package com.example.lobby.handler;

import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.CharacterSummary;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Singleton
public class CharacterListRequestHandler implements PacketHandler<CharacterListRequest> {

  @Override
  public void handle(ChannelHandlerContext ctx, CharacterListRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOBBY-SERVER] Fetching character list for Session #{}, accountId={}", sessionId, packet.accountId());

    List<CharacterSummary> sampleCharacters = List.of(
      new CharacterSummary(1001L, "Warrior", 45, "WARRIOR"),
      new CharacterSummary(1002L, "Mage", 50, "WIZARD")
    );

    ctx.writeAndFlush(new CharacterListResponse(packet.accountId(), sampleCharacters));
  }
}