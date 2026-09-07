package com.example.client.desktop.camera;

/**
 * Camera abstraction providing orientation, spatial vectors, and coordinate projection
 * for third-person rendering and camera-relative character movement.
 */
public interface Camera {

    float getYaw();
    float getPitch();
    float getDistance();

    float getTargetX();
    float getTargetY();
    float getTargetZ();

    float getEyeX();
    float getEyeY();
    float getEyeZ();

    /**
     * Normalized 3D view vector pointing from eye to target.
     */
    float getViewX();
    float getViewY();
    float getViewZ();

    /**
     * Normalized horizontal forward vector (XZ plane, Y=0).
     */
    float getForwardX();
    float getForwardZ();

    /**
     * Normalized horizontal right vector (XZ plane, Y=0).
     */
    float getRightX();
    float getRightZ();

    void setTarget(float x, float y, float z);
    void setYaw(float yaw);
    void setPitch(float pitch);
    void setDistance(float distance);

    void rotate(float deltaYaw, float deltaPitch);
    void zoom(float deltaDistance);
    void reset();

    void update(long windowHandle, float dt);
}
