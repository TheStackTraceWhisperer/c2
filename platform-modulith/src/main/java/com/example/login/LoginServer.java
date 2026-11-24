package com.example.login;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.MessageType;
import com.example.config.LoginServerProperties;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
import com.example.login.handler.LoginRequestHandler;
import com.example.login.handler.PingPacketHandler;
import com.example.login.handler.ReconnectRequestHandler;
import com.example.server.AbstractBackendServer;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = "login")
public class LoginServer extends AbstractBackendServer {

  private final LoginRequestHandler loginHandler;
  private final PingPacketHandler pingHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;
  private final ReconnectRequestHandler reconnectHandler;

  public LoginServer(
    LoginServerProperties properties,
    LoginRequestHandler loginHandler,
    PingPacketHandler pingHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler,
    ReconnectRequestHandler reconnectHandler) {
    super("LOGIN", properties.getHost(), properties.getPort());
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

  @Override
  protected void warmupCodecs() {
    super.warmupCodecs();
    var fory = ForySerializationProvider.get();
    fory.deserialize(fory.serialize(new LoginRequest("warmup", "warmup")));
    fory.deserialize(fory.serialize(new LoginResponse(true, 0L, "warmup", "")));
    fory.deserialize(fory.serialize(new ReconnectRequest("warmup")));
    fory.deserialize(fory.serialize(new ReconnectResponse(true, 0L, 0L, "")));
    var backendFory = BackendForySerializationProvider.get();
    backendFory.deserialize(backendFory.serialize(new SetSessionAuthStateControlMessage(true, 0L)));
  }
}