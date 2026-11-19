package com.example.zone.engine;

import com.example.server.entity.NpcRecord;

/**
 * Stateless server-side filter determining whether an authentic LandSandBoat (LSB)
 * {@link NpcRecord} from {@code game.npc_list} should be spawned into the active zone simulation.
 *
 * <p>Filtering rules enforce that only visible, active actor NPCs are spawned into the spatial grid:
 * <ul>
 *   <li>{@code status} must be {@code "normal"} (case-insensitive), excluding {@code cutscene_only},
 *       {@code status_4}, {@code status_5}, and other non-active states.</li>
 *   <li>{@code look_type} must be one of {@code "equipped"}, {@code "standard"}, {@code "chocobo"},
 *       or {@code "automaton"} (case-insensitive), excluding environmental props/triggers such as
 *       {@code "door"} and {@code "ship"}.</li>
 *   <li>When {@code look_type} is {@code "standard"} or {@code "automaton"}, {@code model_id} must
 *       be strictly positive ({@code > 0}) to exclude unmodeled placeholder triggers.</li>
 *   <li>{@code name} must be non-null, non-blank, and not equal to {@code "blank"}
 *       (case-insensitive, trimmed), filtering out invisible {@code (0,0,0)} script triggers while
 *       preserving legitimate named entities such as {@code Chocobo}, {@code Well}, {@code Ceraule},
 *       {@code Aubejart}, {@code Ephauge}, and {@code Aldo}.</li>
 *   <li>Coordinates {@code (posX, posY, posZ)} must all be finite floats.</li>
 * </ul>
 *
 * <p><strong>Note on {@code animation_sub}:</strong> In LandSandBoat, {@code animation_sub} is an
 * entity sub-state flag present on normal standing NPCs (for example, many Zone 230 guards and
 * merchants have {@code animation_sub = 1}); it is <em>not</em> a sitting/kneeling exclusion flag
 * and must never be used to filter out or alter upright standing spawns.
 */
public final class NpcSpawnFilter {

  private NpcSpawnFilter() {
    // Stateless utility class
  }

  /**
   * Evaluates whether the given {@link NpcRecord} represents a valid, spawnable zone actor.
   *
   * @param record the database NPC record to evaluate (may be {@code null})
   * @return {@code true} if the NPC should be spawned into the zone simulation, {@code false} otherwise
   */
  public static boolean shouldSpawn(NpcRecord record) {
    if (record == null) {
      return false;
    }

    if (!"normal".equalsIgnoreCase(record.status())) {
      return false;
    }

    String lookType = record.lookType();
    if (lookType == null) {
      return false;
    }

    boolean isEquipped = "equipped".equalsIgnoreCase(lookType);
    boolean isStandard = "standard".equalsIgnoreCase(lookType);
    boolean isChocobo = "chocobo".equalsIgnoreCase(lookType);
    boolean isAutomaton = "automaton".equalsIgnoreCase(lookType);

    if (!isEquipped && !isStandard && !isChocobo && !isAutomaton) {
      return false;
    }

    if ((isStandard || isAutomaton) && record.modelId() <= 0) {
      return false;
    }

    String name = record.name();
    if (name == null) {
      return false;
    }
    String trimmedName = name.trim();
    if (trimmedName.isEmpty() || "blank".equalsIgnoreCase(trimmedName)) {
      return false;
    }

    return Float.isFinite(record.posX())
        && Float.isFinite(record.posY())
        && Float.isFinite(record.posZ());
  }
}
