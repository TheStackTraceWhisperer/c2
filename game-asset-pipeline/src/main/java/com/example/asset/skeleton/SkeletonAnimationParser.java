package com.example.asset.skeleton;

import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses authentic FFXI S2B_SKELETON_ANIMATION keyframe tracks from DAT section binaries.
 * Matches xim SkeletonAnimationSection format specification.
 */
public final class SkeletonAnimationParser {

    private SkeletonAnimationParser() {
    }

    private static class FrameSequence {
        private final float[] frameValues;

        FrameSequence(float[] frameValues) {
            this.frameValues = frameValues;
        }

        float getValue(int frame) {
            if (frameValues.length == 1) {
                return frameValues[0];
            }
            if (frame >= 0 && frame < frameValues.length) {
                return frameValues[frame];
            }
            return 0.0f;
        }
    }

    /**
     * Parses an authentic FFXI S2B (0x2B) animation payload into a SkeletonAnimation.
     */
    public static Optional<SkeletonAnimation> parse(DatSectionHeader header, DatByteReader payload) {
        try {
            if (payload == null || payload.remaining() < 10) {
                return Optional.empty();
            }

            payload.position(0);
            int unk0 = payload.nextU16();
            int numJoints = payload.nextU16();
            int numFrames = payload.nextU16();
            float keyFrameDuration = payload.nextFloat();

            if (numJoints <= 0 || numFrames <= 0) {
                return Optional.empty();
            }

            int keyFrameDataOffset = payload.position();
            Map<Integer, List<KeyFrameTransform>> jointTracks = new HashMap<>();

            for (int i = 0; i < numJoints; i++) {
                if (payload.remaining() < 4) {
                    break;
                }
                int jointIndex = payload.nextI32();

                List<FrameSequence> rotSeq = readKeyFrameSequences(4, numFrames, payload, keyFrameDataOffset);
                List<FrameSequence> transSeq = readKeyFrameSequences(3, numFrames, payload, keyFrameDataOffset);
                List<FrameSequence> scaleSeq = readKeyFrameSequences(3, numFrames, payload, keyFrameDataOffset);

                if (rotSeq.isEmpty() || transSeq.isEmpty() || scaleSeq.isEmpty()) {
                    continue;
                }

                List<KeyFrameTransform> transforms = resolveSequences(numFrames, rotSeq, transSeq, scaleSeq);
                jointTracks.put(jointIndex, transforms);
            }

            return Optional.of(new SkeletonAnimation(
                    header != null ? header.sectionId() : "anim_s2b",
                    numJoints,
                    numFrames,
                    keyFrameDuration,
                    jointTracks
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<FrameSequence> readKeyFrameSequences(int amount, int numFrames, DatByteReader payload, int sequenceDataOffset) {
        if (payload.remaining() < amount * 8) {
            return List.of();
        }

        int[] offsets = new int[amount];
        for (int i = 0; i < amount; i++) {
            offsets[i] = payload.nextI32();
        }

        float[] constValues = new float[amount];
        for (int i = 0; i < amount; i++) {
            constValues[i] = payload.nextFloat() % 10000.0f;
        }

        // Top bit set means low priority / ignore
        for (int offset : offsets) {
            if (offset < 0) {
                return List.of();
            }
        }

        List<FrameSequence> sequences = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            if (offsets[i] == 0) {
                sequences.add(new FrameSequence(new float[]{constValues[i]}));
            } else {
                sequences.add(fetchSequence(offsets[i], numFrames, payload, sequenceDataOffset));
            }
        }

        return sequences;
    }

    private static FrameSequence fetchSequence(int index, int numFrames, DatByteReader payload, int sequenceDataOffset) {
        int originalPos = payload.position();
        int targetPos = sequenceDataOffset + index * 4;
        if (targetPos < 0 || targetPos + numFrames * 4 > payload.capacity()) {
            return new FrameSequence(new float[numFrames]);
        }

        payload.position(targetPos);
        float[] seq = new float[numFrames];
        for (int f = 0; f < numFrames; f++) {
            seq[f] = payload.nextFloat();
        }
        payload.position(originalPos);

        return new FrameSequence(seq);
    }

    private static List<KeyFrameTransform> resolveSequences(
            int numFrames,
            List<FrameSequence> rotSeq,
            List<FrameSequence> transSeq,
            List<FrameSequence> scaleSeq
    ) {
        List<KeyFrameTransform> transforms = new ArrayList<>(numFrames);
        for (int frame = 0; frame < numFrames; frame++) {
            Quaternion rot = new Quaternion(
                    rotSeq.get(0).getValue(frame),
                    rotSeq.get(1).getValue(frame),
                    rotSeq.get(2).getValue(frame),
                    rotSeq.get(3).getValue(frame)
            );

            Vector3f trans = new Vector3f(
                    transSeq.get(0).getValue(frame),
                    transSeq.get(1).getValue(frame),
                    transSeq.get(2).getValue(frame)
            );

            Vector3f scale = new Vector3f(
                    scaleSeq.get(0).getValue(frame),
                    scaleSeq.get(1).getValue(frame),
                    scaleSeq.get(2).getValue(frame)
            );

            transforms.add(new KeyFrameTransform(rot, trans, scale));
        }
        return transforms;
    }
}
