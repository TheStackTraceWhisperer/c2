package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import com.example.world.dto.PlayerMovementRequest;
import com.example.world.dto.PlayerMovementResponse;
import com.example.world.state.WorldSessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class PlayerMovementRequestHandler implements PacketHandler<PlayerMovementRequest> {

  private final WorldSessionRegistry sessionRegistry;
  private final AtomicLong serverTick = new AtomicLong(0L);

  @Override
  public void handle(ChannelHandlerContext ctx, PlayerMovementRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    long sid = (sessionId != null) ? sessionId : 0L;
    long currentTick = serverTick.incrementAndGet();

    // In production, the character ID is tracked per active session entity
    long characterId = sessionRegistry.getCharacterId(sid);
    if (characterId == 0L) {
      // Default to the first demo character if not explicitly registered in memory
      characterId = 1001L;
    }

    log.debug("[WORLD-SERVER] Movement from Session #{}, Char #{}: pos=({}, {}, {}), tick={}",
      sid, characterId, packet.x(), packet.y(), packet.z(), currentTick);

    ctx.writeAndFlush(new PlayerMovementResponse(characterId, packet.x(), packet.y(), packet.z(), currentTick));
  }
}