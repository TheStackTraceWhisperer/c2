package com.example.client.handler;

import com.example.common.dto.PongMessage;
import com.example.common.handler.PacketHandler;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class PongPacketHandler implements PacketHandler<PongMessage> {

  @Override
  public void handle(ChannelHandlerContext ctx, PongMessage packet) {
    long latency = System.currentTimeMillis() - packet.timestamp();
    log.info("[CLIENT-HANDLER] Pong received from server (latency: {}ms)", latency);
  }
}