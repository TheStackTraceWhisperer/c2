package com.example.world.registry;

import com.example.server.protocol.EndpointTarget;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class WorldSessionRegistry {

  @Getter
  @Setter
  @AllArgsConstructor
  public static class WorldPlayerSession {
    private final long sessionId;
    private final long characterId;
    private int currentZoneId;
    private EndpointTarget currentZoneEndpoint;
    private long joinedTimestamp;
    private ChannelHandlerContext context;
  }

  private final Map<Long, WorldPlayerSession> sessions = new ConcurrentHashMap<>();
  private final Map<Long, Long> characterToSession = new ConcurrentHashMap<>();

  public void registerSession(long sessionId, long characterId, int initialZoneId, EndpointTarget initialZoneEndpoint, ChannelHandlerContext ctx) {
    WorldPlayerSession session = new WorldPlayerSession(
      sessionId,
      characterId,
      initialZoneId,
      initialZoneEndpoint,
      System.currentTimeMillis(),
      ctx
    );
    sessions.put(sessionId, session);
    characterToSession.put(characterId, sessionId);
  }

  public WorldPlayerSession getSession(long sessionId) {
    return sessions.get(sessionId);
  }

  public WorldPlayerSession getByCharacterId(long characterId) {
    Long sid = characterToSession.get(characterId);
    return (sid != null) ? sessions.get(sid) : null;
  }

  public void updateZone(long sessionId, int newZoneId, EndpointTarget newZoneEndpoint) {
    WorldPlayerSession session = sessions.get(sessionId);
    if (session != null) {
      session.setCurrentZoneId(newZoneId);
      session.setCurrentZoneEndpoint(newZoneEndpoint);
    }
  }

  public void removeSession(long sessionId) {
    WorldPlayerSession session = sessions.remove(sessionId);
    if (session != null) {
      characterToSession.remove(session.getCharacterId());
    }
  }

  public void clear() {
    sessions.clear();
    characterToSession.clear();
  }
}