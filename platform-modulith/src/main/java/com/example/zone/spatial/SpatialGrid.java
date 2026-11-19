package com.example.zone.spatial;

import jakarta.inject.Singleton;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class SpatialGrid {

  private static final float CELL_SIZE = 50.0f;

  // Packed (cellX, cellZ) -> Set of Character IDs
  private final Map<Long, Set<Long>> cells = new ConcurrentHashMap<>();
  // Character ID -> Current packed cell key
  private final Map<Long, Long> entityCells = new ConcurrentHashMap<>();

  public static long packCellKey(int cellX, int cellZ) {
    return (((long) cellX) << 32) | (cellZ & 0xFFFFFFFFL);
  }

  public int getCellCoord(float pos) {
    return (int) Math.floor(pos / CELL_SIZE);
  }

  /**
   * Updates an entity's cell assignment based on new local coordinates.
   */
  public void updatePosition(long characterId, float x, float z) {
    int cellX = getCellCoord(x);
    int cellZ = getCellCoord(z);
    long newKey = packCellKey(cellX, cellZ);

    Long oldKey = entityCells.put(characterId, newKey);
    if (oldKey != null && oldKey.longValue() == newKey) {
      Set<Long> currentCell = cells.get(newKey);
      if (currentCell != null && currentCell.contains(characterId)) {
        return;
      }
    }
    if (oldKey != null && oldKey.longValue() != newKey) {
      pruneFromCell(oldKey, characterId);
    }

    cells.compute(newKey, (k, existing) -> {
      Set<Long> set = (existing != null) ? existing : ConcurrentHashMap.newKeySet();
      set.add(characterId);
      return set;
    });
  }

  /**
   * Finds all character IDs within the 3x3 surrounding cells of (x, z).
   */
  public Set<Long> getNearbyEntities(float x, float z) {
    int centerCellX = getCellCoord(x);
    int centerCellZ = getCellCoord(z);

    Set<Long> nearby = new HashSet<>();
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        long key = packCellKey(centerCellX + dx, centerCellZ + dz);
        Set<Long> cellEntities = cells.get(key);
        if (cellEntities != null) {
          nearby.addAll(cellEntities);
        }
      }
    }
    return nearby;
  }

  public void removeEntity(long characterId) {
    Long cellKey = entityCells.remove(characterId);
    if (cellKey != null) {
      pruneFromCell(cellKey, characterId);
    }
  }

  private void pruneFromCell(long cellKey, long characterId) {
    cells.computeIfPresent(cellKey, (k, cell) -> {
      cell.remove(characterId);
      return cell.isEmpty() ? null : cell;
    });
  }

  public int getCellCount() {
    return cells.size();
  }

  public void clear() {
    cells.clear();
    entityCells.clear();
  }
}