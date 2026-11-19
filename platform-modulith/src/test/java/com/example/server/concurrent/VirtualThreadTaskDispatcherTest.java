package com.example.server.concurrent;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class VirtualThreadTaskDispatcherTest {

  @Test
  @DisplayName("dispatch(Runnable) catches exceptions safely, dispatch(ctx, Runnable) forwards to event loop, and shutdown terminates")
  void testDispatchExceptionHandlingAndShutdown() throws Exception {
    VirtualThreadTaskDispatcher dispatcher = new VirtualThreadTaskDispatcher();
    CountDownLatch backgroundRan = new CountDownLatch(1);
    assertThatCode(() -> dispatcher.dispatch(() -> {
      backgroundRan.countDown();
      throw new RuntimeException("boom");
    })).doesNotThrowAnyException();
    assertThat(backgroundRan.await(2, TimeUnit.SECONDS)).isTrue();

    AtomicReference<Throwable> caughtOnLoop = new AtomicReference<>();
    AtomicReference<Thread> handlerThread = new AtomicReference<>();
    CountDownLatch exceptionLatch = new CountDownLatch(1);

    DefaultEventLoop eventLoop = new DefaultEventLoop();
    EmbeddedChannel ch = new EmbeddedChannel(
      new ChannelInboundHandlerAdapter(),
      new ChannelInboundHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          handlerThread.set(Thread.currentThread());
          caughtOnLoop.set(cause);
          exceptionLatch.countDown();
        }
      }
    );

    ChannelHandlerContext ctxProxy = (ChannelHandlerContext) Proxy.newProxyInstance(
      ChannelHandlerContext.class.getClassLoader(),
      new Class<?>[]{ChannelHandlerContext.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "executor" -> eventLoop;
        case "fireExceptionCaught" -> {
          ch.pipeline().firstContext().fireExceptionCaught((Throwable) args[0]);
          yield proxy;
        }
        default -> null;
      }
    );

    try {
      dispatcher.dispatch(ctxProxy, () -> {
        throw new IllegalStateException("task-failure");
      });

      assertThat(exceptionLatch.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(caughtOnLoop.get()).isInstanceOf(IllegalStateException.class).hasMessage("task-failure");
      assertThat(handlerThread.get().isVirtual()).isFalse();
    } finally {
      ch.finishAndReleaseAll();
      eventLoop.shutdownGracefully().syncUninterruptibly();
      dispatcher.shutdown();
    }
  }
}
