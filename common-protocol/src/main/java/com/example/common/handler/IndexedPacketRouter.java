package com.example.common.handler;

import com.example.common.protocol.PacketMessage;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexedPacketRouter {

  private static final int MAX_MESSAGE_ID = 512;
  private final PacketHandler<?>[] handlers = new PacketHandler<?>[MAX_MESSAGE_ID];

  public <T extends PacketMessage> void register(short messageId, PacketHandler<T> handler) {
    if (messageId < 0 || messageId >= MAX_MESSAGE_ID) {
      throw new IllegalArgumentException("messageId out of bounds (0-" + (MAX_MESSAGE_ID - 1) + "): " + messageId);
    }
    handlers[messageId] = handler;
  }

  @SuppressWarnings("unchecked")
  public boolean route(ChannelHandlerContext ctx, PacketMessage msg) throws Exception {
    if (msg == null) {
      return false;
    }
    short id = msg.messageId();
    if (id >= 0 && id < MAX_MESSAGE_ID) {
      PacketHandler<PacketMessage> handler = (PacketHandler<PacketMessage>) handlers[id];
      if (handler != null) {
        handler.handle(ctx, msg);
        return true;
      }
    }
    log.warn("No handler registered for messageId: {}", id);
    return false;
  }
}