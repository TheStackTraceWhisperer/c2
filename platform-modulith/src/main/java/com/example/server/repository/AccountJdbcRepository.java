package com.example.server.repository;

import com.example.server.entity.AccountRecord;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface AccountJdbcRepository extends CrudRepository<AccountRecord, Long> {

  Optional<AccountRecord> findByUsername(String username);

  boolean existsByUsername(String username);

  @Query("""
      INSERT INTO player.accounts (id, username, email, password_hash, status, created_at, updated_at)
      VALUES (:id, :username, :email, :passwordHash, 'ACTIVE', :createdAt, :updatedAt)
      ON CONFLICT (id) DO UPDATE SET
          username = EXCLUDED.username,
          email = EXCLUDED.email,
          password_hash = EXCLUDED.password_hash,
          updated_at = EXCLUDED.updated_at
      """)
  void insertWithId(Long id, String username, String email, String passwordHash, Instant createdAt, Instant updatedAt);
}