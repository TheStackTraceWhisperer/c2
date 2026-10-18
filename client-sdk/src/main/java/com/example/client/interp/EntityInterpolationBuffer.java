package com.example.client.interp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ring buffer and hermite/spherical interpolator for remote entity states.
 * Translates discrete 20 Hz (50ms interval) network tick deltas into smooth, jitter-free
 * 60+ FPS visual coordinates with interpolation delay buffering and dead-reckoning extrapolation.
 */
public class EntityInterpolationBuffer {

    public static final long DEFAULT_INTERPOLATION_DELAY_MS = 100L; // 2 server ticks buffer
    public static final long MAX_EXTRAPOLATION_MS = 250L;
    public static final float TELEPORT_THRESHOLD_SQ = 625.0f; // 25 meters snap threshold
    private static final int MAX_SNAPSHOTS = 40;

    private final List<InterpolationSnapshot> snapshots = new ArrayList<>();
    private final Object lock = new Object();

    public void pushSnapshot(InterpolationSnapshot snapshot) {
        synchronized (lock) {
            // Maintain chronological order
            if (snapshots.isEmpty()) {
                snapshots.add(snapshot);
                return;
            }

            InterpolationSnapshot last = snapshots.getLast();
            if (snapshot.timestampMs() >= last.timestampMs()) {
                snapshots.add(snapshot);
            } else {
                // Out-of-order packet insertion
                int idx = Collections.binarySearch(
                        snapshots,
                        snapshot,
                        (a, b) -> Long.compare(a.timestampMs(), b.timestampMs())
                );
                if (idx < 0) {
                    idx = -idx - 1;
                }
                snapshots.add(idx, snapshot);
            }

            // Prune old history
            while (snapshots.size() > MAX_SNAPSHOTS) {
                snapshots.removeFirst();
            }
        }
    }

    public InterpolatedEntityState sample(long currentClientTimeMs) {
        return sample(currentClientTimeMs, DEFAULT_INTERPOLATION_DELAY_MS);
    }

    public InterpolatedEntityState sample(long currentClientTimeMs, long interpolationDelayMs) {
        synchronized (lock) {
            if (snapshots.isEmpty()) {
                return new InterpolatedEntityState(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, false);
            }

            long targetTimeMs = currentClientTimeMs - interpolationDelayMs;

            if (snapshots.size() == 1) {
                InterpolationSnapshot s = snapshots.getFirst();
                return new InterpolatedEntityState(
                        s.x(), s.y(), s.z(),
                        s.vx(), s.vy(), s.vz(),
                        s.yaw(), s.pitch(),
                        s.moveFlags(),
                        false
                );
            }

            InterpolationSnapshot oldest = snapshots.getFirst();
            if (targetTimeMs <= oldest.timestampMs()) {
                return new InterpolatedEntityState(
                        oldest.x(), oldest.y(), oldest.z(),
                        oldest.vx(), oldest.vy(), oldest.vz(),
                        oldest.yaw(), oldest.pitch(),
                        oldest.moveFlags(),
                        false
                );
            }

            InterpolationSnapshot newest = snapshots.getLast();
            // Extrapolation when packet arrives late or network jitter occurs
            if (targetTimeMs > newest.timestampMs()) {
                long deltaAheadMs = targetTimeMs - newest.timestampMs();
                if (deltaAheadMs > MAX_EXTRAPOLATION_MS) {
                    deltaAheadMs = MAX_EXTRAPOLATION_MS;
                }
                float dtSec = (float) deltaAheadMs / 1000.0f;
                float decay = Math.max(0.0f, 1.0f - ((float) deltaAheadMs / (float) MAX_EXTRAPOLATION_MS));

                float extX = newest.x() + (newest.vx() * dtSec * decay);
                float extY = newest.y() + (newest.vy() * dtSec * decay);
                float extZ = newest.z() + (newest.vz() * dtSec * decay);

                return new InterpolatedEntityState(
                        extX, extY, extZ,
                        newest.vx() * decay, newest.vy() * decay, newest.vz() * decay,
                        newest.yaw(), newest.pitch(),
                        newest.moveFlags(),
                        true
                );
            }

            // Find surrounding bracket [s0, s1] such that s0.time <= targetTime <= s1.time
            InterpolationSnapshot s0 = oldest;
            InterpolationSnapshot s1 = newest;

            for (int i = 0; i < snapshots.size() - 1; i++) {
                InterpolationSnapshot cur = snapshots.get(i);
                InterpolationSnapshot nxt = snapshots.get(i + 1);
                if (cur.timestampMs() <= targetTimeMs && targetTimeMs <= nxt.timestampMs()) {
                    s0 = cur;
                    s1 = nxt;
                    break;
                }
            }

            long spanMs = s1.timestampMs() - s0.timestampMs();
            float alpha = (spanMs > 0) ? (float) (targetTimeMs - s0.timestampMs()) / (float) spanMs : 0.0f;
            alpha = Math.clamp(alpha, 0.0f, 1.0f);

            // Teleport detection: snap immediately if displacement is beyond threshold
            float dx = s1.x() - s0.x();
            float dy = s1.y() - s0.y();
            float dz = s1.z() - s0.z();
            float distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > TELEPORT_THRESHOLD_SQ) {
                return new InterpolatedEntityState(
                        s1.x(), s1.y(), s1.z(),
                        s1.vx(), s1.vy(), s1.vz(),
                        s1.yaw(), s1.pitch(),
                        s1.moveFlags(),
                        false
                );
            }

            // Linear position interpolation
            float ix = s0.x() + alpha * dx;
            float iy = s0.y() + alpha * dy;
            float iz = s0.z() + alpha * dz;

            // Velocity interpolation
            float ivx = s0.vx() + alpha * (s1.vx() - s0.vx());
            float ivy = s0.vy() + alpha * (s1.vy() - s0.vy());
            float ivz = s0.vz() + alpha * (s1.vz() - s0.vz());

            // Shortest-arc spherical angular interpolation for yaw and pitch
            float iYaw = interpolateAngleDegrees(s0.yaw(), s1.yaw(), alpha);
            float iPitch = interpolateAngleDegrees(s0.pitch(), s1.pitch(), alpha);

            return new InterpolatedEntityState(
                    ix, iy, iz,
                    ivx, ivy, ivz,
                    iYaw, iPitch,
                    s1.moveFlags(),
                    false
            );
        }
    }

    public static float interpolateAngleDegrees(float a0, float a1, float alpha) {
        float diff = (a1 - a0) % 360.0f;
        if (diff > 180.0f) {
            diff -= 360.0f;
        } else if (diff < -180.0f) {
            diff += 360.0f;
        }
        float res = a0 + alpha * diff;
        return (res % 360.0f + 360.0f) % 360.0f;
    }

    public int getSnapshotCount() {
        synchronized (lock) {
            return snapshots.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            snapshots.clear();
        }
    }
}
