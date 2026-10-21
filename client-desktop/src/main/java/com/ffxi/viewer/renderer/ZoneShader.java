package com.ffxi.viewer.renderer;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL20C.*;

public final class ZoneShader implements AutoCloseable {
    private static final String VERTEX_SHADER = """
            #version 330 core

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;

            layout(location=0) in vec3 position;
            layout(location=1) in vec3 normal;
            layout(location=2) in vec2 textureCoords;
            layout(location=3) in vec4 vertexColor;

            out vec2 frag_textureCoords;
            out vec4 frag_color;

            void main() {
                frag_textureCoords = textureCoords;
                
                // Diffuse lighting
                vec3 lightDir = normalize(vec3(0.3, 0.9, 0.25));
                mat3 invTransModel = transpose(inverse(mat3(uModelMatrix)));
                vec3 worldSpaceNormal = normalize(invTransModel * normal);
                float diffuse = max(dot(worldSpaceNormal, lightDir), 0.0);
                vec3 litColor = vertexColor.rgb * (0.40 + (0.60 * diffuse));

                frag_color = vec4(litColor, vertexColor.a);
                gl_Position = uProjMatrix * uViewMatrix * uModelMatrix * vec4(position, 1.0);
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
    private final int projectionLocation;
    private final int viewLocation;
    private final int modelLocation;
    private final int discardThresholdLocation;

    public ZoneShader() {
        int vertexShaderId = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShaderId = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        programId = glCreateProgram();
        glAttachShader(programId, vertexShaderId);
        glAttachShader(programId, fragmentShaderId);
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to link zone shader: " + glGetProgramInfoLog(programId));
        }

        glDeleteShader(vertexShaderId);
        glDeleteShader(fragmentShaderId);

        projectionLocation = glGetUniformLocation(programId, "uProjMatrix");
        viewLocation = glGetUniformLocation(programId, "uViewMatrix");
        modelLocation = glGetUniformLocation(programId, "uModelMatrix");
        discardThresholdLocation = glGetUniformLocation(programId, "discardThreshold");

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

    @Override
    public void close() {
        glDeleteProgram(programId);
    }

    private static int compileShader(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile zone shader: " + glGetShaderInfoLog(id));
        }
        return id;
    }
}
