package com.ffxi.model;

public record ZoneMeshVertex(
        float px,
        float py,
        float pz,
        float p1x,
        float p1y,
        float p1z,
        float nx,
        float ny,
        float nz,
        int colorB,
        int colorG,
        int colorR,
        int colorA,
        float u,
        float v
) {
}
