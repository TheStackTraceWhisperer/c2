package com.example.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientEnvelopeCodecTest {

  @BeforeAll
  static void enableParanoidLeakDetection() {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
  }

  @Test
  @DisplayName("ClientEnvelope delegates ReferenceCounted safely and handles null payload")
  void testReferenceCountedDelegationAndNullPayload() {
    ClientEnvelope nullEnv = new ClientEnvelope(ServiceType.AUTH, MessageType.PING, null);
    assertThat(nullEnv.refCnt()).isZero();
    assertThat(nullEnv.retain()).isSameAs(nullEnv);
    assertThat(nullEnv.retain(2)).isSameAs(nullEnv);
    assertThat(nullEnv.touch()).isSameAs(nullEnv);
    assertThat(nullEnv.touch("hint")).isSameAs(nullEnv);
    assertThat(nullEnv.release()).isTrue();
    assertThat(nullEnv.release(2)).isTrue();

    ByteBuf buf = Unpooled.copiedBuffer("abc", StandardCharsets.UTF_8);
    ClientEnvelope env = new ClientEnvelope(ServiceType.AUTH, MessageType.PING, buf);
    assertThat(env.refCnt()).isEqualTo(1);
    assertThat(env.retain()).isSameAs(env);
    assertThat(env.refCnt()).isEqualTo(2);
    assertThat(env.touch("test")).isSameAs(env);
    assertThat(env.release()).isFalse();
    assertThat(env.refCnt()).isEqualTo(1);
    assertThat(env.release()).isTrue();
    assertThat(env.refCnt()).isZero();
  }

  @Test
  @DisplayName("Encode/decode round-trip leaves zero leaked ByteBuf references")
  void testEncodeDecodeZeroLeak() {
    EmbeddedChannel channel = new EmbeddedChannel(new ClientEnvelopeCodec());
    try {
      ByteBuf payload = channel.alloc().buffer(8).writeBytes("hello".getBytes(StandardCharsets.UTF_8));
      ClientEnvelope outbound = new ClientEnvelope(ServiceType.AUTH, MessageType.PING, payload);

      assertThat(channel.writeOutbound(outbound)).isTrue();
      assertThat(outbound.refCnt()).isZero();
      assertThat(payload.refCnt()).isZero();

      ByteBuf encoded = channel.readOutbound();
      assertThat(encoded).isNotNull();

      assertThat(channel.writeInbound(encoded)).isTrue();
      ClientEnvelope decoded = channel.readInbound();
      assertThat(decoded).isNotNull();
      assertThat(decoded.serviceId()).isEqualTo(ServiceType.AUTH);
      assertThat(decoded.messageId()).isEqualTo(MessageType.PING);
      assertThat(decoded.payload().toString(StandardCharsets.UTF_8)).isEqualTo("hello");

      assertThat(decoded.release()).isTrue();
      assertThat(decoded.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("Encode failure on oversized payload releases envelope once without double-release")
  void testEncodeOversizedPayloadReleasesOnce() {
    EmbeddedChannel channel = new EmbeddedChannel(new ClientEnvelopeCodec());
    try {
      ByteBuf oversized = channel.alloc().buffer(ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      oversized.writeZero(ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      ClientEnvelope outbound = new ClientEnvelope(ServiceType.AUTH, MessageType.PING, oversized);

      assertThatThrownBy(() -> channel.writeOutbound(outbound))
        .isInstanceOf(EncoderException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);

      assertThat(outbound.refCnt()).isZero();
      assertThat(oversized.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("Short frame (< CLIENT_HEADER_SIZE) consumes bytes and throws CorruptedFrameException")
  void testShortFrameThrowsCorruptedFrameException() {
    EmbeddedChannel channel = new EmbeddedChannel(new ClientEnvelopeCodec());
    try {
      ByteBuf shortBuf = channel.alloc().buffer(2).writeShort(ServiceType.AUTH);

      assertThatThrownBy(() -> channel.writeInbound(shortBuf))
        .isInstanceOf(CorruptedFrameException.class);

      assertThat(shortBuf.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("Oversized inbound frame consumes bytes and throws TooLongFrameException")
  void testOversizedInboundFrameThrowsTooLongFrameException() {
    EmbeddedChannel channel = new EmbeddedChannel(new ClientEnvelopeCodec());
    try {
      ByteBuf hugeBuf = channel.alloc().buffer(ProtocolConstants.CLIENT_HEADER_SIZE + ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      hugeBuf.writeShort(ServiceType.AUTH);
      hugeBuf.writeShort(MessageType.PING);
      hugeBuf.writeZero(ProtocolConstants.MAX_PAYLOAD_SIZE + 1);

      assertThatThrownBy(() -> channel.writeInbound(hugeBuf))
        .isInstanceOf(TooLongFrameException.class);

      assertThat(hugeBuf.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
