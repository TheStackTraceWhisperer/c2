package com.ffxi.viewer;

import com.ffxi.model.AnimationKeyFrame;
import com.ffxi.model.JointAnimation;
import com.ffxi.model.Quat4f;
import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonJoint;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.Vec3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the reference XIM SkeletonInstance.updateCurrentJointTransform algorithm exactly.
 *
 * Key points from the reference (SkeletonInstance.kt):
 *  1. Each joint's local pose is bind-pose + animation delta (additive, not replace).
 *     translation += anim.translation
 *     rotation    = anim.rotation * bindRotation   (pre-multiply)
 *  2. For joint 0 (root): the combined translation is put through rotate270()
 *     which is: x' = -z, z' = x  (a 90° CCW rotation in the XZ plane).
 *  3. The parent chain accumulates a DECOMPOSED (R, T, S) triple, NOT a single matrix.
 *     childT = parentT + parentR * (parentS ⊗ localT)
 *     childS = parentS ⊗ localS
 *     childR = parentR * localR
 *  4. The per-joint output matrix is built as:  R  then  translateDirect(T)  then  scale(S)
 *     which in column-major is: M = R * T_mat * S_mat
 *     i.e. identity -> copyFrom(R) -> add T directly to column-3 -> scale columns.
 */
public final class CharacterAnimator {
    private final SkeletonResource skeleton;
    private final SkeletonAnimationResource lowerBodyAnimation;
    private final SkeletonAnimationResource upperBodyAnimation;
    private final Map<Integer, JointAnimation> lowerBodyByJoint;
    private final Map<Integer, JointAnimation> upperBodyByJoint;

    public CharacterAnimator(
            SkeletonResource skeleton,
            SkeletonAnimationResource lowerBodyAnimation,
            SkeletonAnimationResource upperBodyAnimation
    ) {
        this.skeleton = skeleton;
        this.lowerBodyAnimation = lowerBodyAnimation;
        this.upperBodyAnimation = upperBodyAnimation;
        this.lowerBodyByJoint = indexByJoint(lowerBodyAnimation);
        this.upperBodyByJoint = indexByJoint(upperBodyAnimation);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Matrix4f[] computeTPoseMatrices() {
        return computePoseMatrices(-1.0);   // negative time → no animation data sampled
    }

    /**
     * Compute the world-space joint matrices for a given playback time.
     * Mirrors SkeletonInstance.animate() / updateCurrentJointTransform().
     */
    public Matrix4f[] computePoseMatrices(double elapsedSeconds) {
        if (elapsedSeconds < 0.0) {
            return computePoseMatricesInternal(j -> null);
        }
        return computePoseMatricesInternal(j -> sampleJointFrame(j, elapsedSeconds));
    }

    /**
     * Compute blended world-space joint matrices between this animator and another animator.
     * blendFactor: 0.0 = 100% this animator, 1.0 = 100% other animator.
     */
    public Matrix4f[] computeBlendedPoseMatrices(CharacterAnimator other, double thisTime, double otherTime, float blendFactor) {
        float alpha = Math.max(0.0f, Math.min(1.0f, blendFactor));
        return computePoseMatricesInternal(jointIndex -> {
            InterpolatedFrame f0 = sampleJointFrame(jointIndex, thisTime);
            InterpolatedFrame f1 = other != null ? other.sampleJointFrame(jointIndex, otherTime) : null;
            if (f0 == null && f1 == null) return null;
            if (f0 == null) return f1;
            if (f1 == null) return f0;
            return InterpolatedFrame.interpolate(f0, f1, alpha);
        });
    }

    @FunctionalInterface
    private interface JointFrameSampler {
        InterpolatedFrame sample(int jointIndex);
    }

    private Matrix4f[] computePoseMatricesInternal(JointFrameSampler sampler) {
        List<SkeletonJoint> joints = skeleton.joints();
        int n = joints.size();

        // Per-joint decomposed TRS accumulators (world-space)
        // These mirror JointTransformBuilder in the reference.
        Vector3f[] worldT = new Vector3f[n];
        Vector3f[] worldS = new Vector3f[n];
        Matrix4f[] worldR = new Matrix4f[n];   // rotation-only matrices (no translation column)
        Matrix4f[] result = new Matrix4f[n];

        for (int i = 0; i < n; i++) {
            worldT[i] = new Vector3f();
            worldS[i] = new Vector3f(1f, 1f, 1f);
            worldR[i] = new Matrix4f().identity();
            result[i] = new Matrix4f().identity();
        }

        // Scratch objects (reused to avoid allocation per joint)
        Vector3f localT = new Vector3f();
        Vector3f localS = new Vector3f();
        Quaternionf localR = new Quaternionf();
        Vector3f scratch = new Vector3f();

        for (int i = 0; i < n; i++) {
            SkeletonJoint joint = joints.get(i);

            // --- Build local bind-pose TRS ---
            Vec3f bt = joint.translation();
            localT.set(bt != null ? bt.x() : 0f,
                       bt != null ? bt.y() : 0f,
                       bt != null ? bt.z() : 0f);
            localR.set(toQuaternion(joint.rotation()));
            localS.set(1f, 1f, 1f);

            // --- Apply animation delta (additive, matching reference lines 201-205) ---
            InterpolatedFrame frame = sampler.sample(i);
            if (frame != null) {
                // translation += animTranslation
                localT.add(frame.translation());
                // rotation = animRotation * bindRotation  (pre-multiply)
                localR.premul(frame.rotation()).normalize();
                // scale *= animScale  (root joint scale is ignored, per reference comment)
                if (i != 0) {
                    localS.mul(frame.scale());
                }
            }

            // --- Root joint special-case: rotate270 on translation (reference line 209) ---
            // rotate270: x' = -z,  z' = x
            if (i == 0) {
                float tx = localT.x;
                localT.x = -localT.z;
                localT.z = tx;
            }

            // --- Accumulate world TRS (reference lines 211-219) ---
            int parentIdx = joint.parentIndex();
            boolean hasParent = parentIdx >= 0 && parentIdx < n && parentIdx != 255;

            Matrix4f rotMat = new Matrix4f().rotation(localR);   // pure rotation matrix from quat

            if (!hasParent) {
                // Root: worldT = R * (S * localT),  worldS = localS,  worldR = localR
                // Reference line 212: jointTransform.t = jointTransform.r.transform(jointTransform.s * translation)
                // jointTransform.r starts as identity for non-root, but for root it contains actor rotation.
                // For the viewer (no actor), root R starts as identity.
                scratch.set(localT);
                worldR[i].transformPosition(scratch);      // R * (S * t), but S=1 initially for root
                worldT[i].set(scratch);
                worldS[i].set(localS);
                worldR[i].set(rotMat);                    // worldR = localR (for root: no prior rotation)
            } else {
                // Child: worldT = parentT + parentR * (parentS * localT)
                //        worldS = parentS * localS
                //        worldR = parentR * localR
                scratch.set(localT).mul(worldS[parentIdx]);     // parentS * localT
                worldR[parentIdx].transformPosition(scratch);   // parentR * (parentS * localT)
                worldT[i].set(worldT[parentIdx]).add(scratch);  // parentT + ...
                worldS[i].set(worldS[parentIdx]).mul(localS);
                worldR[parentIdx].mul(rotMat, worldR[i]);       // parentR * localR
            }

            // --- Build output matrix: R then translateDirect(T) then scale(S) ---
            // Matches JointTransform.toMat4():
            //   Matrix4f().copyFrom(r).translateDirect(t).scaleInPlace(s)
            // "translateDirect" just sets the translation column directly (no further rotation applied).
            // "scaleInPlace" scales the upper-left 3x3 columns.
            result[i].set(worldR[i]);
            // Set translation column (m30/m31/m32 in JOML = column 3 x/y/z)
            result[i].m30(worldT[i].x);
            result[i].m31(worldT[i].y);
            result[i].m32(worldT[i].z);
            // Scale upper-left 3x3 by worldS
            result[i].m00(result[i].m00() * worldS[i].x);
            result[i].m01(result[i].m01() * worldS[i].y);
            result[i].m02(result[i].m02() * worldS[i].z);
            result[i].m10(result[i].m10() * worldS[i].x);
            result[i].m11(result[i].m11() * worldS[i].y);
            result[i].m12(result[i].m12() * worldS[i].z);
            result[i].m20(result[i].m20() * worldS[i].x);
            result[i].m21(result[i].m21() * worldS[i].y);
            result[i].m22(result[i].m22() * worldS[i].z);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Animation sampling
    // -------------------------------------------------------------------------

    private static Map<Integer, JointAnimation> indexByJoint(SkeletonAnimationResource animation) {
        Map<Integer, JointAnimation> byJoint = new HashMap<>();
        if (animation == null) {
            return byJoint;
        }
        for (JointAnimation jointAnimation : animation.jointAnimations()) {
            byJoint.put(jointAnimation.jointIndex(), jointAnimation);
        }
        return byJoint;
    }

    private InterpolatedFrame sampleJointFrame(int jointIndex, double elapsedSeconds) {
        // Upper body takes priority over lower body (mirrors slot ordering in reference)
        InterpolatedFrame upper = sampleJointFrame(upperBodyAnimation, upperBodyByJoint.get(jointIndex), elapsedSeconds);
        if (upper != null) {
            return upper;
        }
        return sampleJointFrame(lowerBodyAnimation, lowerBodyByJoint.get(jointIndex), elapsedSeconds);
    }

    private static InterpolatedFrame sampleJointFrame(
            SkeletonAnimationResource animation,
            JointAnimation jointAnimation,
            double elapsedSeconds
    ) {
        if (animation == null || jointAnimation == null || jointAnimation.frames().isEmpty()) {
            return null;
        }

        List<AnimationKeyFrame> frames = jointAnimation.frames();
        if (frames.size() == 1 || animation.keyFrameDuration() <= 0.0f) {
            return InterpolatedFrame.from(frames.getFirst());
        }

        // Smooth looping: loop over frameCount - 1 (since the final frame is typically a duplicate of the first frame in FFXI),
        // and interpolate directly from the last unique frame back to frame 0.
        int count = frames.size();
        double loopPeriod = count > 1 ? count - 1 : 1;
        double frameValue = elapsedSeconds * 30.0 * animation.keyFrameDuration();
        double wrappedFrame = frameValue % loopPeriod;
        if (wrappedFrame < 0.0) {
            wrappedFrame += loopPeriod;
        }

        int lowerIndex = (int) Math.floor(wrappedFrame);
        int upperIndex = (lowerIndex + 1) % (int) loopPeriod;
        float alpha = (float) (wrappedFrame - lowerIndex);

        AnimationKeyFrame lower = frames.get(lowerIndex);
        AnimationKeyFrame upper = frames.get(upperIndex);
        return InterpolatedFrame.interpolate(lower, upper, alpha);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Quaternionf toQuaternion(Quat4f quat) {
        if (quat == null) {
            return new Quaternionf();
        }
        return new Quaternionf(quat.x(), quat.y(), quat.z(), quat.w()).normalize();
    }

    private record InterpolatedFrame(Quaternionf rotation, Vector3f translation, Vector3f scale) {
        private static InterpolatedFrame from(AnimationKeyFrame frame) {
            return new InterpolatedFrame(
                    new Quaternionf(frame.rx(), frame.ry(), frame.rz(), frame.rw()).normalize(),
                    new Vector3f(frame.tx(), frame.ty(), frame.tz()),
                    new Vector3f(frame.sx(), frame.sy(), frame.sz())
            );
        }

        private static InterpolatedFrame interpolate(AnimationKeyFrame a, AnimationKeyFrame b, float alpha) {
            Quaternionf rotation = new Quaternionf(a.rx(), a.ry(), a.rz(), a.rw())
                    .normalize()
                    .slerp(new Quaternionf(b.rx(), b.ry(), b.rz(), b.rw()).normalize(), alpha);
            Vector3f translation = new Vector3f(
                    lerp(a.tx(), b.tx(), alpha),
                    lerp(a.ty(), b.ty(), alpha),
                    lerp(a.tz(), b.tz(), alpha)
            );
            Vector3f scale = new Vector3f(
                    lerp(a.sx(), b.sx(), alpha),
                    lerp(a.sy(), b.sy(), alpha),
                    lerp(a.sz(), b.sz(), alpha)
            );
            return new InterpolatedFrame(rotation, translation, scale);
        }

        private static InterpolatedFrame interpolate(InterpolatedFrame a, InterpolatedFrame b, float alpha) {
            Quaternionf rotation = new Quaternionf(a.rotation).slerp(b.rotation, alpha);
            Vector3f translation = new Vector3f(
                    lerp(a.translation.x, b.translation.x, alpha),
                    lerp(a.translation.y, b.translation.y, alpha),
                    lerp(a.translation.z, b.translation.z, alpha)
            );
            Vector3f scale = new Vector3f(
                    lerp(a.scale.x, b.scale.x, alpha),
                    lerp(a.scale.y, b.scale.y, alpha),
                    lerp(a.scale.z, b.scale.z, alpha)
            );
            return new InterpolatedFrame(rotation, translation, scale);
        }

        private static float lerp(float a, float b, float alpha) {
            return a + ((b - a) * alpha);
        }
    }
}
