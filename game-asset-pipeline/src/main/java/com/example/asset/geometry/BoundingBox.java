package com.example.asset.geometry;

/**
 * Axis-Aligned Bounding Box (AABB) defined by minimum and maximum extents.
 */
public record BoundingBox(Vector3f min, Vector3f max) {

    public static final BoundingBox EMPTY = new BoundingBox(
            new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    );

    public static BoundingBox read(com.example.asset.reader.DatByteReader reader) {
        return new BoundingBox(reader.nextVector3f(), reader.nextVector3f());
    }

    public Vector3f center() {
        return new Vector3f(
                (min.x() + max.x()) * 0.5f,
                (min.y() + max.y()) * 0.5f,
                (min.z() + max.z()) * 0.5f
        );
    }

    public Vector3f extents() {
        return new Vector3f(
                max.x() - min.x(),
                max.y() - min.y(),
                max.z() - min.z()
        );
    }

    public boolean contains(Vector3f p) {
        return p.x() >= min.x() && p.x() <= max.x()
                && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    public boolean intersects(BoundingBox other) {
        return this.min.x() <= other.max.x() && this.max.x() >= other.min.x()
                && this.min.y() <= other.max.y() && this.max.y() >= other.min.y()
                && this.min.z() <= other.max.z() && this.max.z() >= other.min.z();
    }

    public BoundingBox expand(Vector3f p) {
        return new BoundingBox(
                new Vector3f(Math.min(min.x(), p.x()), Math.min(min.y(), p.y()), Math.min(min.z(), p.z())),
                new Vector3f(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z()))
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;

        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        public Builder include(Vector3f p) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());

            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
            return this;
        }

        public BoundingBox build() {
            if (minX > maxX) {
                return EMPTY;
            }
            return new BoundingBox(new Vector3f(minX, minY, minZ), new Vector3f(maxX, maxY, maxZ));
        }
    }
}
