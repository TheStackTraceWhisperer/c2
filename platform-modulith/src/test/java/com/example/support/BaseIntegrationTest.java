package com.example.support;

import com.example.client.QuicClient;
import com.example.server.entity.AccountRecord;
import com.example.server.entity.CharacterRecord;
import com.example.server.repository.AccountRepository;
import com.example.server.repository.CharacterRepository;
import com.example.server.security.Argon2PasswordEncoder;
import io.micronaut.context.BeanContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;

@MicronautTest
public abstract class BaseIntegrationTest {

  public static final long TEST_ACCOUNT_ID = 92668751L;
  public static final String TEST_USERNAME = "admin";
  public static final String TEST_PASSWORD = "secret123";

  @Inject
  protected BeanContext beanContext;

  @Inject
  protected QuicClient client;

  @Inject
  protected AccountRepository accountRepository;

  @Inject
  protected CharacterRepository characterRepository;

  @Inject
  protected Argon2PasswordEncoder passwordEncoder;

  @BeforeEach
  protected void setUpBase() throws Exception {
    seedDatabase();
    client.getState().reset();
    client.registerDefaultHandlers();
    client.connect().sync();
  }

  protected void seedDatabase() {
    if (accountRepository.findByUsername(TEST_USERNAME).isEmpty()) {
      String encodedHash = passwordEncoder.encode(TEST_PASSWORD);
      accountRepository.save(new AccountRecord(
        TEST_ACCOUNT_ID,
        TEST_USERNAME,
        encodedHash,
        Instant.now(),
        Instant.now()
      ));
    }

    characterRepository.save(new CharacterRecord(
      TEST_ACCOUNT_ID, 1001L, "Warrior", 45, "WARRIOR", 1, 1, 100.0f, 64.0f, -250.5f, 90.0f, 0.0f
    ));
    characterRepository.save(new CharacterRecord(
      TEST_ACCOUNT_ID, 1002L, "Mage", 50, "WIZARD", 1, 1, 102.0f, 64.0f, -250.5f, 0.0f, 0.0f
    ));
    characterRepository.save(new CharacterRecord(
      TEST_ACCOUNT_ID, 1003L, "Rogue", 30, "ROGUE", 2, 2, 550.0f, 70.0f, -990.0f, 180.0f, 0.0f
    ));
  }

  @AfterEach
  protected void tearDownBase() {
    client.close();
  }
}