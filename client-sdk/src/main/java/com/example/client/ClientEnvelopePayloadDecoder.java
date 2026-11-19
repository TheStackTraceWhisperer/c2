package com.example.client;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.PacketMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class ClientEnvelopePayloadDecoder extends MessageToMessageDecoder<ClientEnvelope> {

  @Override
  protected void decode(ChannelHandlerContext ctx, ClientEnvelope env, List<Object> out) throws CorruptedFrameException {
    if (env.payload() != null && env.payload().isReadable()) {
      byte[] bytes = new byte[env.payload().readableBytes()];
      env.payload().readBytes(bytes);

      Object pojo = ForySerializationProvider.get().deserialize(bytes);
      if (pojo instanceof PacketMessage) {
        out.add(pojo);
      } else if (pojo != null) {
        throw new CorruptedFrameException("Rejected non-PacketMessage client payload type: " + pojo.getClass().getName());
      }
    }
  }
}