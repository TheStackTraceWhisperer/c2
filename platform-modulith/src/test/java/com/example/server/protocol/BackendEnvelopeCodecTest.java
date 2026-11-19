package com.example.server.protocol;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.dto.PingMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.login.dto.LoginRequest;
import com.example.server.ServerEnvelopePayloadDecoder;
import com.example.server.ServerMessageEncoder;
import com.example.server.SessionContext;
import com.example.server.codec.BackendForySerializationProvider;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendEnvelopeCodecTest {

  @BeforeAll
  static void enableParanoidLeakDetection() {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
  }

  @Test
  @DisplayName("BackendEnvelope delegates ReferenceCounted safely including null payload")
  void testBackendEnvelopeReferenceCounted() {
    BackendEnvelope nullEnv = new BackendEnvelope(1L, 2L, ServiceType.AUTH, MessageType.PING, null);
    assertThat(nullEnv.refCnt()).isZero();
    assertThat(nullEnv.retain()).isSameAs(nullEnv);
    assertThat(nullEnv.retain(2)).isSameAs(nullEnv);
    assertThat(nullEnv.touch()).isSameAs(nullEnv);
    assertThat(nullEnv.touch("hint")).isSameAs(nullEnv);
    assertThat(nullEnv.release()).isTrue();
    assertThat(nullEnv.release(2)).isTrue();

    ByteBuf buf = Unpooled.copiedBuffer("xyz", StandardCharsets.UTF_8);
    BackendEnvelope env = new BackendEnvelope(10L, 20L, ServiceType.ZONE, MessageType.PLAYER_MOVEMENT_REQUEST, buf);
    assertThat(env.refCnt()).isEqualTo(1);
    assertThat(env.retain()).isSameAs(env);
    assertThat(env.refCnt()).isEqualTo(2);
    assertThat(env.release()).isFalse();
    assertThat(env.release()).isTrue();
    assertThat(env.refCnt()).isZero();
  }

  @Test
  @DisplayName("BackendEnvelopeCodec encode/decode leaves refCnt == 0")
  void testEncodeDecodeZeroLeak() {
    EmbeddedChannel channel = new EmbeddedChannel(new BackendEnvelopeCodec());
    try {
      ByteBuf payload = channel.alloc().buffer(8).writeBytes("data".getBytes(StandardCharsets.UTF_8));
      BackendEnvelope outbound = new BackendEnvelope(101L, 202L, ServiceType.LOBBY, MessageType.CHARACTER_LIST_REQUEST, payload);

      assertThat(channel.writeOutbound(outbound)).isTrue();
      assertThat(outbound.refCnt()).isZero();
      assertThat(payload.refCnt()).isZero();

      ByteBuf encoded = channel.readOutbound();
      assertThat(encoded).isNotNull();

      assertThat(channel.writeInbound(encoded)).isTrue();
      BackendEnvelope decoded = channel.readInbound();
      assertThat(decoded).isNotNull();
      assertThat(decoded.sessionId()).isEqualTo(101L);
      assertThat(decoded.characterId()).isEqualTo(202L);
      assertThat(decoded.serviceId()).isEqualTo(ServiceType.LOBBY);
      assertThat(decoded.messageId()).isEqualTo(MessageType.CHARACTER_LIST_REQUEST);
      assertThat(decoded.payload().toString(StandardCharsets.UTF_8)).isEqualTo("data");

      assertThat(decoded.release()).isTrue();
      assertThat(decoded.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("BackendEnvelopeCodec short frame throws CorruptedFrameException and oversized encode releases once")
  void testShortFrameAndOversizedEncode() {
    EmbeddedChannel channel = new EmbeddedChannel(new BackendEnvelopeCodec());
    try {
      ByteBuf shortFrame = channel.alloc().buffer(10).writeZero(10);
      assertThatThrownBy(() -> channel.writeInbound(shortFrame))
        .isInstanceOf(CorruptedFrameException.class);
      assertThat(shortFrame.refCnt()).isZero();

      ByteBuf tooLongFrame = channel.alloc().buffer(ProtocolConstants.BACKEND_HEADER_SIZE + ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      tooLongFrame.writeLong(1L);
      tooLongFrame.writeLong(2L);
      tooLongFrame.writeShort(ServiceType.AUTH);
      tooLongFrame.writeShort(MessageType.PING);
      tooLongFrame.writeZero(ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      assertThatThrownBy(() -> channel.writeInbound(tooLongFrame))
        .isInstanceOf(TooLongFrameException.class);
      assertThat(tooLongFrame.refCnt()).isZero();

      ByteBuf oversizedOutbound = channel.alloc().buffer(ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      oversizedOutbound.writeZero(ProtocolConstants.MAX_PAYLOAD_SIZE + 1);
      BackendEnvelope outbound = new BackendEnvelope(1L, 1L, ServiceType.AUTH, MessageType.PING, oversizedOutbound);
      assertThatThrownBy(() -> channel.writeOutbound(outbound))
        .isInstanceOf(EncoderException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
      assertThat(oversizedOutbound.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("ServerEnvelopePayloadDecoder isolates client Fory vs internal control Fory without leaks")
  void testServerEnvelopePayloadDecoderIsolationAndZeroLeak() {
    EmbeddedChannel channel = new EmbeddedChannel(
      new BackendEnvelopeCodec(),
      new ServerEnvelopePayloadDecoder(),
      new ServerMessageEncoder()
    );
    try {
      channel.attr(SessionContext.SESSION_ID).set(777L);
      channel.attr(SessionContext.CHARACTER_ID).set(888L);

      // 1. Outbound round-trip for normal client message
      PingMessage ping = new PingMessage(1234L, "ping");
      assertThat(channel.writeOutbound(ping)).isTrue();
      ByteBuf wirePing = channel.readOutbound();
      assertThat(wirePing).isNotNull();
      assertThat(channel.writeInbound(wirePing)).isTrue();
      assertThat((Object) channel.readInbound()).isEqualTo(ping);

      // 2. Internal disconnect frame decodes via BackendForySerializationProvider
      byte[] disconnectBytes = BackendForySerializationProvider.get()
        .serialize(new SessionDisconnectedEvent(777L, 555L, true));
      ByteBuf disconnectPayload = channel.alloc().buffer(disconnectBytes.length).writeBytes(disconnectBytes);
      BackendEnvelope disconnectEnv = new BackendEnvelope(
        777L, 888L, ServiceType.ZONE, MessageType.SESSION_DISCONNECTED, disconnectPayload
      );
      assertThat(channel.writeInbound(disconnectEnv)).isTrue();
      Object decodedDisconnect = channel.readInbound();
      assertThat(decodedDisconnect).isEqualTo(new SessionDisconnectedEvent(777L, 555L, true));
      assertThat(disconnectPayload.refCnt()).isZero();

      // 3. Forged internal SessionDisconnectedEvent disguised under LOGIN_REQUEST fails Fory decode and leaks nothing
      ByteBuf forgedPayload = channel.alloc().buffer(disconnectBytes.length).writeBytes(disconnectBytes);
      BackendEnvelope forgedEnv = new BackendEnvelope(
        777L, 0L, ServiceType.AUTH, MessageType.LOGIN_REQUEST, forgedPayload
      );
      assertThatThrownBy(() -> channel.writeInbound(forgedEnv))
        .isInstanceOf(DecoderException.class);
      assertThat(forgedPayload.refCnt()).isZero();

      // 4. Normal client LoginRequest decoded cleanly
      byte[] loginBytes = ForySerializationProvider.get().serialize(new LoginRequest("alice", "pw"));
      ByteBuf loginPayload = channel.alloc().buffer(loginBytes.length).writeBytes(loginBytes);
      BackendEnvelope loginEnv = new BackendEnvelope(
        777L, 0L, ServiceType.AUTH, MessageType.LOGIN_REQUEST, loginPayload
      );
      assertThat(channel.writeInbound(loginEnv)).isTrue();
      assertThat((Object) channel.readInbound()).isEqualTo(new LoginRequest("alice", "pw"));
      assertThat(loginPayload.refCnt()).isZero();
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
