package com.example.server.handler;

import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.repository.ActiveSessionJdbcRepository;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionDisconnectedHandlerTest {

  static class RecordingDispatcher extends VirtualThreadTaskDispatcher {
    @Override
    public void dispatch(Runnable task) {
      task.run();
    }
  }

  @Test
  @DisplayName("Ignores spoofed sessionId in payload and deletes DB row only when zoneTransfer is false")
  void testSanitizedSessionIdAndZoneTransferSeam() {
    List<Long> deletedSessionIds = new ArrayList<>();
    ActiveSessionJdbcRepository repo = (ActiveSessionJdbcRepository) Proxy.newProxyInstance(
      ActiveSessionJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ActiveSessionJdbcRepository.class},
      (proxy, method, args) -> {
        if ("deleteBySessionId".equals(method.getName())) {
          deletedSessionIds.add((Long) args[0]);
        }
        return null;
      }
    );

    SessionDisconnectedHandler handler = new SessionDisconnectedHandler(repo, new RecordingDispatcher());
    List<SessionDisconnectedEvent> receivedEvents = new ArrayList<>();
    handler.onDisconnect(receivedEvents::add);

    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    ch.attr(SessionContext.SESSION_ID).set(9001L);

    try {
      // 1. Zone transfer disconnect: preserves active_sessions row, uses trusted SessionContext.SESSION_ID
      handler.handle(ch.pipeline().firstContext(), new SessionDisconnectedEvent(12345L, 1000L, true));
      assertThat(deletedSessionIds).isEmpty();
      assertThat(receivedEvents).hasSize(1);
      assertThat(receivedEvents.get(0).sessionId()).isEqualTo(9001L);
      assertThat(receivedEvents.get(0).zoneTransfer()).isTrue();

      // 2. Full disconnect: deletes active_sessions row for trusted SessionContext.SESSION_ID (ignoring spoofed 99999L)
      handler.handle(ch.pipeline().firstContext(), new SessionDisconnectedEvent(99999L, 2000L, false));
      assertThat(deletedSessionIds).containsExactly(9001L);
      assertThat(receivedEvents).hasSize(2);
      assertThat(receivedEvents.get(1).sessionId()).isEqualTo(9001L);
      assertThat(receivedEvents.get(1).zoneTransfer()).isFalse();
    } finally {
      ch.finishAndReleaseAll();
    }
  }
}
