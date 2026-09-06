package com.example.server.repository;

import com.example.server.entity.ActiveSessionRecord;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ActiveSessionJdbcRepository extends CrudRepository<ActiveSessionRecord, Long> {

  Optional<ActiveSessionRecord> findByAccountId(long accountId);

  Optional<ActiveSessionRecord> findBySessionId(long sessionId);

  @Query("""
      INSERT INTO player.active_sessions (account_id, session_id, gateway_node, character_id, login_time, last_ping)
      VALUES (:accountId, :sessionId, :gatewayNode, :characterId, :loginTime, :lastPing)
      ON CONFLICT (account_id) DO UPDATE SET
          session_id = EXCLUDED.session_id,
          gateway_node = EXCLUDED.gateway_node,
          character_id = EXCLUDED.character_id,
          login_time = EXCLUDED.login_time,
          last_ping = EXCLUDED.last_ping
      """)
  void upsert(long accountId, long sessionId, int gatewayNode, @Nullable Long characterId, Instant loginTime, Instant lastPing);

  @Query("UPDATE player.active_sessions SET character_id = :characterId, last_ping = NOW() WHERE account_id = :accountId")
  void updateCharacterId(long accountId, long characterId);

  @Query("DELETE FROM player.active_sessions WHERE session_id = :sessionId")
  void deleteBySessionId(long sessionId);

  @Query("DELETE FROM player.active_sessions WHERE account_id = :accountId")
  void deleteByAccountId(long accountId);
}