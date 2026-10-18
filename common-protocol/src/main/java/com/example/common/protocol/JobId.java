package com.example.common.protocol;

public final class JobId {
  public static final short NONE = 0;

  // Starter Jobs
  public static final short WARRIOR = 1;
  public static final short MONK = 2;
  public static final short WHITE_MAGE = 3;
  public static final short BLACK_MAGE = 4;
  public static final short RED_MAGE = 5;
  public static final short THIEF = 6;

  // Advanced Jobs
  public static final short PALADIN = 7;
  public static final short DARK_KNIGHT = 8;
  public static final short BEASTMASTER = 9;
  public static final short BARD = 10;
  public static final short RANGER = 11;
  public static final short SAMURAI = 12;
  public static final short NINJA = 13;
  public static final short DRAGOON = 14;
  public static final short SUMMONER = 15;
  public static final short BLUE_MAGE = 16;
  public static final short CORSAIR = 17;
  public static final short PUPPETMASTER = 18;
  public static final short DANCER = 19;
  public static final short SCHOLAR = 20;
  public static final short GEOMANCER = 21;
  public static final short RUNE_FENCER = 22;
  public static final short NPC = 99;

  public static String toAbbreviation(short jobId) {
    return switch (jobId) {
      case WARRIOR -> "WAR";
      case MONK -> "MNK";
      case WHITE_MAGE -> "WHM";
      case BLACK_MAGE -> "BLM";
      case RED_MAGE -> "RDM";
      case THIEF -> "THF";
      case PALADIN -> "PLD";
      case DARK_KNIGHT -> "DRK";
      case BEASTMASTER -> "BST";
      case BARD -> "BRD";
      case RANGER -> "RNG";
      case SAMURAI -> "SAM";
      case NINJA -> "NIN";
      case DRAGOON -> "DRG";
      case SUMMONER -> "SMN";
      case BLUE_MAGE -> "BLU";
      case CORSAIR -> "COR";
      case PUPPETMASTER -> "PUP";
      case DANCER -> "DNC";
      case SCHOLAR -> "SCH";
      case GEOMANCER -> "GEO";
      case RUNE_FENCER -> "RUN";
      case NPC -> "NPC";
      default -> "NON";
    };
  }

  private JobId() {}
}