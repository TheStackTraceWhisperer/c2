package com.example.client;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.ClientEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class ClientEnvelopePayloadDecoder extends MessageToMessageDecoder<ClientEnvelope> {

  @Override
  protected void decode(ChannelHandlerContext ctx, ClientEnvelope env, List<Object> out) {
    try {
      if (env.payload() != null && env.payload().isReadable()) {
        byte[] bytes = new byte[env.payload().readableBytes()];
        env.payload().readBytes(bytes);

        Object pojo = ForySerializationProvider.get().deserialize(bytes);
        if (pojo != null) {
          out.add(pojo);
        }
      }
    } finally {
      if (env.payload() != null) {
        env.payload().release();
      }
    }
  }
}