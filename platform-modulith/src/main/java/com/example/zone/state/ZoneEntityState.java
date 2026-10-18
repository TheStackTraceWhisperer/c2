package com.example.zone.state;

import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public class ZoneEntityState {
  private final long sessionId;
  private final long characterId;
  private String name = "Player";
  private short mainJobId = 1;
  private short subJobId = 0;
  private int level = 1;
  private int zoneId;

  // Kinematics
  private float x;
  private float y;
  private float z;
  private float vx;
  private float vy;
  private float vz;
  private float yaw;
  private float pitch;
  private int moveFlags;

  private long lastUpdatedTick;
  private boolean dirty;
  private ChannelHandlerContext channelContext;

  private boolean npc;

  // Observed entity set for AoI differencing
  private final Set<Long> knownEntities = ConcurrentHashMap.newKeySet();

  public ZoneEntityState(long sessionId, long characterId, int zoneId,
                         float x, float y, float z,
                         float vx, float vy, float vz,
                         float yaw, float pitch, int moveFlags,
                         long lastUpdatedTick, ChannelHandlerContext ctx) {
    this.sessionId = sessionId;
    this.characterId = characterId;
    this.zoneId = zoneId;
    this.x = x;
    this.y = y;
    this.z = z;
    this.vx = vx;
    this.vy = vy;
    this.vz = vz;
    this.yaw = yaw;
    this.pitch = pitch;
    this.moveFlags = moveFlags;
    this.lastUpdatedTick = lastUpdatedTick;
    this.dirty = true;
    this.channelContext = ctx;
    this.npc = false;
  }

  public static ZoneEntityState createNpc(long npcEntityId, int zoneId, String name,
                                         float x, float y, float z, float yaw) {
    ZoneEntityState state = new ZoneEntityState(
      0L, npcEntityId, zoneId,
      x, y, z,
      0f, 0f, 0f,
      yaw, 0f, 0,
      0L, null
    );
    state.setName(name);
    state.setMainJobId(com.example.common.protocol.JobId.NPC);
    state.setSubJobId((short) 0);
    state.setLevel(1);
    state.setNpc(true);
    state.setDirty(false);
    return state;
  }
}