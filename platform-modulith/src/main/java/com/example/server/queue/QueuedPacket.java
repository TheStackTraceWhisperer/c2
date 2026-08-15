package com.example.server.queue;

import io.netty.channel.ChannelHandlerContext;

public record QueuedPacket<T>(ChannelHandlerContext ctx, T packet) {}