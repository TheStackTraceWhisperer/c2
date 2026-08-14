package com.example.common.backend.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

public class BackendEnvelopeCodec extends ByteToMessageCodec<BackendEnvelope> {

  @Override
  protected void encode(ChannelHandlerContext ctx, BackendEnvelope msg, ByteBuf out) {
    out.writeLong(msg.sessionId());
    out.writeShort(msg.serviceId());
    out.writeShort(msg.messageId());
    if (msg.payload() != null && msg.payload().isReadable()) {
      out.writeBytes(msg.payload());
      msg.payload().release(); // Release the envelope payload once written to wire
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (in.readableBytes() < 12) {
      return;
    }
    long sessionId = in.readLong();
    short serviceId = in.readShort();
    short messageId = in.readShort();
    ByteBuf payload = in.readRetainedSlice(in.readableBytes());
    out.add(new BackendEnvelope(sessionId, serviceId, messageId, payload));
  }
}