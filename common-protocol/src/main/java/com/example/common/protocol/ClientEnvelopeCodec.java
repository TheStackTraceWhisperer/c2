package com.example.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;
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
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws CorruptedFrameException {
    if (in.readableBytes() < ProtocolConstants.CLIENT_HEADER_SIZE) {
      in.skipBytes(in.readableBytes());
      throw new CorruptedFrameException("Short client envelope frame: expected at least "
        + ProtocolConstants.CLIENT_HEADER_SIZE + " bytes");
    }

    int payloadLength = in.readableBytes() - ProtocolConstants.CLIENT_HEADER_SIZE;
    if (payloadLength > ProtocolConstants.MAX_PAYLOAD_SIZE) {
      in.skipBytes(in.readableBytes());
      throw new TooLongFrameException("Client envelope payload length " + payloadLength
        + " exceeds maximum allowed " + ProtocolConstants.MAX_PAYLOAD_SIZE);
    }

    short serviceId = in.readShort();
    short messageId = in.readShort();
    ByteBuf payload = in.readRetainedSlice(payloadLength);
    out.add(new ClientEnvelope(serviceId, messageId, payload));
  }
}