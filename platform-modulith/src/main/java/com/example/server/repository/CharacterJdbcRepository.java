package com.example.server.repository;

import com.example.server.entity.CharacterRecord;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface CharacterJdbcRepository extends CrudRepository<CharacterRecord, Long> {

  List<CharacterRecord> findByAccountId(long accountId);

  Optional<CharacterRecord> findByAccountIdAndCharacterId(long accountId, long characterId);

  @Query("""
      UPDATE characters
      SET zone_id = :zoneId, pos_x = :x, pos_y = :y, pos_z = :z, yaw = :yaw, pitch = :pitch, last_saved_at = NOW()
      WHERE id = :characterId
      """)
  void updatePosition(long characterId, int zoneId, float x, float y, float z, float yaw, float pitch);

  @Query("""
      INSERT INTO characters (id, account_id, realm_id, name, level, character_class, zone_id, pos_x, pos_y, pos_z, yaw, pitch, last_saved_at)
      VALUES (:characterId, :accountId, :worldId, :name, :level, :characterClass, :zoneId, :posX, :posY, :posZ, :yaw, :pitch, NOW())
      ON CONFLICT (id) DO UPDATE SET
          name = EXCLUDED.name,
          level = EXCLUDED.level,
          character_class = EXCLUDED.character_class,
          realm_id = EXCLUDED.realm_id,
          zone_id = EXCLUDED.zone_id,
          pos_x = EXCLUDED.pos_x,
          pos_y = EXCLUDED.pos_y,
          pos_z = EXCLUDED.pos_z,
          yaw = EXCLUDED.yaw,
          pitch = EXCLUDED.pitch,
          last_saved_at = NOW()
      """)
  void insertWithId(
    long characterId,
    long accountId,
    int worldId,
    String name,
    int level,
    String characterClass,
    int zoneId,
    float posX,
    float posY,
    float posZ,
    float yaw,
    float pitch
  );
}