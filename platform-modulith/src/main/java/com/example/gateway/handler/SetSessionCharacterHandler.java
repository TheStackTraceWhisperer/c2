package com.example.gateway.handler;

import com.example.server.protocol.SetSessionCharacterControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.gateway.session.ClientSession;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class SetSessionCharacterHandler implements PacketHandler<SetSessionCharacterControlMessage> {

  @Override
  public void handle(ChannelHandlerContext ctx, SetSessionCharacterControlMessage msg) {
    ClientSession session = ctx.channel().attr(ClientSession.KEY).get();
    if (session != null) {
      session.setCharacterId(msg.characterId());
      log.info("[GATEWAY-CTRL] Session #{} bound to characterId={}",
        session.getSessionId(), msg.characterId());
    }
  }
}