package com.ffxi.model;

import java.util.List;

/**
 * Parsed {@code S19_ParticleKeyFrameData} section storing normalized `(time, value)` keyframes
 * sampled by particle updaters (such as FFXI time-of-day color and alpha curves).
 */
public record ParticleKeyFrameResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        List<Entry> entries
) implements ResourceNode {

    public ParticleKeyFrameResource {
        entries = entries != null ? List.copyOf(entries) : List.of();
    }

    /** Single normalized time-value keyframe sample. */
    public record Entry(float time, float value) {
    }

    /**
     * Evaluates the keyframe curve at normalized clock/life progress in {@code [0, 1]} using
     * linear interpolation matching XIM's {@code ParticleKeyFrameData.getCurrentValue}.
     */
    public float sampleNormalized(float progress) {
        if (entries.isEmpty()) {
            return 0.0f;
        }
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        if (clamped >= 1.0f || entries.size() == 1) {
            return entries.getLast().value();
        }
        int nextIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).time() > clamped) {
                nextIndex = i;
                break;
            }
        }
        if (nextIndex < 0) {
            return entries.getLast().value();
        }
        if (nextIndex == 0) {
            return entries.getFirst().value();
        }
        Entry prev = entries.get(nextIndex - 1);
        Entry next = entries.get(nextIndex);
        float span = next.time() - prev.time();
        if (span <= 1e-6f) {
            return next.value();
        }
        float factor = (clamped - prev.time()) / span;
        return (1.0f - factor) * prev.value() + factor * next.value();
    }
}
