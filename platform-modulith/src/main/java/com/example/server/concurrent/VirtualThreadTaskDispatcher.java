package com.example.server.concurrent;

import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class VirtualThreadTaskDispatcher {

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Dispatches a blocking database or CPU task off the Netty event loop.
   */
  public void dispatch(Runnable task) {
    executor.execute(() -> {
      try {
        task.run();
      } catch (Throwable t) {
        log.error("[VTHREAD-DISPATCHER] Unhandled background task failure: {}", t.getMessage(), t);
      }
    });
  }

  /**
   * Dispatches a task and automatically handles unhandled exceptions on the channel's event loop.
   */
  public void dispatch(ChannelHandlerContext ctx, Runnable task) {
    executor.execute(() -> {
      try {
        task.run();
      } catch (Throwable t) {
        log.error("[VTHREAD-DISPATCHER] Unhandled task failure: {}", t.getMessage(), t);
        if (ctx != null) {
          if (ctx.executor() != null && !ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> ctx.fireExceptionCaught(t));
          } else {
            ctx.fireExceptionCaught(t);
          }
        }
      }
    });
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}