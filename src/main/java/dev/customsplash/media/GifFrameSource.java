package dev.customsplash.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * GIF 动图帧源。GIF 由 JDK 自带的 ImageIO 解码，不需要任何额外依赖。
 *
 * <p>加载时会把所有帧一次性读进内存。GIF 一般体积小、分辨率低，
 * 这样处理最简单也最流畅；如果帧数非常多，建议先用工具压缩一下。
 */
public final class GifFrameSource implements FrameSource {

    /** 单帧像素上限保护：超过这个数量的 GIF 会被等比缩小。 */
    private static final int MAX_DIMENSION = 1920;

    private final int width;
    private final int height;
    private final List<int[]> frames;
    private final int delayMs;
    private int cursor;

    private GifFrameSource(int width, int height, List<int[]> frames, int delayMs) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.delayMs = delayMs;
    }

    public static GifFrameSource load(Path file) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        List<int[]> frames = new ArrayList<>();
        int width = 0;
        int height = 0;
        int totalDelay = 0;

        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(in, false, false);
            int count = reader.getNumImages(true);
            for (int i = 0; i < count; i++) {
                BufferedImage img = Images.toArgb(reader.read(i));
                img = Images.fitDown(img, MAX_DIMENSION, MAX_DIMENSION);
                if (width == 0) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
                BufferedImage sized = img;
                if (img.getWidth() != width || img.getHeight() != height) {
                    // 极少数 GIF 每帧尺寸不同，统一到第一帧的尺寸
                    sized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    sized.createGraphics().drawImage(img, 0, 0, null);
                }
                int[] px = new int[width * height];
                sized.getRGB(0, 0, width, height, px, 0, width);
                frames.add(px);
                totalDelay += readDelay(reader.getImageMetadata(i));
            }
        } finally {
            reader.dispose();
        }

        if (frames.isEmpty()) {
            throw new IOException("GIF 没有可用的帧: " + file);
        }
        int delay = Math.max(20, totalDelay / frames.size());
        return new GifFrameSource(width, height, frames, delay);
    }

    /** 从 GIF 元数据里读帧延时（单位 1/100 秒）。 */
    private static int readDelay(IIOMetadata metadata) {
        try {
            String format = metadata.getNativeMetadataFormatName();
            Node root = metadata.getAsTree(format);
            Node node = findNode(root, "GraphicControlExtension");
            if (node != null) {
                Node attr = node.getAttributes().getNamedItem("delayTime");
                if (attr != null) {
                    int hundredths = Integer.parseInt(attr.getNodeValue().trim());
                    if (hundredths > 0) {
                        return hundredths * 10;
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不到就用默认值
        }
        return 100;
    }

    private static Node findNode(Node node, String name) {
        if (node == null) {
            return null;
        }
        if (name.equalsIgnoreCase(node.getNodeName())) {
            return node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node found = findNode(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
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
        return frames.size();
    }

    @Override
    public int frameDelayMs() {
        return delayMs;
    }

    @Override
    public int[] nextFrame() {
        int[] frame = frames.get(cursor);
        cursor = (cursor + 1) % frames.size();
        return frame;
    }

    @Override
    public void close() {
        frames.clear();
    }
}
