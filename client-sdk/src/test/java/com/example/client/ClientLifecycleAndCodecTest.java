package com.example.client;

import com.example.client.handler.*;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PingMessage;
import com.example.common.protocol.ClientEnvelopeCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClientLifecycleAndCodecTest {

  @BeforeAll
  static void enableParanoid() {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
  }

  @Test
  @DisplayName("ClientMessageEncoder + ClientEnvelopeCodec + ClientEnvelopePayloadDecoder round-trips without leaks")
  void testClientCodecPipelineZeroLeak() {
    EmbeddedChannel channel = new EmbeddedChannel(
      new ClientEnvelopeCodec(),
      new ClientEnvelopePayloadDecoder(),
      new ClientMessageEncoder()
    );
    try {
      PingMessage ping = new PingMessage(999L, "hello");
      assertTrue(channel.writeOutbound(ping));
      ByteBuf wireBuf = channel.readOutbound();
      assertNotNull(wireBuf);

      assertTrue(channel.writeInbound(wireBuf));
      PingMessage decoded = channel.readInbound();
      assertEquals(ping, decoded);
      assertEquals(0, wireBuf.refCnt());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("QuicClient handles failed connect cleanup and idempotent close safely")
  void testQuicClientCloseAndFailedConnect() throws Exception {
    ClientConfiguration config = () -> new ClientConfiguration.GatewayEndpoint() {
      @Override public int getPort() { return 1; }
      @Override public String getHostname() { return "127.0.0.1"; }
    };

    ClientState state = new ClientState();
    QuicClient client = new QuicClient(
      config,
      state,
      new LoginResponseHandler(state),
      new CharacterListResponseHandler(state),
      new CreateCharacterResponseHandler(state),
      new PongPacketHandler(state),
      new SelectCharacterResponseHandler(state),
      new ReconnectResponseHandler(state),
      new JoinWorldResponseHandler(state),
      new ZoneTransferNotificationHandler(state),
      new PlayerMovementResponseHandler(state),
      new ZoneStateDeltaHandler(state),
      new GatewayErrorResponseHandler(state)
    );

    try {
      assertFalse(client.isConnected());
      var future = client.connect();
      future.await(3, TimeUnit.SECONDS);
      // Either failed fast or timed out; close must cleanly release and remain safe when called repeatedly
      client.close();
      client.close();
      assertFalse(client.isConnected());
      assertEquals(ClientConnectionState.DISCONNECTED, state.getConnectionState());
    } finally {
      client.close();
    }
  }
}
