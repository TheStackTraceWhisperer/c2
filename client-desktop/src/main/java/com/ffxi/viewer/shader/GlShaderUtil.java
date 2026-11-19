package com.ffxi.viewer.shader;

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
import static org.lwjgl.opengl.GL20C.glDetachShader;
import static org.lwjgl.opengl.GL20C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20C.glGetProgrami;
import static org.lwjgl.opengl.GL20C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20C.glGetShaderi;
import static org.lwjgl.opengl.GL20C.glLinkProgram;
import static org.lwjgl.opengl.GL20C.glShaderSource;

/**
 * Shared OpenGL shader compilation and program linking utility.
 * Guarantees that intermediate shader and program handles are deleted on compile or link failure
 * so failed shader builds never leak GPU handles.
 */
public final class GlShaderUtil {

    private GlShaderUtil() {
    }

    public static int compileShader(int shaderType, String source, String label) {
        int shaderId = glCreateShader(shaderType);
        glShaderSource(shaderId, source);
        glCompileShader(shaderId);
        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) != GL_TRUE) {
            String infoLog = glGetShaderInfoLog(shaderId);
            glDeleteShader(shaderId);
            throw new IllegalStateException("Failed to compile " + label + " shader: " + infoLog);
        }
        return shaderId;
    }

    public static int createProgram(String vertexSource, String fragmentSource, String label) {
        int vs = compileShader(GL_VERTEX_SHADER, vertexSource, label + " vertex");
        int fs;
        try {
            fs = compileShader(GL_FRAGMENT_SHADER, fragmentSource, label + " fragment");
        } catch (RuntimeException e) {
            glDeleteShader(vs);
            throw e;
        }

        int programId = glCreateProgram();
        glAttachShader(programId, vs);
        glAttachShader(programId, fs);
        glLinkProgram(programId);

        glDetachShader(programId, vs);
        glDetachShader(programId, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);

        if (glGetProgrami(programId, GL_LINK_STATUS) != GL_TRUE) {
            String infoLog = glGetProgramInfoLog(programId);
            glDeleteProgram(programId);
            throw new IllegalStateException("Failed to link " + label + " program: " + infoLog);
        }
        return programId;
    }
}
