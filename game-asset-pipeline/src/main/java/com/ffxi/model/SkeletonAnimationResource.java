package com.ffxi.model;

import java.util.List;

public record SkeletonAnimationResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int payloadSize,
        int unknownHeaderValue,
        int jointCount,
        int frameCount,
        float keyFrameDuration,
        int keyFrameTableOffset,
        List<JointAnimation> jointAnimations
) implements ResourceNode {
    public SkeletonAnimationResource {
        jointAnimations = List.copyOf(jointAnimations != null ? jointAnimations : List.of());
    }
}
