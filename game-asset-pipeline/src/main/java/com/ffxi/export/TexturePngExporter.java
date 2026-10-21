package com.ffxi.export;

import com.ffxi.model.TextureResource;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class TexturePngExporter {
    private TexturePngExporter() {
    }

    /**
     * Decodes a TextureResource from raw DAT bytes and writes a PNG file.
     *
     * @param data      full DAT bytes
     * @param section   parsed TextureResource
     * @param outputFile destination PNG path (parent dirs created automatically)
     * @return true if written successfully, false if decode returned null or I/O error
     */
    public static boolean writePng(byte[] data, TextureResource section, Path outputFile) {
        byte[] rgba = TextureDecoder.decode(data, section);
        if (rgba == null || outputFile == null) {
            return false;
        }

        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            BufferedImage image = new BufferedImage(section.width(), section.height(), BufferedImage.TYPE_INT_ARGB);
            int index = 0;
            for (int y = 0; y < section.height(); y++) {
                for (int x = 0; x < section.width(); x++) {
                    int red = rgba[index] & 0xFF;
                    int green = rgba[index + 1] & 0xFF;
                    int blue = rgba[index + 2] & 0xFF;
                    int alpha = rgba[index + 3] & 0xFF;
                    image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                    index += 4;
                }
            }

            ImageIO.write(image, "png", outputFile.toFile());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
