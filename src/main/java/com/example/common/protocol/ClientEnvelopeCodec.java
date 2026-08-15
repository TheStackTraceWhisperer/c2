package com.example.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.TooLongFrameException;

import java.util.List;

public class ClientEnvelopeCodec extends ByteToMessageCodec<ClientEnvelope> {

  @Override
  protected void encode(ChannelHandlerContext ctx, ClientEnvelope msg, ByteBuf out) {
    if (msg.payload() != null && msg.payload().readableBytes() > ProtocolConstants.MAX_PAYLOAD_SIZE) {
      throw new IllegalArgumentException("Payload size " + msg.payload().readableBytes()
        + " exceeds MAX_PAYLOAD_SIZE (" + ProtocolConstants.MAX_PAYLOAD_SIZE + ")");
    }

    out.writeShort(msg.serviceId());
    out.writeShort(msg.messageId());
    if (msg.payload() != null && msg.payload().isReadable()) {
      out.writeBytes(msg.payload());
      msg.payload().release();
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (in.readableBytes() < ProtocolConstants.CLIENT_HEADER_SIZE) {
      return;
    }

    int payloadLength = in.readableBytes() - ProtocolConstants.CLIENT_HEADER_SIZE;
    if (payloadLength > ProtocolConstants.MAX_PAYLOAD_SIZE) {
      in.skipBytes(in.readableBytes()); // Discard corrupted/abusive frame
      throw new TooLongFrameException("Client envelope payload length " + payloadLength
        + " exceeds maximum allowed " + ProtocolConstants.MAX_PAYLOAD_SIZE);
    }

    short serviceId = in.readShort();
    short messageId = in.readShort();
    ByteBuf payload = in.readRetainedSlice(in.readableBytes());
    out.add(new ClientEnvelope(serviceId, messageId, payload));
  }
}