package com.example.world.state;

import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class WorldSessionRegistry {

  // Maps sessionId -> characterId
  private final Map<Long, Long> sessionCharacters = new ConcurrentHashMap<>();

  public void bindCharacter(long sessionId, long characterId) {
    sessionCharacters.put(sessionId, characterId);
  }

  public long getCharacterId(long sessionId) {
    return sessionCharacters.getOrDefault(sessionId, 0L);
  }

  public void removeSession(long sessionId) {
    sessionCharacters.remove(sessionId);
  }
}