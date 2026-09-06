package com.example.server.handler;

import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.repository.ActiveSessionJdbcRepository;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class SessionDisconnectedHandler implements PacketHandler<SessionDisconnectedEvent> {

  private final ActiveSessionJdbcRepository activeSessionRepository;
  private final VirtualThreadTaskDispatcher dispatcher;
  private final List<Consumer<SessionDisconnectedEvent>> disconnectListeners = new CopyOnWriteArrayList<>();

  public void onDisconnect(Consumer<SessionDisconnectedEvent> listener) {
    disconnectListeners.add(listener);
  }

  public void removeListener(Consumer<SessionDisconnectedEvent> listener) {
    disconnectListeners.remove(listener);
  }

  @Override
  public void handle(ChannelHandlerContext ctx, SessionDisconnectedEvent packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    long targetSessionId = (sessionId != null && sessionId > 0L) ? sessionId : packet.sessionId();

    log.info("[BACKEND-CLEANUP] Evicting active session lock for Session #{} (ts: {})",
      targetSessionId, packet.timestamp());

    dispatcher.dispatch(() -> {
      try {
        activeSessionRepository.deleteBySessionId(targetSessionId);
      } catch (Exception e) {
        log.warn("[BACKEND-CLEANUP] Active session eviction failed: {}", e.getMessage());
      }
    });

    for (Consumer<SessionDisconnectedEvent> listener : disconnectListeners) {
      try {
        listener.accept(packet);
      } catch (Exception e) {
        log.error("Error invoking disconnect listener", e);
      }
    }
  }
}