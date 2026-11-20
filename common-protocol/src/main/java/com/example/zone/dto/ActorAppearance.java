package com.example.zone.dto;

public record ActorAppearance(
  ActorLookType lookType,
  int raceId,
  int faceId,
  int head,
  int body,
  int hands,
  int legs,
  int feet,
  int mainWeapon,
  int subShield,
  int ranged,
  int modelId,
  int size,
  int animationSub,
  int entityFlags,
  int namePrefix
) {
  public static final int SLOT_MODEL_MASK = 0x0FFF;

  public static final ActorAppearance NONE = new ActorAppearance(
    ActorLookType.NONE,
    0, 0,
    0, 0, 0, 0, 0, 0, 0, 0,
    0,
    1,
    0, 0, 0
  );

  public ActorAppearance {
    if (lookType == null) {
      lookType = ActorLookType.NONE;
    }
  }

  public static int stripSlotPrefix(int rawSlotWord) {
    return rawSlotWord & SLOT_MODEL_MASK;
  }

  public static ActorAppearance forPlayer(int raceId, int faceId, int size) {
    return new ActorAppearance(
      ActorLookType.EQUIPPED,
      raceId,
      faceId,
      0, 0, 0, 0, 0, 0, 0, 0,
      0,
      size,
      0, 0, 0
    );
  }

  public static ActorAppearance forStandardModel(int modelId, int animationSub, int entityFlags, int namePrefix) {
    return new ActorAppearance(
      ActorLookType.STANDARD,
      0, 0,
      0, 0, 0, 0, 0, 0, 0, 0,
      modelId,
      1,
      animationSub,
      entityFlags,
      namePrefix
    );
  }

  public static ActorAppearance fromRawNpcLook(
    String dbLookType,
    int raceId,
    int faceId,
    int rawHead,
    int rawBody,
    int rawHands,
    int rawLegs,
    int rawFeet,
    int rawMain,
    int rawSub,
    int rawRanged,
    int modelId,
    int animationSub,
    int entityFlags,
    int namePrefix
  ) {
    ActorLookType type = ActorLookType.fromDbValue(dbLookType);
    return switch (type) {
      case EQUIPPED -> new ActorAppearance(
        ActorLookType.EQUIPPED,
        raceId,
        faceId,
        stripSlotPrefix(rawHead),
        stripSlotPrefix(rawBody),
        stripSlotPrefix(rawHands),
        stripSlotPrefix(rawLegs),
        stripSlotPrefix(rawFeet),
        stripSlotPrefix(rawMain),
        stripSlotPrefix(rawSub),
        stripSlotPrefix(rawRanged),
        0,
        1,
        animationSub,
        entityFlags,
        namePrefix
      );
      case CHOCOBO -> {
        int resolvedRace = (raceId >= 32 && raceId <= 36)
          ? raceId
          : ((faceId >= 32 && faceId <= 36) ? faceId : 32);
        yield new ActorAppearance(
          ActorLookType.CHOCOBO,
          resolvedRace,
          0,
          stripSlotPrefix(rawHead),
          stripSlotPrefix(rawBody),
          stripSlotPrefix(rawHands),
          stripSlotPrefix(rawLegs),
          stripSlotPrefix(rawFeet),
          0,
          0,
          0,
          0,
          1,
          animationSub,
          entityFlags,
          namePrefix
        );
      }
      case STANDARD, AUTOMATON -> new ActorAppearance(
        type,
        0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        modelId,
        1,
        animationSub,
        entityFlags,
        namePrefix
      );
      case NONE -> NONE;
    };
  }
}
