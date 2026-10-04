package dev.customsplash.media;

import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rect;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

/**
 * 把 jcodec 解出的画面转成 ARGB 像素，**同时完成缩放**。
 *
 * <h2>背景：jcodec 的转换 API 有两个大坑</h2>
 *
 * <h3>坑一：{@code toBufferedImage(Picture, BufferedImage)} 不做 YUV→RGB</h3>
 *
 * <p>这个重载的实现只有一句搬运：
 * <pre>
 * byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
 * byte[] plane = pic.getPlaneData(0);
 * for (int i = 0; i &lt; data.length; i++) {
 *     data[i] = (byte) (plane[i] + 128);   // 只把 plane0 当字节流，每字节 +128
 * }
 * </pre>
 * <b>它没有任何色彩空间换算</b>，只适用于「颜色已经是 RGB/BGR 的 Picture」。
 * 拿解码器产出的 YUV420 帧去喂它，直接
 * {@code ArrayIndexOutOfBoundsException: Index 838656 out of bounds for length 838656}
 * —— 838656 = 1344×624，正是 YUV420 里 plane0（1 字节/像素）的大小，
 * 而 BufferedImage 侧期望 {@code w*h*3} 字节。
 *
 * <h3>坑二：就连「同一张图」的搬运也要按跨距来</h3>
 *
 * <p>即便中间 Picture 已经是 BGR，2 参重载仍是「按目标图像的字节数从 plane0 <b>连续</b> 拷贝」，
 * <b>完全不理会源的行跨距</b>。H.264 宏块要 16 对齐，源宽度 1344 而画面宽度 1336，
 * 每行会错位 8 像素、越往下越偏 —— 实测 94.77% 的像素不一致，
 * 画面看起来「结构还在但整体错位」。
 * 官方那个 private 的 {@code toBufferedImageCropped} 才是按跨距搬的，
 * 所以这里照它的算法自己搬（已验证逐像素 0% 差异）。
 *
 * <h2>最终做法：复刻官方单参版本的完整流程（四步，一步都不能少）</h2>
 *
 * <ol>
 *     <li>{@code Picture.createCropped(pw, ph, ColorSpace.BGR, crop)} 建目标图；</li>
 *     <li>{@code ColorUtil.getTransform(源色彩, ColorSpace.RGB).transform(pic, 目标)} 做 YUV→RGB；</li>
 *     <li><b>{@code RgbToBgr.transform(目标, 目标)}</b> —— 把 RGB 字节就地转成 BGR 顺序。
 *         漏掉这步的症状很隐蔽：画面结构正常，但 <b>R 和 B 通道对调</b>
 *         （(0,0) 处官方 {@code 02 03 0A}、自己实现 {@code 0A 03 02}），
 *         平均色差只有个位数、粗看「差不多」，细看颜色偏；</li>
 *     <li>按跨距把字节搬进 {@code TYPE_3BYTE_BGR} 的 BufferedImage。</li>
 * </ol>
 *
 * <p>把前两步显式拆出来的好处是可以<b>复用预分配的图对象与字节缓冲</b>，
 * 省掉每帧 {@code new BufferedImage} / {@code createCropped} 的分配，
 * 而换算本身仍走 jcodec 官方实现，**颜色保证一致**。
 *
 * <h2>另一条走过的弯路（一并记下）</h2>
 *
 * <p>还试过自己解析 YUV420 平面手写换算，结果画面颜色全错
 * （深蓝森林变橙色，逐像素比对 100% 超标）。原因是
 * {@code Picture.getPlaneData(1)}/{(2)} 对解码产出的 YUV420 帧，
 * {@code length} 实测都是整图大小（838656），与 420 应有的 1/4 不符。
 * <b>教训：性能优化不能牺牲正确性，逐像素比对是必须的一步。</b>
 */
public final class YuvConverter {

    private YuvConverter() {
    }

    /**
     * 把一帧画面转成 ARGB 并缩放到 {@code dw × dh}，写进 {@code out}。
     *
     * @param pic      解码得到的一帧（通常是 YUV420）
     * @param dw       目标宽度
     * @param dh       目标高度
     * @param out      输出像素数组，长度必须 ≥ {@code dw * dh}
     * @param reusable 可复用的中间画布（由调用方持有，避免每帧分配）
     * @return 实际写入的像素数（即 {@code dw * dh}）
     */
    public static int toArgbScaled(Picture pic, int dw, int dh, int[] out, Canvas reusable) {
        java.awt.image.BufferedImage src = toImage(pic, reusable);

        // toImage 返回的已经是「裁掉宏块填充后」的有效画面，尺寸即 crop 尺寸
        int cw = src.getWidth();
        int ch = src.getHeight();

        // 一步缩放到位：从源图直接采样到目标尺寸（不先 fitDown 再重采样，省掉一整趟）
        java.awt.image.BufferedImage dst = reusable.scaled(dw, dh);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, dw, dh, 0, 0, cw, ch, null);
        g.dispose();

        dst.getRGB(0, 0, dw, dh, out, 0, dw);
        return dw * dh;
    }

    /**
     * 按 jcodec 官方路径把一帧转成 {@code BufferedImage}，但**复用画布**。
     *
     * <p>复用的是那张 BGR 中间 Picture（尺寸随宏块对齐，比画面本身略大）。
     * 转换本身每次都要做，因为输入帧在变。
     */
    private static java.awt.image.BufferedImage toImage(Picture pic, Canvas reusable) {
        int pw = pic.getWidth();
        int ph = pic.getHeight();
        Rect crop = pic.getCrop();

        // 缺省输出尺寸：有 crop 就按 crop，没有就用整图
        int iw = pic.getCroppedWidth() > 0 ? pic.getCroppedWidth() : pw;
        int ih = pic.getCroppedHeight() > 0 ? pic.getCroppedHeight() : ph;

        // 严格复刻 AWTUtil.toBufferedImage(Picture) 单参版本的完整流程（四步，一步都不能少）：
        //   1. createCropped(w, h, BGR, crop) 建目标图
        //   2. getTransform(源色彩, RGB).transform(源, 目标)  → YUV→RGB
        //   3. RgbToBgr.transform(目标, 目标)                 → RGB 字节就地转成 BGR 顺序
        //   4. 按 toBufferedImageCropped 的跨距规则搬进 TYPE_3BYTE_BGR 的 BufferedImage
        // 目标尺寸必须用宏块对齐后的 pw/ph（crop 只影响 getCroppedWidth/Height）。
        Picture dst = reusable.picture(pw, ph, crop);
        Transform tr = reusable.transformFor(pic.getColor());
        tr.transform(pic, dst);
        reusable.rgbToBgr().transform(dst, dst);

        // 第 4 步不要用 AWTUtil.toBufferedImage(Picture, BufferedImage) 这个重载：
        // 它是「按目标图的字节数从 plane0 连续拷贝」，完全不理会源的行跨距。
        // 当源宽度（宏块对齐，1344）大于画面宽度（1336）时，每行会错位 8 像素，
        // 越往后越偏（实测 94% 的像素不一致）。官方那个 private 的
        // toBufferedImageCropped 才是对的，所以这里照它的算法自己搬。
        // 已验证：与官方单参输出逐像素 0% 差异。
        byte[] plane = dst.getPlaneData(0);
        byte[] data = reusable.bytes(iw * ih * 3);
        int dstStride = iw * 3;
        int srcStride = pw * 3;
        int srcRow = 0;
        int dstRow = 0;
        for (int y = 0; y < ih; y++) {
            int s = srcRow;
            for (int t = dstRow, end = dstRow + dstStride; t < end; t += 3, s += 3) {
                data[t] = (byte) (plane[s] + 128);
                data[t + 1] = (byte) (plane[s + 1] + 128);
                data[t + 2] = (byte) (plane[s + 2] + 128);
            }
            srcRow += srcStride;
            dstRow += dstStride;
        }

        java.awt.image.BufferedImage img = reusable.image(iw, ih);
        java.awt.image.DataBuffer imgBuf = img.getRaster().getDataBuffer();
        System.arraycopy(data, 0, ((java.awt.image.DataBufferByte) imgBuf).getData(), 0, data.length);
        return img;
    }

    /**
     * 采样计算画面平均亮度，用于判断首帧是不是黑场。
     *
     * <p>转换可能失败时返回 -1，调用方据此跳过判断（宁可不说，也别误报）。
     */
    public static double averageLuma(Picture pic, Canvas reusable) {
        try {
            java.awt.image.BufferedImage img = toImage(pic, reusable);
            int cw = img.getWidth();
            int ch = img.getHeight();
            if (cw <= 0 || ch <= 0) {
                return -1;
            }
            int stepX = Math.max(1, cw / 64);
            int stepY = Math.max(1, ch / 64);
            long sum = 0;
            long count = 0;
            for (int y = 0; y < ch; y += stepY) {
                for (int x = 0; x < cw; x += stepX) {
                    int p = img.getRGB(x, y);
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    sum += (r * 299 + g * 587 + b * 114) / 1000;
                    count++;
                }
            }
            return count == 0 ? -1 : sum / (double) count;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 可复用的画布集合。
     *
     * <p>只有**解码线程**会用到，所以不需要加锁。
     * 之所以要缓存，是因为每帧都分配几张图会给 GC 造成不小压力 ——
     * 1280×586 的 ARGB 图每帧约 3 MB，30fps 下就是 90 MB/s。
     */
    public static final class Canvas {
        /** jcodec 侧中间图（BGR 色彩空间）。 */
        private Picture bgr;
        /** 从中间图搬出来的 BufferedImage（拿它的 getRGB 用）。 */
        private java.awt.image.BufferedImage img;
        /** 最终目标尺寸的图。 */
        private java.awt.image.BufferedImage scaled;
        /** 上次用的转换器（色彩空间不变就不用重新查）。 */
        private ColorSpace lastColor;
        private Transform lastTransform;
        /** RGB→BGR 就地转换器（官方路径里固定要做的一步）。 */
        private Transform rgbToBgr;
        /** 搬运用的字节缓冲（每帧都要写满一遍，复用可省分配）。 */
        private byte[] data;

        byte[] bytes(int n) {
            if (data == null || data.length != n) {
                data = new byte[n];
            }
            return data;
        }

        Picture picture(int w, int h, Rect crop) {
            if (bgr == null || bgr.getWidth() != w || bgr.getHeight() != h) {
                bgr = Picture.createCropped(w, h, ColorSpace.BGR, crop);
            }
            return bgr;
        }

        Transform transformFor(ColorSpace color) {
            if (lastTransform == null || lastColor != color) {
                lastTransform = ColorUtil.getTransform(color, ColorSpace.RGB);
                lastColor = color;
            }
            return lastTransform;
        }

        Transform rgbToBgr() {
            if (rgbToBgr == null) {
                rgbToBgr = new org.jcodec.scale.RgbToBgr();
            }
            return rgbToBgr;
        }
        java.awt.image.BufferedImage image(int w, int h) {
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
            }
            return img;
        }

        java.awt.image.BufferedImage scaled(int w, int h) {
            if (scaled == null || scaled.getWidth() != w || scaled.getHeight() != h) {
                scaled = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            }
            return scaled;
        }

        void release() {
            bgr = null;
            img = null;
            scaled = null;
            data = null;
            lastTransform = null;
            lastColor = null;
        }
    }
}
