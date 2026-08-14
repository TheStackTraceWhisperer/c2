package com.example.client;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.PacketMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public class ClientMessageEncoder extends MessageToMessageEncoder<PacketMessage> {

  public ClientMessageEncoder() {
    super(PacketMessage.class);
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, PacketMessage msg, List<Object> out) {
    byte[] bytes = ForySerializationProvider.get().serialize(msg);
    ByteBuf payloadBuf = ctx.alloc().buffer(bytes.length).writeBytes(bytes);

    out.add(new ClientEnvelope(msg.serviceId(), msg.messageId(), payloadBuf));
  }
}