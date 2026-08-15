package com.example.login;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.login.handler.LoginRequestHandler;
import com.example.login.handler.PingPacketHandler;
import com.example.login.handler.ReconnectRequestHandler;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
public class LoginServer extends AbstractBackendServer {

  private final LoginRequestHandler loginHandler;
  private final PingPacketHandler pingHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;
  private final ReconnectRequestHandler reconnectHandler;

  public LoginServer(
    DemoProperties properties,
    IndexedPacketRouter router,
    LoginRequestHandler loginHandler,
    PingPacketHandler pingHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler,
    ReconnectRequestHandler reconnectHandler) {
    super("LOGIN", properties.getLogin().getPort(), router);
    this.loginHandler = loginHandler;
    this.pingHandler = pingHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
    this.reconnectHandler = reconnectHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.LOGIN_REQUEST, loginHandler);
    router.register(MessageType.PING, pingHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);
    router.register(MessageType.RECONNECT_REQUEST, reconnectHandler);
    start();
  }
}