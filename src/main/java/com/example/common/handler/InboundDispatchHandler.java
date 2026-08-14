package com.example.common.handler;

import com.example.common.protocol.PacketMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class InboundDispatchHandler extends SimpleChannelInboundHandler<PacketMessage> {

  private final IndexedPacketRouter router;

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, PacketMessage msg) throws Exception {
    router.route(ctx, msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("Dispatch handler exception: {}", cause.getMessage());
    ctx.close();
  }
}