package com.example.server.queue;

import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Singleton
public class PacketQueue {

  private final Queue<QueuedPacket<?>> queue = new ConcurrentLinkedQueue<>();

  public void enqueue(ChannelHandlerContext ctx, Object packet) {
    queue.offer(new QueuedPacket<>(ctx, packet));
  }

  public QueuedPacket<?> poll() {
    return queue.poll();
  }

  public boolean isEmpty() {
    return queue.isEmpty();
  }
}