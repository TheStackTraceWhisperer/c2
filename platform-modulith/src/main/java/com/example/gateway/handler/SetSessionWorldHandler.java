package com.example.gateway.handler;

import com.example.common.handler.PacketHandler;
import com.example.common.protocol.ServiceType;
import com.example.gateway.BackendConnectionManager;
import com.example.gateway.session.ClientSession;
import com.example.server.protocol.SetSessionWorldControlMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class SetSessionWorldHandler implements PacketHandler<SetSessionWorldControlMessage> {

  @Override
  public void handle(ChannelHandlerContext ctx, SetSessionWorldControlMessage msg) {
    ClientSession session = (ctx != null && ctx.channel() != null)
      ? ctx.channel().attr(ClientSession.KEY).get()
      : null;

    if (session != null) {
      int previousWorldId = session.getCurrentWorldId();
      int newWorldId = msg.worldId();

      if (previousWorldId == newWorldId) {
        return;
      }

      log.info("[GATEWAY-CTRL] Transferring Session #{} from World #{} to World #{}",
        session.getSessionId(), previousWorldId, newWorldId);

      long oldKey = BackendConnectionManager.makeKey(ServiceType.WORLD, previousWorldId);
      QuicStreamChannel oldStream = session.getBackendStreams().remove(oldKey);
      if (oldStream != null && oldStream.isActive()) {
        oldStream.close();
      }

      session.setCurrentWorldId(newWorldId);
    }
  }
}