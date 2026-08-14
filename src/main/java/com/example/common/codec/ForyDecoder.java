package com.example.common.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class ForyDecoder extends ByteToMessageDecoder {

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    int readableBytes = in.readableBytes();
    if (readableBytes == 0) {
      return;
    }

    byte[] bytes = new byte[readableBytes];
    in.readBytes(bytes);

    Object obj = ForySerializationProvider.get().deserialize(bytes);
    if (obj != null) {
      out.add(obj);
    }
  }
}