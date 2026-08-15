package com.example.gateway.handler;

import com.example.server.protocol.SetSessionAuthStateControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.gateway.session.ClientSession;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class SetSessionAuthStateHandler implements PacketHandler<SetSessionAuthStateControlMessage> {

  @Override
  public void handle(ChannelHandlerContext ctx, SetSessionAuthStateControlMessage msg) {
    ClientSession session = ctx.channel().attr(ClientSession.KEY).get();
    if (session != null) {
      session.setAuthenticated(msg.authenticated());
      session.setAccountId(msg.accountId());
      log.info("[GATEWAY-CTRL] Session #{} authentication state set to: {} (accountId: {})",
        session.getSessionId(), msg.authenticated(), msg.accountId());
    }
  }
}