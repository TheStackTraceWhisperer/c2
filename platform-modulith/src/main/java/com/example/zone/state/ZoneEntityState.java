package com.example.zone.state;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ZoneEntityState {
  private final long sessionId;
  private final long characterId;
  private int zoneId;
  private float x;
  private float y;
  private float z;
  private float yaw;
  private float pitch;
  private long lastUpdatedTick;
  private boolean dirty;

  public ZoneEntityState(long sessionId, long characterId, int zoneId, float x, float y, float z, float yaw, float pitch, long lastUpdatedTick) {
    this(sessionId, characterId, zoneId, x, y, z, yaw, pitch, lastUpdatedTick, true);
  }
}