package com.example.client.desktop.scene;

import com.example.common.protocol.MovementFlags;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.renderer.CharacterShader;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

/**
 * Interactive or ambient actor node in the 3D WorldScene graph.
 * Represents player avatars, NPCs, and monsters with authentic dual-bone skeletal animation.
 */
@Getter
@Setter
public class SceneActor {

    public enum ActorType {
        PLAYER,
        NPC,
        MONSTER
    }

    private final long id;
    private String name;
    private ActorType type = ActorType.PLAYER;
    private boolean isLocalPlayer = false;
    private boolean visible = true;

    // Spatial transform
    private float x = 0.0f;
    private float y = 0.0f;
    private float z = 0.0f;
    private float yaw = 0.0f;
    private float pitch = 0.0f;
    private float roll = 0.0f;

    // Target transform for smooth remote entity interpolation & dead-reckoning
    private float targetX = 0.0f;
    private float targetY = 0.0f;
    private float targetZ = 0.0f;
    private float targetYaw = 0.0f;
    private float vx = 0.0f;
    private float vy = 0.0f;
    private float vz = 0.0f;
    private boolean lerpEnabled = false;

    // Movement & animation
    private int movementFlags = MovementFlags.IDLE;
    private float movementSpeed = 0.0f;
    private AuthenticCharacterInstance visualModel;

    public SceneActor(long id, String name, ActorType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public void setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public void setRotation(float yaw) {
        this.yaw = yaw;
        this.targetYaw = yaw;
    }

    /**
     * Sets target transform from authoritative network state delta.
     * Position and yaw will smoothly interpolate and dead-reckon over time without popping.
     */
    public void setTargetTransform(float tx, float ty, float tz, float tYaw, float velX, float velY, float velZ) {
        this.targetX = tx;
        this.targetY = ty;
        this.targetZ = tz;
        this.targetYaw = tYaw;
        this.vx = velX;
        this.vy = velY;
        this.vz = velZ;
        this.lerpEnabled = true;
    }

    /**
     * Updates locomotion animation tracks and skeleton based on elapsed time.
     * Evaluates dead-reckoning extrapolation and authentic angular turn-rate easing.
     */
    public void update(float dt) {
        if (lerpEnabled && !isLocalPlayer) {
            float dx = targetX - x;
            float dy = targetY - y;
            float dz = targetZ - z;
            float distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > 400.0f) {
                // Hard snap on large teleport / initial spawn (20m)
                x = targetX;
                y = targetY;
                z = targetZ;
            } else {
                // Exponential decay approach towards target with velocity prediction
                float blend = 1.0f - (float) Math.exp(-15.0f * dt);
                x += dx * blend + vx * dt * (1.0f - blend);
                y += dy * blend + vy * dt * (1.0f - blend);
                z += dz * blend + vz * dt * (1.0f - blend);
            }

            // Authentic turn-rate easing from vana-client client/mod.rs (1.0 - (1.0 - rate)^ticks)
            float diff = (targetYaw - yaw) % 360.0f;
            if (diff > 180.0f) diff -= 360.0f;
            else if (diff < -180.0f) diff += 360.0f;
            float turnFactor = 1.0f - (float) Math.pow(1.0f - 0.15f, dt * 60.0f);
            yaw += diff * turnFactor;
        }

        float speed = isLocalPlayer ? movementSpeed : (float) Math.hypot(vx, vz);
        if (visualModel != null) {
            visualModel.update(dt, movementFlags, speed);
        }
    }

    /**
     * Renders the actor using authentic GPU linear blend skinning and CharacterShader.
     */
    public void render(CharacterShader shader, Matrix4f proj, Matrix4f view) {
        if (!visible || visualModel == null) {
            return;
        }
        visualModel.render(shader, proj, view, x, y, z, yaw);
    }
}
