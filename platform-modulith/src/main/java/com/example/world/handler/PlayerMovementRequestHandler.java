package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import com.example.server.protocol.SetSessionWorldControlMessage;
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

    long characterId = sessionRegistry.getCharacterId(sid);
    if (characterId == 0L) {
      characterId = 1001L;
    }

    log.debug("[WORLD-SERVER] Movement from Session #{}, Char #{}: pos=({}, {}, {}), tick={}",
      sid, characterId, packet.x(), packet.y(), packet.z(), currentTick);

    // Zone boundary / portal trigger: moving past x >= 500 triggers dynamic handoff to World 2
    if (packet.x() >= 500.0f) {
      log.info("[WORLD-SERVER] Character #{} crossed portal boundary. Triggering handoff to World 2...", characterId);
      ctx.write(new SetSessionWorldControlMessage(2));
    }

    ctx.writeAndFlush(new PlayerMovementResponse(characterId, packet.x(), packet.y(), packet.z(), currentTick));
  }
}