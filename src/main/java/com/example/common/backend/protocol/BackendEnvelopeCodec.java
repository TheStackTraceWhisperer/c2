package com.example.common.backend.protocol;

import com.example.common.protocol.ProtocolConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.TooLongFrameException;

import java.util.List;

public class BackendEnvelopeCodec extends ByteToMessageCodec<BackendEnvelope> {

  @Override
  protected void encode(ChannelHandlerContext ctx, BackendEnvelope msg, ByteBuf out) {
    if (msg.payload() != null && msg.payload().readableBytes() > ProtocolConstants.MAX_PAYLOAD_SIZE) {
      throw new IllegalArgumentException("Backend payload size " + msg.payload().readableBytes()
        + " exceeds MAX_PAYLOAD_SIZE (" + ProtocolConstants.MAX_PAYLOAD_SIZE + ")");
    }

    out.writeLong(msg.sessionId());
    out.writeShort(msg.serviceId());
    out.writeShort(msg.messageId());
    if (msg.payload() != null && msg.payload().isReadable()) {
      out.writeBytes(msg.payload());
      msg.payload().release();
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (in.readableBytes() < ProtocolConstants.BACKEND_HEADER_SIZE) {
      return;
    }

    int payloadLength = in.readableBytes() - ProtocolConstants.BACKEND_HEADER_SIZE;
    if (payloadLength > ProtocolConstants.MAX_PAYLOAD_SIZE) {
      in.skipBytes(in.readableBytes());
      throw new TooLongFrameException("Backend envelope payload length " + payloadLength
        + " exceeds maximum allowed " + ProtocolConstants.MAX_PAYLOAD_SIZE);
    }

    long sessionId = in.readLong();
    short serviceId = in.readShort();
    short messageId = in.readShort();
    ByteBuf payload = in.readRetainedSlice(in.readableBytes());
    out.add(new BackendEnvelope(sessionId, serviceId, messageId, payload));
  }
}