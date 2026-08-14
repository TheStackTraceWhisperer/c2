package com.example.common.protocol;

import io.netty.buffer.ByteBuf;

public record ClientEnvelope(
  short serviceId,
  short messageId,
  ByteBuf payload
) {}