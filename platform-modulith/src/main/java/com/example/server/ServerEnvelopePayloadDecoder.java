package com.example.server;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.BackendEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class ServerEnvelopePayloadDecoder extends MessageToMessageDecoder<BackendEnvelope> {

  @Override
  protected void decode(ChannelHandlerContext ctx, BackendEnvelope env, List<Object> out) throws CorruptedFrameException {
    ctx.channel().attr(SessionContext.SESSION_ID).set(env.sessionId());
    ctx.channel().attr(SessionContext.CHARACTER_ID).set(env.characterId());
    ctx.channel().attr(SessionContext.SERVICE_ID).set(env.serviceId());

    if (env.payload() != null && env.payload().isReadable()) {
      byte[] bytes = new byte[env.payload().readableBytes()];
      env.payload().readBytes(bytes);

      boolean internalFrame = env.serviceId() == ServiceType.GATEWAY
        || env.messageId() == MessageType.SESSION_DISCONNECTED;
      Object pojo = internalFrame
        ? BackendForySerializationProvider.get().deserialize(bytes)
        : ForySerializationProvider.get().deserialize(bytes);
      if (pojo instanceof PacketMessage) {
        out.add(pojo);
      } else if (pojo != null) {
        throw new CorruptedFrameException("Rejected non-PacketMessage payload type: " + pojo.getClass().getName());
      }
    }
  }
}