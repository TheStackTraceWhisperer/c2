package com.example.asset.skeleton;

import com.example.asset.geometry.Matrix4f;

import java.util.List;

/**
 * Result of animation state evaluation, containing active state, blend weight, and joint pose matrices.
 */
public record BlendedPose(
    AnimationState activeState,
    AnimationState transitionState,
    float blendWeight,
    float playbackTime,
    List<Matrix4f> jointPoseMatrices
) {}
