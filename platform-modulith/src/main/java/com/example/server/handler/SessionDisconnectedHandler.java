package com.example.server.handler;

import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class SessionDisconnectedHandler implements PacketHandler<SessionDisconnectedEvent> {

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
    log.info("[BACKEND-CLEANUP] Evicting resources for disconnected Session #{} (ts: {})",
      sessionId, packet.timestamp());

    for (Consumer<SessionDisconnectedEvent> listener : disconnectListeners) {
      try {
        listener.accept(packet);
      } catch (Exception e) {
        log.error("Error invoking disconnect listener", e);
      }
    }
  }
}