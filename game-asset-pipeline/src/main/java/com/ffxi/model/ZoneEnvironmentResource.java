package com.ffxi.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Parsed {@code S2F_Environment} ({@code 0x2F}) section defining retail diurnal lighting groups
 * (actor and background/terrain), fog parameters, clear color, and the 8-ring hemispherical sky palette.
 */
public record ZoneEnvironmentResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int flags,
        LightGroup actorGroup,
        LightGroup bgGroup,
        int clearR,
        int clearG,
        int clearB,
        int clearA,
        float drawDist,
        boolean hasDome,
        int spokeCount,
        float skyRadius,
        int[] skyRgba,
        float[] skyStops
) implements ResourceNode {

    public static final int PAYLOAD_SIZE = 0xAC;
    public static final int SKY_SLICE_COUNT = 8;

    /**
     * One lighting &amp; fog parameter group (+0x0C for actors, +0x2C for scenery/bg).
     */
    public record LightGroup(
            int sunR,
            int sunG,
            int sunB,
            int sunA,
            int moonR,
            int moonG,
            int moonB,
            int moonA,
            int ambientR,
            int ambientG,
            int ambientB,
            int ambientA,
            int fogR,
            int fogG,
            int fogB,
            int fogA,
            float fogEnd,
            float fogStart,
            float lightScale
    ) {
    }

    public ZoneEnvironmentResource {
        LightGroup emptyGroup = new LightGroup(0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 1.0f, 0.0f, 1.0f);
        actorGroup = actorGroup != null ? actorGroup : emptyGroup;
        bgGroup = bgGroup != null ? bgGroup : emptyGroup;
        if (skyRgba == null || skyRgba.length != SKY_SLICE_COUNT * 4) {
            int[] padded = new int[SKY_SLICE_COUNT * 4];
            if (skyRgba != null) {
                System.arraycopy(skyRgba, 0, padded, 0, Math.min(skyRgba.length, padded.length));
            }
            skyRgba = padded;
        } else {
            skyRgba = skyRgba.clone();
        }
        if (skyStops == null || skyStops.length != SKY_SLICE_COUNT) {
            float[] padded = new float[SKY_SLICE_COUNT];
            if (skyStops != null) {
                System.arraycopy(skyStops, 0, padded, 0, Math.min(skyStops.length, padded.length));
            }
            skyStops = padded;
        } else {
            skyStops = skyStops.clone();
        }
    }

    /** True when bit 0 is set, indicating an indoor/fixed directional light encoded in moon RGBA bytes. */
    public boolean isIndoor() {
        return (flags & 1) != 0;
    }

    public int skyR(int slice) {
        return skyRgba[slice * 4] & 0xFF;
    }

    public int skyG(int slice) {
        return skyRgba[slice * 4 + 1] & 0xFF;
    }

    public int skyB(int slice) {
        return skyRgba[slice * 4 + 2] & 0xFF;
    }

    public int skyA(int slice) {
        return skyRgba[slice * 4 + 3] & 0xFF;
    }

    public float skyStop(int slice) {
        return skyStops[slice];
    }

    @Override
    public int[] skyRgba() {
        return skyRgba.clone();
    }

    @Override
    public float[] skyStops() {
        return skyStops.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZoneEnvironmentResource that)) return false;
        return sectionStart == that.sectionStart
                && sectionSize == that.sectionSize
                && flags == that.flags
                && clearR == that.clearR
                && clearG == that.clearG
                && clearB == that.clearB
                && clearA == that.clearA
                && Float.compare(that.drawDist, drawDist) == 0
                && hasDome == that.hasDome
                && spokeCount == that.spokeCount
                && Float.compare(that.skyRadius, skyRadius) == 0
                && Objects.equals(sectionId, that.sectionId)
                && Objects.equals(sectionType, that.sectionType)
                && Objects.equals(actorGroup, that.actorGroup)
                && Objects.equals(bgGroup, that.bgGroup)
                && Arrays.equals(skyRgba, that.skyRgba)
                && Arrays.equals(skyStops, that.skyStops);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sectionId, sectionType, sectionStart, sectionSize, flags, actorGroup, bgGroup,
                clearR, clearG, clearB, clearA, drawDist, hasDome, spokeCount, skyRadius);
        result = 31 * result + Arrays.hashCode(skyRgba);
        result = 31 * result + Arrays.hashCode(skyStops);
        return result;
    }
}
