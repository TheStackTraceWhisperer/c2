package com.example.support;

import com.example.client.ClientConfiguration;
import com.example.client.QuicClient;
import com.example.client.handler.*;
import com.example.client.state.ClientState;
import com.example.server.repository.AccountJdbcRepository;
import com.example.server.repository.CharacterJdbcRepository;
import com.example.server.security.Argon2PasswordEncoder;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.Map;

import static java.util.Map.entry;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test-driver")
public abstract class BaseIntegrationTest implements TestPropertyProvider {

  public static final long TEST_ACCOUNT_ID = 92668751L;
  public static final String TEST_USERNAME = "admin";
  public static final String TEST_PASSWORD = "secret123";

  @Value("${app.gateway.host:127.0.0.1}")
  private String gatewayHost;

  @Value("${app.gateway.port:8888}")
  private int gatewayPort;

  @Override
  public Map<String, String> getProperties() {
    return Map.ofEntries(
      entry("datasources.default.url", System.getProperty("datasources.default.url", "jdbc:postgresql://localhost:5432/mmo_platform")),
      entry("datasources.default.username", "postgres"),
      entry("datasources.default.password", "postgres"),
      entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
      entry("datasources.default.maximum-pool-size", "3"),
      entry("datasources.default.minimum-idle", "1"),
      entry("flyway.datasources.default.enabled", "false"),
      entry("app.gateway.host", System.getProperty("app.gateway.host", "127.0.0.1")),
      entry("app.gateway.port", System.getProperty("app.gateway.port", "8888")),
      entry("app.auth.private-key-path", System.getProperty("app.auth.private-key-path", "target/certs/jwt.key")),
      entry("app.auth.public-key-path", System.getProperty("app.auth.public-key-path", "target/certs/jwt.pub"))
    );
  }

  @Inject
  protected AccountJdbcRepository accountRepository;

  @Inject
  protected CharacterJdbcRepository characterRepository;

  @Inject
  protected Argon2PasswordEncoder passwordEncoder;

  protected QuicClient client;

  @BeforeEach
  protected void setUpBase() throws Exception {
    seedDatabase();

    ClientConfiguration clientConfig = () -> new ClientConfiguration.GatewayEndpoint() {
      @Override
      public int getPort() {
        return gatewayPort;
      }

      @Override
      public String getHostname() {
        return gatewayHost;
      }
    };

    ClientState state = new ClientState();
    this.client = new QuicClient(
      clientConfig,
      state,
      new LoginResponseHandler(state),
      new CharacterListResponseHandler(state),
      new PongPacketHandler(),
      new SelectCharacterResponseHandler(state),
      new ReconnectResponseHandler(state),
      new JoinWorldResponseHandler(state),
      new ZoneTransferNotificationHandler(state),
      new PlayerMovementResponseHandler(state),
      new ZoneStateDeltaHandler(state)
    );

    client.connect().sync();
  }

  protected void seedDatabase() {
    if (accountRepository.findByUsername(TEST_USERNAME).isEmpty()) {
      String encodedHash = passwordEncoder.encode(TEST_PASSWORD);
      accountRepository.insertWithId(
        TEST_ACCOUNT_ID,
        TEST_USERNAME,
        TEST_USERNAME + "@ffxi.internal",
        encodedHash,
        Instant.now(),
        Instant.now()
      );
    }

    // Zone 230: Southern San d'Oria (World 1)
    characterRepository.insertWithId(
      1001L, TEST_ACCOUNT_ID, 1, "ElvaanKnight",
      (short) 3, (short) 0, (short) 1, (short) 1, (short) 1, (short) 0,
      (short) 1, (short) 45, (short) 0, (short) 0,
      230, 0.0f, -2.0f, -10.0f, 90.0f, 0.0f,
      230, 0.0f, -2.0f, -10.0f
    );
    // Zone 230: Southern San d'Oria (World 1)
    characterRepository.insertWithId(
      1002L, TEST_ACCOUNT_ID, 1, "TarutaruMage",
      (short) 5, (short) 0, (short) 1, (short) 1, (short) 0, (short) 2,
      (short) 4, (short) 50, (short) 3, (short) 25,
      230, 2.0f, -2.0f, -10.0f, 0.0f, 0.0f,
      230, 0.0f, -2.0f, -10.0f
    );
    // Zone 234: Bastok Mines (World 2 - Unallocated Instance for Testing)
    characterRepository.insertWithId(
      1003L, TEST_ACCOUNT_ID, 2, "HumeRanger",
      (short) 1, (short) 0, (short) 1, (short) 1, (short) 1, (short) 1,
      (short) 6, (short) 30, (short) 1, (short) 15,
      234, -80.0f, 0.0f, -40.0f, 180.0f, 0.0f,
      234, -80.0f, 0.0f, -40.0f
    );
  }

  @AfterEach
  protected void tearDownBase() {
    if (client != null) {
      client.close();
    }
  }
}