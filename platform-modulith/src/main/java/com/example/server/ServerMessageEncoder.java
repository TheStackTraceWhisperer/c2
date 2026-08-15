package com.example.server;

import com.example.server.protocol.BackendEnvelope;
import com.example.server.codec.BackendForySerializationProvider;

import com.example.common.protocol.PacketMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public class ServerMessageEncoder extends MessageToMessageEncoder<PacketMessage> {

  public ServerMessageEncoder() {
    super(PacketMessage.class);
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, PacketMessage msg, List<Object> out) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    long sid = (sessionId != null) ? sessionId : 0L;

    byte[] bytes = BackendForySerializationProvider.get().serialize(msg);
    ByteBuf payloadBuf = ctx.alloc().buffer(bytes.length).writeBytes(bytes);

    out.add(new BackendEnvelope(sid, msg.serviceId(), msg.messageId(), payloadBuf));
  }
}