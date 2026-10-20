package com.example.asset.skeleton;

import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkeletonAnimationParserTest {

    @Test
    @DisplayName("SkeletonAnimationParser correctly parses S2B section with constant and sequenced keyframes")
    void testParseS2bAnimation() {
        // Construct binary S2B payload:
        // Header: unk0 (u16=0), numJoints (u16=1), numFrames (u16=2), keyFrameDuration (float=0.0333f)
        // Joint 0:
        //   jointIndex: 0 (i32)
        //   rotSeq (4): offsets = [0, 0, 0, 0], constValues = [0f, 0f, 0f, 1f] (identity quaternion)
        //   transSeq (3): offsets = [10, 0, 0], constValues = [0f, 1.5f, -0.5f]
        //   scaleSeq (3): offsets = [0, 0, 0], constValues = [1f, 1f, 1f]
        // Floating point sequence at index 10 from sequenceDataOffset:
        //   frame 0: 2.0f, frame 1: 4.0f

        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);      // unk0
        buf.putShort((short) 1);      // numJoints = 1
        buf.putShort((short) 2);      // numFrames = 2
        buf.putFloat(0.0333f);        // keyFrameDuration
        int seqDataOffset = buf.position(); // offset 10

        // Joint 0
        buf.putInt(0); // jointIndex

        // Rotation (amount = 4)
        buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0); // offsets
        buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(1f); // const quat (0, 0, 0, 1)

        // Translation (amount = 3): X has sequence at index 25 (offset = 25 floats = 100 bytes), Y & Z are constant
        int seqFloatIndex = 25;
        buf.putInt(seqFloatIndex); buf.putInt(0); buf.putInt(0); // offsets
        buf.putFloat(0f); buf.putFloat(1.5f); buf.putFloat(-0.5f); // const values

        // Scale (amount = 3)
        buf.putInt(0); buf.putInt(0); buf.putInt(0); // offsets
        buf.putFloat(1f); buf.putFloat(1f); buf.putFloat(1f); // const values

        // Write sequence float data at seqDataOffset + seqFloatIndex * 4
        buf.position(seqDataOffset + seqFloatIndex * 4);
        buf.putFloat(2.0f); // frame 0
        buf.putFloat(4.0f); // frame 1

        buf.flip();

        DatByteReader reader = new DatByteReader(buf);
        DatSectionHeader header = new DatSectionHeader("test_anim", SectionType.S2B_SKELETON_ANIMATION, 0, 16, buf.capacity());

        Optional<SkeletonAnimation> animOpt = SkeletonAnimationParser.parse(header, reader);
        assertTrue(animOpt.isPresent(), "Parser should successfully parse S2B animation payload");

        SkeletonAnimation anim = animOpt.get();
        assertEquals(1, anim.numJoints());
        assertEquals(2, anim.numFrames());
        assertEquals(0.0333f, anim.frameDuration(), 1e-5f);

        // Evaluate joint 0 at frame 0: trans.x should be 2.0, y=1.5, z=-0.5
        KeyFrameTransform kf0 = anim.evaluate(0, 0.0f);
        assertEquals(2.0f, kf0.translation().x(), 1e-4f);
        assertEquals(1.5f, kf0.translation().y(), 1e-4f);
        assertEquals(-0.5f, kf0.translation().z(), 1e-4f);

        // Evaluate joint 0 at frame 1: trans.x should be 4.0
        KeyFrameTransform kf1 = anim.evaluate(0, 1.0f);
        assertEquals(4.0f, kf1.translation().x(), 1e-4f);

        // Evaluate interpolated halfway at frame 0.5: trans.x should be 3.0
        KeyFrameTransform kfMid = anim.evaluate(0, 0.5f);
        assertEquals(3.0f, kfMid.translation().x(), 1e-4f);
        assertEquals(1.5f, kfMid.translation().y(), 1e-4f);
    }
}
