package com.example.gateway.handler;

import com.example.common.handler.PacketHandler;
import com.example.common.protocol.ServiceType;
import com.example.config.DemoProperties.EndpointProperties;
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
      EndpointProperties oldEndpoint = session.getWorldEndpoint();
      int previousWorldId = session.getCurrentWorldId();

      log.info("[GATEWAY-CTRL] Binding Session #{} to World #{} ({}:{})",
        session.getSessionId(), msg.worldId(), msg.host(), msg.port());

      if (oldEndpoint != null && (oldEndpoint.getPort() != msg.port() || !oldEndpoint.getHostname().equals(msg.host()))) {
        long oldKey = BackendConnectionManager.makeKey(ServiceType.WORLD, oldEndpoint.getHostname(), oldEndpoint.getPort());
        QuicStreamChannel oldStream = session.getBackendStreams().remove(oldKey);
        if (oldStream != null && oldStream.isActive()) {
          oldStream.close();
        }
      }

      session.setCurrentWorldId(msg.worldId());
      session.setWorldEndpoint(new EndpointProperties(msg.port(), msg.host()));
    }
  }
}