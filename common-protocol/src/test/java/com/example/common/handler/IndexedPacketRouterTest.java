package com.example.common.handler;

import com.example.common.dto.PingMessage;
import com.example.common.protocol.MessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IndexedPacketRouterTest {

  @Test
  @DisplayName("route() returns false for unregistered messages and true when dispatched")
  void testRouteReturnValueAndDispatch() throws Exception {
    IndexedPacketRouter router = new IndexedPacketRouter();
    PingMessage ping = new PingMessage(123L, "hi");

    assertThat(router.route(null, ping)).isFalse();
    assertThat(router.route(null, null)).isFalse();

    AtomicInteger handled = new AtomicInteger();
    router.register(MessageType.PING, (ctx, msg) -> handled.incrementAndGet());

    assertThat(router.route(null, ping)).isTrue();
    assertThat(handled.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("InboundDispatchHandler handles unregistered packets gracefully")
  void testInboundDispatchHandlerUnregistered() {
    IndexedPacketRouter router = new IndexedPacketRouter();
    EmbeddedChannel ch = new EmbeddedChannel(new InboundDispatchHandler(router));
    try {
      assertThat(ch.writeInbound(new PingMessage(100L, "test"))).isFalse();
      assertThat(ch.isOpen()).isTrue();
    } finally {
      ch.finishAndReleaseAll();
    }
  }
}
