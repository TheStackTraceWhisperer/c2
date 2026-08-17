package com.example.server.concurrent;

import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Singleton
public class VirtualThreadTaskDispatcher {

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Dispatches a blocking database or CPU task off the Netty event loop.
   */
  public void dispatch(Runnable task) {
    executor.submit(task);
  }

  /**
   * Dispatches a task and automatically handles unhandled exceptions on the channel.
   */
  public void dispatch(ChannelHandlerContext ctx, Runnable task) {
    executor.submit(() -> {
      try {
        task.run();
      } catch (Throwable t) {
        log.error("[VTHREAD-DISPATCHER] Unhandled task failure: {}", t.getMessage(), t);
        ctx.fireExceptionCaught(t);
      }
    });
  }
}