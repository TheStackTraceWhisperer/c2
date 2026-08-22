package com.example.server.repository;

import com.example.server.entity.AccountRecord;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

@Singleton
@RequiredArgsConstructor
public class AccountRepository {

  private final AccountJdbcRepository jdbcRepository;

  public Optional<AccountRecord> findById(long accountId) {
    return jdbcRepository.findById(accountId);
  }

  public Optional<AccountRecord> findByUsername(String username) {
    return jdbcRepository.findByUsername(username);
  }

  public boolean existsByUsername(String username) {
    return jdbcRepository.existsByUsername(username);
  }

  public AccountRecord save(AccountRecord record) {
    if (record.id() != null && record.id() > 0L) {
      jdbcRepository.insertWithId(
        record.id(),
        record.username(),
        record.email() != null ? record.email() : record.username() + "@example.internal",
        record.passwordHash(),
        record.createdAt() != null ? record.createdAt() : Instant.now(),
        record.updatedAt() != null ? record.updatedAt() : Instant.now()
      );
      return record;
    }
    return jdbcRepository.save(record);
  }
}