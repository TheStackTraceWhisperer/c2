package com.example.common.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ForyEncoder extends MessageToByteEncoder<Object> {

  @Override
  protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
    byte[] bytes = ForySerializationProvider.get().serialize(msg);
    out.writeBytes(bytes);
  }
}