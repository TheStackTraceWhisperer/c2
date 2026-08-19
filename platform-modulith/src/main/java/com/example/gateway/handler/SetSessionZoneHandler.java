package com.example.gateway.handler;

import com.example.common.handler.PacketHandler;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ServiceType;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.gateway.BackendConnectionManager;
import com.example.gateway.session.ClientSession;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.BackendEnvelope;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.protocol.SetSessionZoneControlMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class SetSessionZoneHandler implements PacketHandler<SetSessionZoneControlMessage> {

  @Override
  public void handle(ChannelHandlerContext ctx, SetSessionZoneControlMessage msg) {
    ClientSession session = (ctx != null && ctx.channel() != null)
      ? ctx.channel().attr(ClientSession.KEY).get()
      : null;

    if (session != null) {
      EndpointProperties oldEndpoint = session.getZoneEndpoint();
      int previousZoneId = session.getCurrentZoneId();

      log.info("[GATEWAY-CTRL] Transferring Session #{} from Zone #{} to Zone #{} ({}:{})",
        session.getSessionId(), previousZoneId, msg.zoneId(), msg.host(), msg.port());

      if (oldEndpoint != null) {
        long oldKey = BackendConnectionManager.makeKey(ServiceType.ZONE, oldEndpoint.getHostname(), oldEndpoint.getPort());
        QuicStreamChannel oldStream = session.getBackendStreams().remove(oldKey);
        if (oldStream != null && oldStream.isActive()) {
          // Authoritatively evict entity state from previous zone before closing stream
          SessionDisconnectedEvent disconnectEvent = new SessionDisconnectedEvent(session.getSessionId(), System.currentTimeMillis());
          byte[] bytes = BackendForySerializationProvider.get().serialize(disconnectEvent);
          ByteBuf payloadBuf = oldStream.alloc().buffer(bytes.length).writeBytes(bytes);
          BackendEnvelope envelope = new BackendEnvelope(session.getSessionId(), session.getCharacterId(), ServiceType.ZONE, MessageType.SESSION_DISCONNECTED, payloadBuf);
          oldStream.writeAndFlush(envelope).addListener((ChannelFutureListener) f -> oldStream.close());
        }
      }

      session.setCurrentZoneId(msg.zoneId());
      session.setZoneEndpoint(new EndpointProperties(msg.port(), msg.host()));
    }
  }
}