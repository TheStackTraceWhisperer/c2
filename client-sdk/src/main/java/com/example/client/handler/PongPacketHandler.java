package com.example.client.handler;

import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
import com.example.common.handler.PacketHandler;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class PongPacketHandler implements PacketHandler<PongMessage> {

  private final ClientState clientState;

  public PongPacketHandler() {
    this(null);
  }

  public PongPacketHandler(ClientState clientState) {
    this.clientState = clientState;
  }

  @Override
  public void handle(ChannelHandlerContext ctx, PongMessage packet) {
    long latency = Math.max(0, System.currentTimeMillis() - packet.timestamp());
    log.debug("[CLIENT-HANDLER] Pong received from server (latency: {}ms)", latency);
    if (clientState != null) {
      clientState.updateLatency(latency);
    }
  }
}