package com.example.server.repository;

import com.example.server.entity.CharacterRecord;
import jakarta.inject.Singleton;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class CharacterRepository {

  private final Map<Long, CharacterRecord> charactersById = new ConcurrentHashMap<>();
  private final Map<Long, List<Long>> accountCharacterMap = new ConcurrentHashMap<>();

  public CharacterRepository() {
    save(new CharacterRecord(
      92668751L,
      1001L,
      "Warrior",
      45,
      "WARRIOR",
      1,
      1,
      100.0f, 64.0f, -250.5f,
      90.0f, 0.0f
    ));

    save(new CharacterRecord(
      92668751L,
      1002L,
      "Mage",
      50,
      "WIZARD",
      1,
      1,
      0.0f, 0.0f, 0.0f,
      0.0f, 0.0f
    ));

    save(new CharacterRecord(
      92668751L,
      1003L,
      "Rogue",
      30,
      "ROGUE",
      2,
      2,
      550.0f, 70.0f, -990.0f,
      180.0f, 0.0f
    ));
  }

  public Optional<CharacterRecord> findById(long characterId) {
    return Optional.ofNullable(charactersById.get(characterId));
  }

  public List<CharacterRecord> findByAccountId(long accountId) {
    List<Long> ids = accountCharacterMap.getOrDefault(accountId, Collections.emptyList());
    List<CharacterRecord> result = new ArrayList<>();
    for (Long id : ids) {
      CharacterRecord record = charactersById.get(id);
      if (record != null) {
        result.add(record);
      }
    }
    return result;
  }

  public Optional<CharacterRecord> findByAccountAndCharacterId(long accountId, long characterId) {
    return findById(characterId).filter(c -> c.accountId() == accountId);
  }

  public void save(CharacterRecord record) {
    charactersById.put(record.characterId(), record);
    accountCharacterMap.computeIfAbsent(record.accountId(), k -> new ArrayList<>())
      .removeIf(id -> id == record.characterId());
    accountCharacterMap.get(record.accountId()).add(record.characterId());
  }

  public void updatePosition(long characterId, int zoneId, float x, float y, float z, float yaw, float pitch) {
    charactersById.computeIfPresent(characterId, (id, old) -> new CharacterRecord(
      old.accountId(),
      old.characterId(),
      old.name(),
      old.level(),
      old.characterClass(),
      old.worldId(),
      zoneId,
      x, y, z,
      yaw, pitch
    ));
  }

  public void clear() {
    charactersById.clear();
    accountCharacterMap.clear();
  }
}