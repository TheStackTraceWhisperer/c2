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
  private float x;
  private float y;
  private float z;
  private float yaw;
  private float pitch;
  private long lastUpdatedTick;
}