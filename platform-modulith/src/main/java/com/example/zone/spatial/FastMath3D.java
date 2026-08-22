package com.example.zone.spatial;

public final class FastMath3D {

  private FastMath3D() {}

  /**
   * Swept line-segment vs Oriented Bounding Box (OBB) intersection rotated around the Y axis.
   */
  public static boolean lineSegmentIntersectsObb(
    float sX, float sY, float sZ,
    float eX, float eY, float eZ,
    float cX, float cY, float cZ,
    float extX, float extY, float extZ,
    float yawDegrees) {

    double rad = Math.toRadians(-yawDegrees);
    float cos = (float) Math.cos(rad);
    float sin = (float) Math.sin(rad);

    float dsX = sX - cX;
    float dsZ = sZ - cZ;
    float localStartX = dsX * cos - dsZ * sin;
    float localStartY = sY - cY;
    float localStartZ = dsX * sin + dsZ * cos;

    float deX = eX - cX;
    float deZ = eZ - cZ;
    float localEndX = deX * cos - deZ * sin;
    float localEndY = eY - cY;
    float localEndZ = deX * sin + deZ * cos;

    return lineSegmentIntersectsAabb(
      localStartX, localStartY, localStartZ,
      localEndX, localEndY, localEndZ,
      -extX, -extY, -extZ,
      extX, extY, extZ
    );
  }

  public static boolean lineSegmentIntersectsAabb(
    float startX, float startY, float startZ,
    float endX, float endY, float endZ,
    float minX, float minY, float minZ,
    float maxX, float maxY, float maxZ) {

    float dirX = endX - startX;
    float dirY = endY - startY;
    float dirZ = endZ - startZ;

    float tMin = 0.0f;
    float tMax = 1.0f;

    // X Slab
    if (Math.abs(dirX) < 1e-7f) {
      if (startX < minX || startX > maxX) return false;
    } else {
      float invD = 1.0f / dirX;
      float t1 = (minX - startX) * invD;
      float t2 = (maxX - startX) * invD;
      if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
      tMin = Math.max(tMin, t1);
      tMax = Math.min(tMax, t2);
      if (tMin > tMax) return false;
    }

    // Y Slab
    if (Math.abs(dirY) < 1e-7f) {
      if (startY < minY || startY > maxY) return false;
    } else {
      float invD = 1.0f / dirY;
      float t1 = (minY - startY) * invD;
      float t2 = (maxY - startY) * invD;
      if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
      tMin = Math.max(tMin, t1);
      tMax = Math.min(tMax, t2);
      if (tMin > tMax) return false;
    }

    // Z Slab
    if (Math.abs(dirZ) < 1e-7f) {
      if (startZ < minZ || startZ > maxZ) return false;
    } else {
      float invD = 1.0f / dirZ;
      float t1 = (minZ - startZ) * invD;
      float t2 = (maxZ - startZ) * invD;
      if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
      tMin = Math.max(tMin, t1);
      tMax = Math.min(tMax, t2);
      if (tMin > tMax) return false;
    }

    return true;
  }
}