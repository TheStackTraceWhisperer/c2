package com.ffxi.model;

public record AnimationKeyFrame(
        float rx, float ry, float rz, float rw,
        float tx, float ty, float tz,
        float sx, float sy, float sz
) {
}
