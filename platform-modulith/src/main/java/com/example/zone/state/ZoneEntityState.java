package com.example.zone.state;

import com.example.common.protocol.RaceId;
import com.example.zone.dto.ActorAppearance;
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
  private long displacementTick;
  private double tickDisplacement;
  private int worldId = 1;
  private boolean dirty;
  private boolean networkDirty;
  private ChannelHandlerContext channelContext;

  private boolean npc;

  // Authentic NPC appearance, animation, and behavior metadata
  private int entityFlags;
  private int sizeId = 1;
  private int animationSub;
  private int namePrefix;
  private int moving;
  private String lookType;
  private int raceId;
  private int faceId;
  private int head;
  private int body;
  private int hands;
  private int legs;
  private int feet;
  private int mainWeapon;
  private int subShield;
  private int ranged;
  private int modelId;
  private boolean widescan;

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
    this.networkDirty = false;
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
    state.setNetworkDirty(false);
    return state;
  }

  public static ZoneEntityState createNpc(com.example.server.entity.NpcRecord record) {
    ZoneEntityState state = createNpc(
      record.id(),
      record.zoneId(),
      record.name(),
      record.posX(),
      record.posY(),
      record.posZ(),
      record.yawDegrees()
    );
    state.setEntityFlags(record.entityFlags());
    state.setAnimationSub(record.animationSub());
    state.setNamePrefix(record.namePrefix());
    state.setMoving(record.moving());
    state.setLookType(record.lookType());
    state.setRaceId(record.raceId());
    state.setFaceId(record.faceId());
    state.setHead(record.head());
    state.setBody(record.body());
    state.setHands(record.hands());
    state.setLegs(record.legs());
    state.setFeet(record.feet());
    state.setMainWeapon(record.mainWeapon());
    state.setSubShield(record.subShield());
    state.setRanged(record.ranged());
    state.setModelId(record.modelId());
    state.setWidescan(record.widescan());
    return state;
  }

  public ActorAppearance toActorAppearance() {
    if (npc) {
      return ActorAppearance.fromRawNpcLook(
        lookType, raceId, faceId,
        head, body, hands, legs, feet,
        mainWeapon, subShield, ranged,
        modelId, animationSub, entityFlags, namePrefix
      );
    }
    return ActorAppearance.forPlayer(
      raceId > 0 ? raceId : RaceId.HUME_MALE,
      faceId,
      sizeId
    );
  }
}