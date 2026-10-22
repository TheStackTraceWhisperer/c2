package com.ffxi.viewer.environment;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Authentic FFXI atmospheric weather particle system and elemental environment simulator.
 * Simulates camera-anchored weather particles (rain streaks, heavy rain squalls, snow crystals,
 * blizzard drifts, and sandstorm dust billboards) and manages dynamic lightning bolt flash cycles.
 */
public final class WeatherParticleSystem implements AutoCloseable {

    public static final int MAX_PARTICLES = 3000;
    private static final float EMITTER_RADIUS = 35.0f;
    private static final float EMITTER_HEIGHT = 25.0f;

    private static final String PARTICLE_VERT_SRC = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec4 aColor;

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;

            out vec4 vColor;

            void main() {
                vColor = aColor;
                gl_Position = uProjMatrix * uViewMatrix * vec4(aPos, 1.0);
            }
            """;

    private static final String PARTICLE_FRAG_SRC = """
            #version 330 core
            in vec4 vColor;
            out vec4 FragColor;

            void main() {
                FragColor = vColor;
            }
            """;

    private final float[] posX = new float[MAX_PARTICLES];
    private final float[] posY = new float[MAX_PARTICLES];
    private final float[] posZ = new float[MAX_PARTICLES];
    private final float[] velX = new float[MAX_PARTICLES];
    private final float[] velY = new float[MAX_PARTICLES];
    private final float[] velZ = new float[MAX_PARTICLES];
    private final float[] length = new float[MAX_PARTICLES];
    private final float[] life = new float[MAX_PARTICLES];

    private final Random random = new Random(1337);

    private final int shaderProgram;
    private final int uProjLoc;
    private final int uViewLoc;
    private final int vaoId;
    private final int vboId;

    private final ByteBuffer vertexBuffer = BufferUtils.createByteBuffer(MAX_PARTICLES * 2 * 28); // 2 vertices per line streak

    // Lightning flash simulation
    private float lightningTimer = 4.0f;
    private float lightningFlashDuration = 0.0f;
    private float lightningIntensity = 0.0f;
    private boolean lightningActive = false;

    public WeatherParticleSystem() {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, PARTICLE_VERT_SRC);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile particle vertex shader: " + glGetShaderInfoLog(vs));
        }

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, PARTICLE_FRAG_SRC);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile particle fragment shader: " + glGetShaderInfoLog(fs));
        }

        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vs);
        glAttachShader(shaderProgram, fs);
        glLinkProgram(shaderProgram);
        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to link particle program: " + glGetProgramInfoLog(shaderProgram));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);

        uProjLoc = glGetUniformLocation(shaderProgram, "uProjMatrix");
        uViewLoc = glGetUniformLocation(shaderProgram, "uViewMatrix");

        vaoId = glGenVertexArrays();
        vboId = glGenBuffers();

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_PARTICLES * 2 * 28, GL_DYNAMIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);

        // Pre-initialize particles randomly around origin
        for (int i = 0; i < MAX_PARTICLES; i++) {
            resetParticle(i, new Vector3f(0f, 0f, 0f), WeatherPreset.WeatherEffectType.RAIN, new Vector3f(0f, -10f, 0f));
            life[i] = random.nextFloat();
        }
    }

    private void resetParticle(int i, Vector3f origin, WeatherPreset.WeatherEffectType type, Vector3f wind) {
        float angle = random.nextFloat() * (float) (2.0 * Math.PI);
        float dist = (float) Math.sqrt(random.nextFloat()) * EMITTER_RADIUS;

        posX[i] = origin.x + (float) Math.cos(angle) * dist;
        posY[i] = origin.y + random.nextFloat() * EMITTER_HEIGHT;
        posZ[i] = origin.z + (float) Math.sin(angle) * dist;

        switch (type) {
            case RAIN -> {
                velX[i] = wind.x + (random.nextFloat() - 0.5f) * 1.5f;
                velY[i] = -22.0f - random.nextFloat() * 6.0f;
                velZ[i] = wind.z + (random.nextFloat() - 0.5f) * 1.5f;
                length[i] = 0.9f + random.nextFloat() * 0.5f;
            }
            case HEAVY_RAIN, THUNDERSTORM -> {
                velX[i] = wind.x * 1.4f + (random.nextFloat() - 0.5f) * 2.5f;
                velY[i] = -32.0f - random.nextFloat() * 10.0f;
                velZ[i] = wind.z * 1.4f + (random.nextFloat() - 0.5f) * 2.5f;
                length[i] = 1.4f + random.nextFloat() * 0.8f;
            }
            case SNOW -> {
                velX[i] = wind.x * 0.4f + (random.nextFloat() - 0.5f) * 2.0f;
                velY[i] = -2.8f - random.nextFloat() * 1.5f;
                velZ[i] = wind.z * 0.4f + (random.nextFloat() - 0.5f) * 2.0f;
                length[i] = 0.12f + random.nextFloat() * 0.10f;
            }
            case BLIZZARD -> {
                velX[i] = wind.x * 1.2f + (random.nextFloat() - 0.5f) * 4.0f;
                velY[i] = -5.5f - random.nextFloat() * 3.0f;
                velZ[i] = wind.z * 1.2f + (random.nextFloat() - 0.5f) * 4.0f;
                length[i] = 0.25f + random.nextFloat() * 0.20f;
            }
            case SANDSTORM, DUST_STORM -> {
                velX[i] = wind.x * 1.5f + (random.nextFloat() - 0.5f) * 3.0f;
                velY[i] = (random.nextFloat() - 0.4f) * 3.5f;
                velZ[i] = wind.z * 1.5f + (random.nextFloat() - 0.5f) * 3.0f;
                length[i] = 0.35f + random.nextFloat() * 0.35f;
            }
            default -> {
                velX[i] = 0f; velY[i] = 0f; velZ[i] = 0f;
                length[i] = 0f;
            }
        }
        life[i] = 1.0f;
    }

    /**
     * Updates physics and life cycles for all active weather particles and advances lightning timers.
     */
    public void update(float dt, Vector3f cameraPos, WeatherPreset preset) {
        WeatherPreset.WeatherEffectType effect = preset.effectType();
        Vector3f wind = preset.windVector();

        // 1. Advance lightning cycle for thunder/bolt weathers
        if (effect == WeatherPreset.WeatherEffectType.THUNDERSTORM) {
            lightningTimer -= dt;
            if (lightningTimer <= 0.0f && !lightningActive) {
                lightningActive = true;
                lightningFlashDuration = 0.22f + random.nextFloat() * 0.15f;
                lightningTimer = 3.5f + random.nextFloat() * 6.0f;
            }

            if (lightningActive) {
                lightningFlashDuration -= dt;
                if (lightningFlashDuration <= 0.0f) {
                    lightningActive = false;
                    lightningIntensity = 0.0f;
                } else {
                    // Strobe double-flash pattern
                    float phase = lightningFlashDuration * 25.0f;
                    lightningIntensity = (float) Math.max(0.0, Math.sin(phase)) * 2.2f;
                }
            } else {
                lightningIntensity = 0.0f;
            }
        } else {
            lightningActive = false;
            lightningIntensity = 0.0f;
        }

        if (effect == WeatherPreset.WeatherEffectType.NONE || effect == WeatherPreset.WeatherEffectType.HEAT_WAVE || effect == WeatherPreset.WeatherEffectType.AURORA) {
            return;
        }

        // 2. Update particle positions
        int activeCount = getActiveParticleCount(effect);
        for (int i = 0; i < activeCount; i++) {
            posX[i] += velX[i] * dt;
            posY[i] += velY[i] * dt;
            posZ[i] += velZ[i] * dt;

            // Check boundaries relative to camera
            float dx = posX[i] - cameraPos.x;
            float dy = posY[i] - cameraPos.y;
            float dz = posZ[i] - cameraPos.z;

            if (dy < -5.0f || dy > EMITTER_HEIGHT + 5.0f || (dx * dx + dz * dz) > EMITTER_RADIUS * EMITTER_RADIUS) {
                resetParticle(i, cameraPos, effect, wind);
            }
        }
    }

    private int getActiveParticleCount(WeatherPreset.WeatherEffectType effect) {
        return switch (effect) {
            case RAIN -> 1200;
            case HEAVY_RAIN, THUNDERSTORM -> 2600;
            case SNOW -> 800;
            case BLIZZARD -> 2200;
            case SANDSTORM -> 1800;
            case DUST_STORM -> 900;
            default -> 0;
        };
    }

    /**
     * Renders active weather streaks/particles.
     */
    public void render(Matrix4f projMatrix, Matrix4f viewMatrix, WeatherPreset preset) {
        WeatherPreset.WeatherEffectType effect = preset.effectType();
        int activeCount = getActiveParticleCount(effect);
        if (activeCount == 0) {
            return;
        }

        Vector4f col0;
        Vector4f col1;

        switch (effect) {
            case RAIN -> {
                col0 = new Vector4f(0.70f, 0.78f, 0.88f, 0.40f);
                col1 = new Vector4f(0.55f, 0.65f, 0.80f, 0.10f);
            }
            case HEAVY_RAIN, THUNDERSTORM -> {
                col0 = new Vector4f(0.80f, 0.85f, 0.95f, 0.55f);
                col1 = new Vector4f(0.65f, 0.75f, 0.90f, 0.15f);
            }
            case SNOW -> {
                col0 = new Vector4f(0.95f, 0.98f, 1.00f, 0.85f);
                col1 = new Vector4f(0.85f, 0.92f, 1.00f, 0.40f);
            }
            case BLIZZARD -> {
                col0 = new Vector4f(0.98f, 0.98f, 1.00f, 0.90f);
                col1 = new Vector4f(0.88f, 0.94f, 1.00f, 0.50f);
            }
            case SANDSTORM, DUST_STORM -> {
                col0 = new Vector4f(0.85f, 0.72f, 0.48f, 0.60f);
                col1 = new Vector4f(0.65f, 0.52f, 0.32f, 0.20f);
            }
            default -> {
                col0 = new Vector4f(1f, 1f, 1f, 0.5f);
                col1 = new Vector4f(1f, 1f, 1f, 0.1f);
            }
        }

        vertexBuffer.clear();
        for (int i = 0; i < activeCount; i++) {
            float x0 = posX[i];
            float y0 = posY[i];
            float z0 = posZ[i];

            // Velocity-aligned streak
            float len = length[i];
            float vxNorm = velX[i];
            float vyNorm = velY[i];
            float vzNorm = velZ[i];
            float speed = (float) Math.sqrt(vxNorm * vxNorm + vyNorm * vyNorm + vzNorm * vzNorm);
            if (speed > 1e-4f) {
                vxNorm = (vxNorm / speed) * len;
                vyNorm = (vyNorm / speed) * len;
                vzNorm = (vzNorm / speed) * len;
            } else {
                vyNorm = -len;
            }

            float x1 = x0 - vxNorm;
            float y1 = y0 - vyNorm;
            float z1 = z0 - vzNorm;

            // Head vertex
            vertexBuffer.putFloat(x0).putFloat(y0).putFloat(z0);
            vertexBuffer.putFloat(col0.x).putFloat(col0.y).putFloat(col0.z).putFloat(col0.w);

            // Tail vertex
            vertexBuffer.putFloat(x1).putFloat(y1).putFloat(z1);
            vertexBuffer.putFloat(col1.x).putFloat(col1.y).putFloat(col1.z).putFloat(col1.w);
        }
        vertexBuffer.flip();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);

        glUseProgram(shaderProgram);

        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        projMatrix.get(fb);
        glUniformMatrix4fv(uProjLoc, false, fb);

        fb.clear();
        viewMatrix.get(fb);
        glUniformMatrix4fv(uViewLoc, false, fb);

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        glDrawArrays(GL_LINES, 0, activeCount * 2);

        glBindVertexArray(0);
        glUseProgram(0);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    public float getLightningIntensity() {
        return lightningIntensity;
    }

    public boolean isLightningActive() {
        return lightningActive;
    }

    @Override
    public void close() {
        if (vboId != 0) glDeleteBuffers(vboId);
        if (vaoId != 0) glDeleteVertexArrays(vaoId);
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);
    }
}
