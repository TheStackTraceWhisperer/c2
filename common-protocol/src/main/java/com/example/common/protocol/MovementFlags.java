package com.example.common.protocol;

@SuppressWarnings("PointlessBitwiseExpression")
public final class MovementFlags {
  public static final int IDLE = 0;
  public static final int FORWARD = 1 << 0;
  public static final int BACKWARD = 1 << 1;
  public static final int STRAFE_LEFT = 1 << 2;
  public static final int STRAFE_RIGHT = 1 << 3;
  public static final int JUMP = 1 << 4;
  public static final int FALLING = 1 << 5;

  private MovementFlags() {}
}