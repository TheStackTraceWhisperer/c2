package com.ffxi.model;

import java.util.List;

public record JointAnimation(int jointIndex, List<AnimationKeyFrame> frames) {
    public JointAnimation {
        frames = List.copyOf(frames != null ? frames : List.of());
    }
}
