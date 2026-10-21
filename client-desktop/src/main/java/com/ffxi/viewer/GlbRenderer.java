package com.ffxi.viewer;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class GlbRenderer {

    private static final String VERTEX_SHADER_SRC =
            "#version 330\n" +
            "layout(location=0) in vec3 position;\n" +
            "layout(location=1) in vec3 normal;\n" +
            "layout(location=2) in vec2 texCoords;\n" +
            "layout(location=3) in vec4 color;\n" +
            "uniform mat4 uProjMatrix;\n" +
            "uniform mat4 uViewMatrix;\n" +
            "uniform mat4 uModelMatrix;\n" +
            "out vec4 fragColor;\n" +
            "out vec2 fragTexCoords;\n" +
            "void main() {\n" +
            "    vec3 lightDir = normalize(vec3(0.3, 0.9, 0.25));\n" +
            "    mat3 normalMatrix = transpose(inverse(mat3(uModelMatrix)));\n" +
            "    vec3 worldNormal = normalize(normalMatrix * normal);\n" +
            "    float diffuse = max(dot(worldNormal, lightDir), 0.0);\n" +
            "    fragColor = color * (0.4 + 0.6 * diffuse);\n" +
            "    fragTexCoords = texCoords;\n" +
            "    gl_Position = uProjMatrix * uViewMatrix * uModelMatrix * vec4(position, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_SRC =
            "#version 330\n" +
            "uniform sampler2D uDiffuseTexture;\n" +
            "uniform float uDiscardThreshold;\n" +
            "in vec4 fragColor;\n" +
            "in vec2 fragTexCoords;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            "    vec4 texColor = texture(uDiffuseTexture, fragTexCoords);\n" +
            "    vec4 coloredPixel = vec4(2.0 * fragColor.rgb * texColor.rgb, 4.0 * fragColor.a * texColor.a);\n" +
            "    if (coloredPixel.a < uDiscardThreshold) {\n" +
            "        discard;\n" +
            "    }\n" +
            "    outColor = coloredPixel;\n" +
            "}\n";

    private static class Accessor {
        int bufferView;
        int byteOffset;
        int componentType;
        int count;
        String type;
    }

    private static class BufferView {
        int byteOffset;
        int byteLength;
    }

    private static class Material {
        int textureIndex = -1;
        String alphaMode = "OPAQUE";
        float alphaCutoff = 0.0f;
    }

    private static class Texture {
        int source = -1;
    }

    private static class Image {
        int bufferView = -1;
    }

    private static class Primitive {
        int positionAcc;
        int normalAcc;
        int texCoordAcc;
        int colorAcc = -1;
        int indicesAcc;
        int material = -1;
        int vao, vbo, ebo;
        int indexCount;
        int indexType;
        int glTexId;
        String alphaMode = "OPAQUE";
        float alphaCutoff = 0.0f;
    }

    private static class Mesh {
        String name;
        List<Primitive> primitives = new ArrayList<>();
    }

    private static class Node {
        String name;
        List<Integer> children = new ArrayList<>();
        Integer mesh;
        float[] translation;
        float[] rotation;
        float[] scale;
    }

    private long window;
    private int programId;
    private int uProjMatrixLoc;
    private int uViewMatrixLoc;
    private int uModelMatrixLoc;
    private int uDiscardThresholdLoc;
    private int defaultTexId;

    private final List<Accessor> accessors = new ArrayList<>();
    private final List<BufferView> bufferViews = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();
    private final List<Texture> textures = new ArrayList<>();
    private final List<Image> images = new ArrayList<>();
    private final List<Integer> glTextures = new ArrayList<>();
    private final List<Mesh> meshes = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private int rootNodeIndex = -1;

    private float orbitYaw = 0.0f;
    private float orbitPitch = 0.5f;
    private float orbitDistance = 400.0f;
    private float targetX = 0.0f;
    private float targetY = 0.0f;
    private float targetZ = 0.0f;

    public static void main(String[] args) {
        String glbPath = args.length > 0 ? args[0] : "/home/samuel/projects/ffxi-xim-analysis/dumps/zone-geometry/zone_100.glb";
        File file = new File(glbPath);
        if (!file.exists()) {
            System.err.println("GLB file not found: " + file.getAbsolutePath());
            System.exit(1);
        }
        new GlbRenderer().run(file);
    }

    public void run(File glbFile) {
        initWindow();
        initGL();
        loadGlb(glbFile);
        loop();
        cleanup();
    }

    private void initWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "FFXI GLB Viewer", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true);
            }
        });

        final double[] lastX = new double[1];
        final double[] lastY = new double[1];
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
                float dx = (float) (xpos - lastX[0]);
                float dy = (float) (ypos - lastY[0]);
                orbitYaw -= dx * 0.005f;
                orbitPitch = Math.max(-1.4f, Math.min(1.4f, orbitPitch + dy * 0.005f));
            }
            lastX[0] = xpos;
            lastY[0] = ypos;
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            orbitDistance = Math.max(10.0f, orbitDistance - (float) yoffset * 20.0f);
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidmode != null) {
            glfwSetWindowPos(window, (vidmode.width() - 1280) / 2, (vidmode.height() - 720) / 2);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private void initGL() {
        GL.createCapabilities();
        glClearColor(0.5f, 0.5f, 0.52f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);

        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, VERTEX_SHADER_SRC);
        glCompileShader(vs);
        checkShader(vs);

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, FRAGMENT_SHADER_SRC);
        glCompileShader(fs);
        checkShader(fs);

        programId = glCreateProgram();
        glAttachShader(programId, vs);
        glAttachShader(programId, fs);
        glLinkProgram(programId);
        checkProgram(programId);

        glDeleteShader(vs);
        glDeleteShader(fs);

        uProjMatrixLoc = glGetUniformLocation(programId, "uProjMatrix");
        uViewMatrixLoc = glGetUniformLocation(programId, "uViewMatrix");
        uModelMatrixLoc = glGetUniformLocation(programId, "uModelMatrix");
        uDiscardThresholdLoc = glGetUniformLocation(programId, "uDiscardThreshold");

        glUseProgram(programId);
        glUniform1i(glGetUniformLocation(programId, "uDiffuseTexture"), 0);
        glUseProgram(0);

        defaultTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, defaultTexId);
        ByteBuffer whitePixel = BufferUtils.createByteBuffer(4);
        whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, whitePixel);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void checkShader(int shader) {
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader compilation failed: " + glGetShaderInfoLog(shader));
        }
    }

    private void checkProgram(int prog) {
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Program linking failed: " + glGetProgramInfoLog(prog));
        }
    }

    private void loadGlb(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[12];
            if (fis.read(header) != 12) throw new IOException("Invalid GLB header");
            ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int magic = hb.getInt();
            hb.getInt(); // version
            hb.getInt(); // length
            if (magic != 0x46546C67) throw new IOException("Not a GLB file");

            byte[] chunkHeader = new byte[8];
            if (fis.read(chunkHeader) != 8) throw new IOException("Missing JSON chunk header");
            ByteBuffer jb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN);
            int jsonLength = jb.getInt();
            int jsonType = jb.getInt();
            if (jsonType != 0x4E4F534A) throw new IOException("Expected JSON chunk");

            byte[] jsonBytes = new byte[jsonLength];
            if (fis.read(jsonBytes) != jsonLength) throw new IOException("Incomplete JSON chunk");
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            if (fis.read(chunkHeader) != 8) throw new IOException("Missing BIN chunk header");
            ByteBuffer bb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN);
            int binLength = bb.getInt();
            int binType = bb.getInt();
            if (binType != 0x004E4942) throw new IOException("Expected BIN chunk");

            byte[] binBytes = new byte[binLength];
            if (fis.read(binBytes) != binLength) throw new IOException("Incomplete BIN chunk");
            ByteBuffer binBuffer = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN);

            parseJson(json);
            loadGlTextures(binBuffer);
            compileGeometry(binBuffer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read GLB: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void parseJson(String json) {
        Map<String, Object> root = (Map<String, Object>) JsonParser.parse(json);

        List<Object> bvs = (List<Object>) root.get("bufferViews");
        if (bvs != null) {
            for (Object o : bvs) {
                Map<String, Object> m = (Map<String, Object>) o;
                BufferView bv = new BufferView();
                bv.byteOffset = m.containsKey("byteOffset") ? ((Number) m.get("byteOffset")).intValue() : 0;
                bv.byteLength = ((Number) m.get("byteLength")).intValue();
                bufferViews.add(bv);
            }
        }

        List<Object> accs = (List<Object>) root.get("accessors");
        if (accs != null) {
            for (Object o : accs) {
                Map<String, Object> m = (Map<String, Object>) o;
                Accessor acc = new Accessor();
                acc.bufferView = ((Number) m.get("bufferView")).intValue();
                acc.componentType = ((Number) m.get("componentType")).intValue();
                acc.count = ((Number) m.get("count")).intValue();
                acc.type = (String) m.get("type");
                acc.byteOffset = m.containsKey("byteOffset") ? ((Number) m.get("byteOffset")).intValue() : 0;
                accessors.add(acc);
            }
        }

        List<Object> mats = (List<Object>) root.get("materials");
        if (mats != null) {
            for (Object o : mats) {
                Map<String, Object> m = (Map<String, Object>) o;
                Material material = new Material();
                Map<String, Object> pbr = (Map<String, Object>) m.get("pbrMetallicRoughness");
                if (pbr != null && pbr.containsKey("baseColorTexture")) {
                    Map<String, Object> baseColorTexture = (Map<String, Object>) pbr.get("baseColorTexture");
                    material.textureIndex = ((Number) baseColorTexture.get("index")).intValue();
                }
                if (m.containsKey("alphaMode")) {
                    material.alphaMode = (String) m.get("alphaMode");
                }
                if (m.containsKey("alphaCutoff")) {
                    material.alphaCutoff = ((Number) m.get("alphaCutoff")).floatValue();
                } else if ("MASK".equals(material.alphaMode)) {
                    material.alphaCutoff = 0.375f;
                }
                materials.add(material);
            }
        }

        List<Object> texs = (List<Object>) root.get("textures");
        if (texs != null) {
            for (Object o : texs) {
                Map<String, Object> m = (Map<String, Object>) o;
                Texture texture = new Texture();
                texture.source = ((Number) m.get("source")).intValue();
                textures.add(texture);
            }
        }

        List<Object> imgs = (List<Object>) root.get("images");
        if (imgs != null) {
            for (Object o : imgs) {
                Map<String, Object> m = (Map<String, Object>) o;
                Image image = new Image();
                if (m.containsKey("bufferView")) {
                    image.bufferView = ((Number) m.get("bufferView")).intValue();
                }
                images.add(image);
            }
        }

        List<Object> mshs = (List<Object>) root.get("meshes");
        if (mshs != null) {
            for (Object o : mshs) {
                Map<String, Object> m = (Map<String, Object>) o;
                Mesh mesh = new Mesh();
                mesh.name = (String) m.get("name");
                List<Object> prims = (List<Object>) m.get("primitives");
                for (Object po : prims) {
                    Map<String, Object> pm = (Map<String, Object>) po;
                    Primitive prim = new Primitive();
                    prim.indicesAcc = ((Number) pm.get("indices")).intValue();
                    if (pm.containsKey("material")) {
                        prim.material = ((Number) pm.get("material")).intValue();
                    }

                    Map<String, Object> attribs = (Map<String, Object>) pm.get("attributes");
                    prim.positionAcc = ((Number) attribs.get("POSITION")).intValue();
                    prim.normalAcc = ((Number) attribs.get("NORMAL")).intValue();
                    prim.texCoordAcc = ((Number) attribs.get("TEXCOORD_0")).intValue();
                    if (attribs.containsKey("COLOR_0")) {
                        prim.colorAcc = ((Number) attribs.get("COLOR_0")).intValue();
                    }
                    mesh.primitives.add(prim);
                }
                meshes.add(mesh);
            }
        }

        List<Object> nds = (List<Object>) root.get("nodes");
        if (nds != null) {
            for (Object o : nds) {
                Map<String, Object> m = (Map<String, Object>) o;
                Node node = new Node();
                node.name = (String) m.get("name");
                if (m.containsKey("children")) {
                    for (Object co : (List<Object>) m.get("children")) {
                        node.children.add(((Number) co).intValue());
                    }
                }
                if (m.containsKey("mesh")) {
                    node.mesh = ((Number) m.get("mesh")).intValue();
                }
                if (m.containsKey("translation")) {
                    node.translation = toFloatArray((List<Object>) m.get("translation"));
                }
                if (m.containsKey("rotation")) {
                    node.rotation = toFloatArray((List<Object>) m.get("rotation"));
                }
                if (m.containsKey("scale")) {
                    node.scale = toFloatArray((List<Object>) m.get("scale"));
                }
                nodes.add(node);
            }
        }

        List<Object> scenes = (List<Object>) root.get("scenes");
        if (scenes != null && !scenes.isEmpty()) {
            Map<String, Object> sc = (Map<String, Object>) scenes.get(0);
            List<Object> scNodes = (List<Object>) sc.get("nodes");
            if (scNodes != null && !scNodes.isEmpty()) {
                rootNodeIndex = ((Number) scNodes.get(0)).intValue();
            }
        }
        if (rootNodeIndex == -1) rootNodeIndex = 0;
    }

    private float[] toFloatArray(List<Object> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = ((Number) list.get(i)).floatValue();
        }
        return arr;
    }

    private void loadGlTextures(ByteBuffer binBuffer) {
        for (int i = 0; i < images.size(); i++) {
            Image img = images.get(i);
            if (img.bufferView != -1) {
                BufferView bv = bufferViews.get(img.bufferView);
                binBuffer.position(bv.byteOffset);
                byte[] pngBytes = new byte[bv.byteLength];
                binBuffer.get(pngBytes);

                try {
                    java.awt.image.BufferedImage imgData = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
                    if (imgData != null) {
                        int w = imgData.getWidth();
                        int h = imgData.getHeight();
                        System.out.println("Loaded image " + i + ": " + w + "x" + h + " bytes=" + bv.byteLength);

                        int[] pixels = new int[w * h];
                        imgData.getRGB(0, 0, w, h, pixels, 0, w);

                        ByteBuffer rgbaBuffer = BufferUtils.createByteBuffer(w * h * 4);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int pixel = pixels[y * w + x];
                                rgbaBuffer.put((byte) ((pixel >> 16) & 0xFF));
                                rgbaBuffer.put((byte) ((pixel >> 8) & 0xFF));
                                rgbaBuffer.put((byte) (pixel & 0xFF));
                                rgbaBuffer.put((byte) ((pixel >> 24) & 0xFF));
                            }
                        }
                        rgbaBuffer.flip();

                        int tex = glGenTextures();
                        glBindTexture(GL_TEXTURE_2D, tex);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgbaBuffer);
                        glGenerateMipmap(GL_TEXTURE_2D);
                        glBindTexture(GL_TEXTURE_2D, 0);
                        glTextures.add(tex);
                    } else {
                        System.err.println("ImageIO.read returned null for image " + i + " bytes=" + bv.byteLength);
                        glTextures.add(0);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse PNG for image " + i + " bytes=" + bv.byteLength + ": " + e.getMessage());
                    glTextures.add(0);
                }
                binBuffer.clear();
            } else {
                System.err.println("Image " + i + " has no bufferView");
                glTextures.add(0);
            }
        }
    }

    private void compileGeometry(ByteBuffer bin) {
        for (Mesh mesh : meshes) {
            for (Primitive prim : mesh.primitives) {
                Accessor posAcc = accessors.get(prim.positionAcc);
                Accessor normAcc = accessors.get(prim.normalAcc);
                Accessor uvAcc = accessors.get(prim.texCoordAcc);
                Accessor colAcc = prim.colorAcc != -1 ? accessors.get(prim.colorAcc) : null;
                Accessor indAcc = accessors.get(prim.indicesAcc);

                BufferView posBv = bufferViews.get(posAcc.bufferView);
                BufferView normBv = bufferViews.get(normAcc.bufferView);
                BufferView uvBv = bufferViews.get(uvAcc.bufferView);
                BufferView colBv = colAcc != null ? bufferViews.get(colAcc.bufferView) : null;
                BufferView indBv = bufferViews.get(indAcc.bufferView);

                int numVerts = posAcc.count;
                int stride = 48; // pos(3) + norm(3) + uv(2) + col(4) = 12 floats = 48 bytes
                ByteBuffer vertexData = BufferUtils.createByteBuffer(numVerts * stride);

                for (int i = 0; i < numVerts; i++) {
                    int posOffset = posBv.byteOffset + posAcc.byteOffset + i * 12;
                    vertexData.putFloat(bin.getFloat(posOffset));
                    vertexData.putFloat(bin.getFloat(posOffset + 4));
                    vertexData.putFloat(bin.getFloat(posOffset + 8));

                    int normOffset = normBv.byteOffset + normAcc.byteOffset + i * 12;
                    vertexData.putFloat(bin.getFloat(normOffset));
                    vertexData.putFloat(bin.getFloat(normOffset + 4));
                    vertexData.putFloat(bin.getFloat(normOffset + 8));

                    int uvOffset = uvBv.byteOffset + uvAcc.byteOffset + i * 8;
                    vertexData.putFloat(bin.getFloat(uvOffset));
                    vertexData.putFloat(bin.getFloat(uvOffset + 4));

                    if (colAcc != null && colBv != null) {
                        int colOffset = colBv.byteOffset + colAcc.byteOffset + i * 16;
                        vertexData.putFloat(bin.getFloat(colOffset));
                        vertexData.putFloat(bin.getFloat(colOffset + 4));
                        vertexData.putFloat(bin.getFloat(colOffset + 8));
                        vertexData.putFloat(bin.getFloat(colOffset + 12));
                    } else {
                        vertexData.putFloat(1.0f);
                        vertexData.putFloat(1.0f);
                        vertexData.putFloat(1.0f);
                        vertexData.putFloat(1.0f);
                    }
                }
                vertexData.flip();

                prim.vao = glGenVertexArrays();
                prim.vbo = glGenBuffers();
                prim.ebo = glGenBuffers();

                glBindVertexArray(prim.vao);
                glBindBuffer(GL_ARRAY_BUFFER, prim.vbo);
                glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);

                int indexSize = indAcc.componentType == GL_UNSIGNED_SHORT ? 2 : 4;
                ByteBuffer indexData = BufferUtils.createByteBuffer(indAcc.count * indexSize);
                bin.position(indBv.byteOffset + indAcc.byteOffset);
                bin.limit(indBv.byteOffset + indAcc.byteOffset + indAcc.count * indexSize);
                indexData.put(bin);
                indexData.flip();

                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, prim.ebo);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexData, GL_STATIC_DRAW);

                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);

                glEnableVertexAttribArray(1);
                glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12L);

                glEnableVertexAttribArray(2);
                glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 24L);

                glEnableVertexAttribArray(3);
                glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 32L);

                prim.indexCount = indAcc.count;
                prim.indexType = indAcc.componentType;

                // Bind texture mapping
                prim.glTexId = defaultTexId;
                prim.alphaMode = "OPAQUE";
                prim.alphaCutoff = 0.0f;
                if (prim.material >= 0 && prim.material < materials.size()) {
                    Material mat = materials.get(prim.material);
                    prim.alphaMode = mat.alphaMode;
                    prim.alphaCutoff = mat.alphaCutoff;
                    if (mat.textureIndex >= 0 && mat.textureIndex < textures.size()) {
                        Texture tex = textures.get(mat.textureIndex);
                        if (tex.source >= 0 && tex.source < glTextures.size()) {
                            prim.glTexId = glTextures.get(tex.source);
                        } else {
                            System.err.println("Tex source out of bounds: " + tex.source + " of " + glTextures.size());
                        }
                    } else if (mat.textureIndex != -1) {
                        System.err.println("Tex index out of bounds: " + mat.textureIndex + " of " + textures.size());
                    }
                } else if (prim.material != -1) {
                    System.err.println("Mat index out of bounds: " + prim.material + " of " + materials.size());
                }

                glBindVertexArray(0);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
                bin.clear();
            }
        }
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glUseProgram(programId);

            int[] width = new int[1];
            int[] height = new int[1];
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);

            float aspect = (float) width[0] / (float) height[0];
            Matrix4f projectionMatrix = new Matrix4f().perspective((float) Math.toRadians(50.0), aspect, 1.0f, 100000.0f);
            Matrix4f viewMatrix = createViewMatrix();

            float[] pm = new float[16];
            projectionMatrix.get(pm);
            glUniformMatrix4fv(uProjMatrixLoc, false, pm);

            float[] vm = new float[16];
            viewMatrix.get(vm);
            glUniformMatrix4fv(uViewMatrixLoc, false, vm);

            glActiveTexture(GL_TEXTURE0);

            if (rootNodeIndex >= 0 && rootNodeIndex < nodes.size()) {
                renderNode(rootNodeIndex, new Matrix4f());
            }

            glUseProgram(0);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private Matrix4f createViewMatrix() {
        Vector3f target = new Vector3f(targetX, targetY, targetZ);
        Vector3f eye = new Vector3f(
                (float) (orbitDistance * Math.cos(orbitPitch) * Math.sin(orbitYaw)) + targetX,
                (float) (orbitDistance * Math.sin(orbitPitch)) + targetY,
                (float) (orbitDistance * Math.cos(orbitPitch) * Math.cos(orbitYaw)) + targetZ
        );
        return new Matrix4f().lookAt(eye, target, new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private void renderNode(int nodeIdx, Matrix4f parentTransform) {
        Node node = nodes.get(nodeIdx);
        Matrix4f localTransform = new Matrix4f();
        if (node.translation != null) {
            localTransform.translate(node.translation[0], node.translation[1], node.translation[2]);
        }
        if (node.rotation != null) {
            localTransform.rotate(new Quaternionf(node.rotation[0], node.rotation[1], node.rotation[2], node.rotation[3]));
        }
        if (node.scale != null) {
            localTransform.scale(node.scale[0], node.scale[1], node.scale[2]);
        }

        Matrix4f worldTransform = new Matrix4f(parentTransform).mul(localTransform);

        if (node.mesh != null) {
            Mesh mesh = meshes.get(node.mesh);
            float[] wm = new float[16];
            worldTransform.get(wm);
            glUniformMatrix4fv(uModelMatrixLoc, false, wm);

            boolean inverted = worldTransform.determinant() < 0.0f;
            if (inverted) {
                glFrontFace(GL_CW);
            }

            for (Primitive prim : mesh.primitives) {
                boolean blend = "BLEND".equals(prim.alphaMode);
                if (blend) {
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                } else {
                    glDisable(GL_BLEND);
                }

                glUniform1f(uDiscardThresholdLoc, prim.alphaCutoff);

                glBindTexture(GL_TEXTURE_2D, prim.glTexId);
                glBindVertexArray(prim.vao);
                glDrawElements(GL_TRIANGLES, prim.indexCount, prim.indexType, 0L);
            }
            glDisable(GL_BLEND);
            glBindVertexArray(0);

            if (inverted) {
                glFrontFace(GL_CCW);
            }
        }

        for (int childIdx : node.children) {
            renderNode(childIdx, worldTransform);
        }
    }

    private void cleanup() {
        glUseProgram(0);
        glDeleteProgram(programId);
        for (Mesh mesh : meshes) {
            for (Primitive prim : mesh.primitives) {
                glDeleteBuffers(prim.ebo);
                glDeleteBuffers(prim.vbo);
                glDeleteVertexArrays(prim.vao);
            }
        }
        for (int tex : glTextures) {
            glDeleteTextures(tex);
        }
        glDeleteTextures(defaultTexId);
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static class JsonParser {
        private final String src;
        private int pos = 0;

        JsonParser(String src) {
            this.src = src;
        }

        public static Object parse(String src) {
            return new JsonParser(src).parseValue();
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (Character.isDigit(c) || c == '-' || c == '.' || c == '+') return parseNumber();
            if (c == 't' || c == 'f' || c == 'n') return parseBooleanOrNull();
            throw new RuntimeException("Unexpected character '" + c + "' at position " + pos);
        }

        private Map<String, Object> parseObject() {
            pos++; // skip '{'
            Map<String, Object> map = new HashMap<>();
            while (true) {
                skipWhitespace();
                if (pos >= src.length()) throw new RuntimeException("Unterminated object");
                if (src.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw new RuntimeException("Expected ':' at " + pos);
                }
                pos++; // skip ':'
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                }
            }
        }

        private List<Object> parseArray() {
            pos++; // skip '['
            List<Object> list = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (pos >= src.length()) throw new RuntimeException("Unterminated array");
                if (src.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                Object val = parseValue();
                list.add(val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                }
            }
        }

        private String parseString() {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw new RuntimeException("Expected string start at " + pos);
            }
            pos++; // skip '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                } else if (c == '\\') {
                    pos++;
                    if (pos < src.length()) {
                        sb.append(src.charAt(pos));
                        pos++;
                    }
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new RuntimeException("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            boolean isFloat = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c) || c == '-' || c == '+' || c == 'e' || c == 'E' || c == '.') {
                    if (c == '.' || c == 'e' || c == 'E') {
                        isFloat = true;
                    }
                    pos++;
                } else {
                    break;
                }
            }
            String s = src.substring(start, pos);
            try {
                if (isFloat) return Double.parseDouble(s);
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private Object parseBooleanOrNull() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new RuntimeException("Unknown literal at " + pos);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
