package com.example.zone.dto;

import java.util.Locale;

public enum ActorLookType {
  NONE,
  EQUIPPED,
  STANDARD,
  CHOCOBO,
  AUTOMATON;

  public static ActorLookType fromDbValue(String dbLookType) {
    if (dbLookType == null) {
      return NONE;
    }
    String normalized = dbLookType.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return NONE;
    }
    return switch (normalized) {
      case "EQUIPPED" -> EQUIPPED;
      case "STANDARD" -> STANDARD;
      case "CHOCOBO" -> CHOCOBO;
      case "AUTOMATON" -> AUTOMATON;
      default -> NONE;
    };
  }
}
