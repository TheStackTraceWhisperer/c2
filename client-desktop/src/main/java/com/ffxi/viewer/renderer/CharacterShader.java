package com.ffxi.viewer.renderer;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20C.GL_TRUE;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20C.glAttachShader;
import static org.lwjgl.opengl.GL20C.glCompileShader;
import static org.lwjgl.opengl.GL20C.glCreateProgram;
import static org.lwjgl.opengl.GL20C.glCreateShader;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glDeleteShader;
import static org.lwjgl.opengl.GL20C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20C.glGetProgrami;
import static org.lwjgl.opengl.GL20C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20C.glGetShaderi;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glLinkProgram;
import static org.lwjgl.opengl.GL20C.glShaderSource;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL31C.glUniformBlockBinding;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glBufferSubData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;

public final class CharacterShader implements AutoCloseable {
    public static final int MAX_JOINTS = 128;

    private static final String VERTEX_SHADER = """
            #version 330 core

            layout (std140) uniform JointMatrices {
                mat4 joints[128];
            };

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;

            layout(location=0) in vec3 position0;
            layout(location=1) in vec3 position1;
            layout(location=2) in vec3 normal0;
            layout(location=3) in vec3 normal1;
            layout(location=4) in vec2 textureCoords;
            layout(location=5) in float jointWeight;
            layout(location=6) in uint joint0;
            layout(location=7) in uint joint1;
            layout(location=8) in vec4 vertexColor;

            out vec2 frag_textureCoords;
            out vec4 frag_cameraSpacePos;
            out vec4 frag_color;
            out vec3 frag_normals;
            out vec3 cam_normals;

            void main() {
                frag_textureCoords = textureCoords;

                mat3 invTransModel = transpose(inverse(mat3(uModelMatrix)));
                vec4 skinnedNormal = jointWeight * (joints[int(joint0)] * vec4(normal0, 0.0))
                                   + (1.0 - jointWeight) * (joints[int(joint1)] * vec4(normal1, 0.0));
                vec3 worldSpaceNormal = normalize(invTransModel * normalize(skinnedNormal.xyz));
                frag_normals = worldSpaceNormal;
                cam_normals = normalize((uViewMatrix * vec4(worldSpaceNormal, 0.0)).xyz);

                vec4 modelSpacePos = (joints[int(joint0)] * vec4(position0, jointWeight))
                                   + (joints[int(joint1)] * vec4(position1, 1.0 - jointWeight));
                vec4 worldSpacePos = uModelMatrix * modelSpacePos;
                vec4 camSpacePos = uViewMatrix * worldSpacePos;

                vec3 lightDir = normalize(vec3(0.3, 0.9, 0.25));
                float diffuse = max(dot(worldSpaceNormal, lightDir), 0.0);
                vec3 litColor = vertexColor.rgb * (0.40 + (0.60 * diffuse));

                frag_color = vec4(litColor, vertexColor.a);
                frag_cameraSpacePos = camSpacePos;
                gl_Position = uProjMatrix * camSpacePos;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core

            uniform sampler2D diffuseTexture;
            uniform float discardThreshold;

            in vec2 frag_textureCoords;
            in vec4 frag_color;

            out vec4 outColor;

            void main() {
                vec4 basePixel = texture(diffuseTexture, frag_textureCoords);
                vec4 coloredPixel = vec4(2.0 * frag_color.rgb * basePixel.rgb, 4.0 * frag_color.a * basePixel.a);
                if (coloredPixel.a < discardThreshold) {
                    discard;
                }
                outColor = coloredPixel;
            }
            """;

    private final int programId;
    private final int jointBufferId;
    private final int projectionLocation;
    private final int viewLocation;
    private final int modelLocation;
    private final int discardThresholdLocation;
    private final FloatBuffer jointUploadBuffer = BufferUtils.createFloatBuffer(MAX_JOINTS * 16);

    public CharacterShader() {
        int vertexShaderId = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShaderId = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        programId = glCreateProgram();
        glAttachShader(programId, vertexShaderId);
        glAttachShader(programId, fragmentShaderId);
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to link character shader: " + glGetProgramInfoLog(programId));
        }

        glDeleteShader(vertexShaderId);
        glDeleteShader(fragmentShaderId);

        projectionLocation = glGetUniformLocation(programId, "uProjMatrix");
        viewLocation = glGetUniformLocation(programId, "uViewMatrix");
        modelLocation = glGetUniformLocation(programId, "uModelMatrix");
        discardThresholdLocation = glGetUniformLocation(programId, "discardThreshold");

        jointBufferId = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, jointBufferId);
        glBufferData(GL_UNIFORM_BUFFER, MAX_JOINTS * 16L * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);

        int blockIndex = glGetUniformBlockIndex(programId, "JointMatrices");
        glUniformBlockBinding(programId, blockIndex, 0);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, jointBufferId);

        bind();
        glUniform1i(glGetUniformLocation(programId, "diffuseTexture"), 0);
        glUniform1f(discardThresholdLocation, 0.375f);
    }

    public void bind() {
        glUseProgram(programId);
    }

    public void setMatrices(Matrix4f projection, Matrix4f view, Matrix4f model) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(projectionLocation, false, projection.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(viewLocation, false, view.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(modelLocation, false, model.get(stack.mallocFloat(16)));
        }
    }

    public void setDiscardThreshold(float discardThreshold) {
        glUniform1f(discardThresholdLocation, discardThreshold);
    }

    public void uploadJointMatrices(Matrix4f[] joints) {
        if (joints.length > MAX_JOINTS) {
            throw new IllegalArgumentException("Character has " + joints.length + " joints, but shader supports " + MAX_JOINTS);
        }

        jointUploadBuffer.clear();
        for (int i = 0; i < joints.length; i++) {
            joints[i].get(i * 16, jointUploadBuffer);
        }
        jointUploadBuffer.position(joints.length * 16);
        jointUploadBuffer.flip();

        glBindBuffer(GL_UNIFORM_BUFFER, jointBufferId);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, jointUploadBuffer);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    @Override
    public void close() {
        glDeleteBuffers(jointBufferId);
        glDeleteProgram(programId);
    }

    private static int compileShader(int shaderType, String source) {
        int shaderId = glCreateShader(shaderType);
        glShaderSource(shaderId, source);
        glCompileShader(shaderId);
        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile shader: " + glGetShaderInfoLog(shaderId));
        }
        return shaderId;
    }
}
