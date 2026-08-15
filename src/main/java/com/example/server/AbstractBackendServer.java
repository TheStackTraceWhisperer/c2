package com.example.server;

import com.example.common.backend.protocol.BackendEnvelopeCodec;
import com.example.common.handler.InboundDispatchHandler;
import com.example.common.handler.IndexedPacketRouter;

import com.example.common.protocol.ProtocolConstants;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractBackendServer {

  protected final String serviceName;
  protected final int port;
  protected final IndexedPacketRouter router;

  private EventLoopGroup group;
  private Channel channel;

  public void start() throws Exception {
    group = new NioEventLoopGroup(1);

    SelfSignedCertificate cert = new SelfSignedCertificate();
    QuicSslContext sslContext = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
      .applicationProtocols(ProtocolConstants.ALPN_PROTOCOL)
      .build();

    ChannelHandler codec = new QuicServerCodecBuilder()
      .sslContext(sslContext)
      .maxIdleTimeout(30, TimeUnit.SECONDS)
      .initialMaxData(10000000)
      .initialMaxStreamDataBidirectionalLocal(1000000)
      .initialMaxStreamDataBidirectionalRemote(1000000)
      .initialMaxStreamsBidirectional(100)
      .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
      .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
        @Override
        protected void initChannel(QuicStreamChannel ch) {
          ch.pipeline().addLast(
            new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH, 0, 4, 0, 4),
            new LengthFieldPrepender(4),
            new BackendEnvelopeCodec(),
            new ServerEnvelopePayloadDecoder(),
            new ServerMessageEncoder(),
            new InboundDispatchHandler(router)
          );
        }
      })
      .build();

    Bootstrap bootstrap = new Bootstrap();
    channel = bootstrap.group(group)
      .channel(NioDatagramChannel.class)
      .handler(codec)
      .bind(new InetSocketAddress(port))
      .sync()
      .channel();

    log.info("[{}-SERVER] listening for QUIC UDP datagrams on port {}", serviceName, port);
  }

  @PreDestroy
  public void stop() {
    log.info("shutting down {} server...", serviceName);
    if (channel != null) channel.close();
    if (group != null) group.shutdownGracefully();
  }
}