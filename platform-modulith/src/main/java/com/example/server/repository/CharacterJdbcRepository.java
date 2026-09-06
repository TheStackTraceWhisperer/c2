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
      UPDATE player.characters
      SET zone_id = :zoneId, pos_x = :posX, pos_y = :posY, pos_z = :posZ,
          rot_yaw = :rotYaw, rot_pitch = :rotPitch, last_saved_at = NOW()
      WHERE id = :characterId
      """)
  void updatePosition(long characterId, int zoneId, float posX, float posY, float posZ, float rotYaw, float rotPitch);

  @Query("""
      INSERT INTO player.characters (
          id, account_id, world_id, name,
          race_id, gender, face_id, hair_id, size_id, nation_id,
          main_job_id, main_job_level, sub_job_id, sub_job_level,
          zone_id, pos_x, pos_y, pos_z, rot_yaw, rot_pitch,
          hp_zone_id, hp_pos_x, hp_pos_y, hp_pos_z, last_saved_at
      ) VALUES (
          :characterId, :accountId, :worldId, :name,
          :raceId, :gender, :faceId, :hairId, :sizeId, :nationId,
          :mainJobId, :mainJobLevel, :subJobId, :subJobLevel,
          :zoneId, :posX, :posY, :posZ, :rotYaw, :rotPitch,
          :hpZoneId, :hpPosX, :hpPosY, :hpPosZ, NOW()
      )
      ON CONFLICT (id) DO UPDATE SET
          name = EXCLUDED.name,
          world_id = EXCLUDED.world_id,
          main_job_id = EXCLUDED.main_job_id,
          main_job_level = EXCLUDED.main_job_level,
          sub_job_id = EXCLUDED.sub_job_id,
          sub_job_level = EXCLUDED.sub_job_level,
          zone_id = EXCLUDED.zone_id,
          pos_x = EXCLUDED.pos_x,
          pos_y = EXCLUDED.pos_y,
          pos_z = EXCLUDED.pos_z,
          rot_yaw = EXCLUDED.rot_yaw,
          rot_pitch = EXCLUDED.rot_pitch,
          last_saved_at = NOW()
      """)
  void insertWithId(
    long characterId,
    long accountId,
    int worldId,
    String name,
    short raceId,
    short gender,
    short faceId,
    short hairId,
    short sizeId,
    short nationId,
    short mainJobId,
    short mainJobLevel,
    short subJobId,
    short subJobLevel,
    int zoneId,
    float posX,
    float posY,
    float posZ,
    float rotYaw,
    float rotPitch,
    int hpZoneId,
    float hpPosX,
    float hpPosY,
    float hpPosZ
  );

  @Query("DELETE FROM player.characters WHERE id NOT IN (1001, 1002, 1003)")
  void deleteTestCharacters();
}