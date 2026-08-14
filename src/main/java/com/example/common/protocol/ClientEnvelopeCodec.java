package com.example.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

public class ClientEnvelopeCodec extends ByteToMessageCodec<ClientEnvelope> {

  @Override
  protected void encode(ChannelHandlerContext ctx, ClientEnvelope msg, ByteBuf out) {
    out.writeShort(msg.serviceId());
    out.writeShort(msg.messageId());
    if (msg.payload() != null && msg.payload().isReadable()) {
      out.writeBytes(msg.payload());
      msg.payload().release(); // Release the envelope payload once written to wire
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (in.readableBytes() < 4) {
      return;
    }
    short serviceId = in.readShort();
    short messageId = in.readShort();
    ByteBuf payload = in.readRetainedSlice(in.readableBytes());
    out.add(new ClientEnvelope(serviceId, messageId, payload));
  }
}