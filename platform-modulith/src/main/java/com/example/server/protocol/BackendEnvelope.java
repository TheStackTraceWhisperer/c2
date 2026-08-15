package com.example.server.protocol;

import io.netty.buffer.ByteBuf;

public record BackendEnvelope(
  long sessionId,
  short serviceId,
  short messageId,
  ByteBuf payload
) {}