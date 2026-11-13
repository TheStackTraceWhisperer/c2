package com.example.server.entity;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

@MappedEntity(value = "npc_list", schema = "game")
public record NpcRecord(
    @Id
    long id,
    String name,
    @MappedProperty("pos_x")
    float posX,
    @MappedProperty("pos_y")
    float posY,
    @MappedProperty("pos_z")
    float posZ,
    @MappedProperty("pos_rot")
    short posRot,
    @MappedProperty("zone_id")
    int zoneId,
    String status,
    @MappedProperty("entity_flags")
    int entityFlags,
    @MappedProperty("animation_sub")
    short animationSub,
    @MappedProperty("name_prefix")
    short namePrefix,
    short moving,
    @Nullable
    @MappedProperty("look_type")
    String lookType,
    @MappedProperty("race_id")
    short raceId,
    @MappedProperty("face_id")
    short faceId,
    int head,
    int body,
    int hands,
    int legs,
    int feet,
    @MappedProperty("main_weapon")
    int mainWeapon,
    @MappedProperty("sub_shield")
    int subShield,
    int ranged,
    @MappedProperty("model_id")
    int modelId,
    boolean widescan
) {
  public float yawDegrees() {
    return (posRot & 0xFF) * 360.0f / 256.0f;
  }
}
