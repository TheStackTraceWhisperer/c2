package com.example.server;

import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.BackendEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class ServerEnvelopePayloadDecoder extends MessageToMessageDecoder<BackendEnvelope> {

  @Override
  protected void decode(ChannelHandlerContext ctx, BackendEnvelope env, List<Object> out) {
    try {
      ctx.channel().attr(SessionContext.SESSION_ID).set(env.sessionId());
      ctx.channel().attr(SessionContext.CHARACTER_ID).set(env.characterId());
      ctx.channel().attr(SessionContext.SERVICE_ID).set(env.serviceId());

      if (env.payload() != null && env.payload().isReadable()) {
        byte[] bytes = new byte[env.payload().readableBytes()];
        env.payload().readBytes(bytes);

        Object pojo = BackendForySerializationProvider.get().deserialize(bytes);
        if (pojo != null) {
          out.add(pojo);
        }
      }
    } finally {
      if (env.payload() != null && env.payload().refCnt() > 0) {
        env.payload().release();
      }
    }
  }
}