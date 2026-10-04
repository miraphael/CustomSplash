package dev.customsplash.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 静态图片（PNG / JPG）帧源：只有一帧。
 */
public final class ImageFrameSource implements FrameSource {

    private final int width;
    private final int height;
    private final int[] pixels;

    private ImageFrameSource(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static ImageFrameSource load(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("无法解析图片文件（格式不支持？）: " + file);
        }
        BufferedImage argb = Images.toArgb(img);
        int w = argb.getWidth();
        int h = argb.getHeight();
        int[] px = new int[w * h];
        argb.getRGB(0, 0, w, h, px, 0, w);
        return new ImageFrameSource(w, h, px);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int frameCount() {
        return 1;
    }

    @Override
    public int frameDelayMs() {
        return 1000;
    }

    @Override
    public int[] nextFrame() {
        return pixels;
    }

    @Override
    public void close() {
        // 无外部资源
    }
}
