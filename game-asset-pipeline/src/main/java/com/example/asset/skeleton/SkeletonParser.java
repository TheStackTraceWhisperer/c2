package com.example.asset.skeleton;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses FFXI S29_SKELETON sections into bone hierarchies and rest bind poses.
 */
public final class SkeletonParser {

    private SkeletonParser() {
    }

    public static Optional<Skeleton> parseSkeleton(DatSectionHeader header, DatByteReader payload) {
        try {
            payload.position(0x02);
            int numJoints = payload.nextU8();

            payload.position(0x04);
            List<Joint> joints = new ArrayList<>(numJoints);
            for (int i = 0; i < numJoints; i++) {
                int maybeParent = payload.nextU8();
                int parentIndex = (maybeParent == i) ? -1 : maybeParent;
                payload.skip(1); // padding

                float qx = payload.nextFloat();
                float qy = payload.nextFloat();
                float qz = payload.nextFloat();
                float qw = payload.nextFloat();
                Quaternion rot = new Quaternion(qx, qy, qz, qw);

                float tx = payload.nextFloat();
                float ty = payload.nextFloat();
                float tz = payload.nextFloat();
                Vector3f trans = new Vector3f(tx, ty, tz);

                joints.add(new Joint(i, parentIndex, rot, trans));
            }

            int numReferences = payload.nextU16();
            int unk1 = payload.nextU16();

            List<JointReference> references = new ArrayList<>(numReferences);
            for (int i = 0; i < numReferences; i++) {
                int jointIndex = payload.nextU16();
                Vector3f unkV0 = payload.nextVector3f();
                Vector3f offset = payload.nextVector3f();
                references.add(new JointReference(jointIndex, unkV0, offset));
            }

            List<BoundingBox> boxes = new ArrayList<>();
            while (payload.remaining() >= 24) {
                float yMax = payload.nextFloat();
                float yMin = payload.nextFloat();
                float xMax = payload.nextFloat();
                float xMin = payload.nextFloat();
                float zMax = payload.nextFloat();
                float zMin = payload.nextFloat();

                if (Float.isNaN(yMax) || Float.isNaN(xMax) || Float.isNaN(zMax)) {
                    break;
                }
                boxes.add(new BoundingBox(new Vector3f(xMin, yMin, zMin), new Vector3f(xMax, yMax, zMax)));
            }

            return Optional.of(new Skeleton(header.sectionId(), joints, references, boxes));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
