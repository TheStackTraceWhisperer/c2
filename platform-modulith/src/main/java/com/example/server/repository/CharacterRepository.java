package com.example.server.repository;

import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class CharacterRepository {

  private final CharacterJdbcRepository jdbcRepository;

  public Optional<CharacterRecord> findById(long characterId) {
    return jdbcRepository.findById(characterId);
  }

  public List<CharacterRecord> findByAccountId(long accountId) {
    return jdbcRepository.findByAccountId(accountId);
  }

  public Optional<CharacterRecord> findByAccountAndCharacterId(long accountId, long characterId) {
    return jdbcRepository.findByAccountIdAndCharacterId(accountId, characterId);
  }

  public CharacterRecord save(CharacterRecord record) {
    if (record.characterId() > 0L) {
      jdbcRepository.insertWithId(
        record.characterId(),
        record.accountId(),
        record.worldId(),
        record.name(),
        record.level(),
        record.characterClass(),
        record.zoneId(),
        record.posX(),
        record.posY(),
        record.posZ(),
        record.yaw(),
        record.pitch()
      );
      return record;
    }
    return jdbcRepository.save(record);
  }

  public void updatePosition(long characterId, int zoneId, float x, float y, float z, float yaw, float pitch) {
    jdbcRepository.updatePosition(characterId, zoneId, x, y, z, yaw, pitch);
  }

  /**
   * Persists a batch of dirty entity snapshots to PostgreSQL.
   */
  public void flushStateBatch(Collection<CharacterStateSnapshot> snapshots) {
    if (snapshots == null || snapshots.isEmpty()) {
      return;
    }
    for (CharacterStateSnapshot s : snapshots) {
      try {
        jdbcRepository.updatePosition(s.characterId(), s.zoneId(), s.x(), s.y(), s.z(), s.yaw(), s.pitch());
      } catch (Exception e) {
        log.error("[CHARACTER-REPO] Failed to flush snapshot for character #{}: {}", s.characterId(), e.getMessage(), e);
      }
    }
    log.debug("[CHARACTER-REPO] Flushed {} dirty character checkpoints to PostgreSQL.", snapshots.size());
  }
}