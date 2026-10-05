package dev.customsplash.client;

import dev.customsplash.CustomSplash;
import dev.customsplash.media.FrameSource;
import dev.customsplash.media.VideoFrameSource;
import dev.customsplash.media.VideoReport;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 把一帧序列（图片 / GIF / 视频）渲染成一张全屏背景。
 *
 * <p>动画推进用的是墙上时钟，而不是游戏 tick —— 这样连「游戏还没完全启动」的
 * 早期加载界面里也能正常播放动画。
 *
 * <h2>关于流畅度（重要）</h2>
 *
 * <p>视频解码一律在 {@code VideoFrameSource} 的后台线程里做，
 * 这里的 {@link FrameSource#nextFrame()} **不会阻塞**，只取已经解好的最新帧。
 * 再加上下面 {@link #writePixels} 的直接内存写入，渲染线程每帧的开销控制在毫秒级。
 */
public class MediaPlayer implements AutoCloseable {

    private final Identifier textureId;
    private final String label;
    private final FrameSource source;
    private final int width;
    private final int height;

    /**
     * 纹理是**第一次渲染时才创建**的，不在构造器里。
     *
     * <p>原因：这个类的构造器会被 {@code SplashMediaManager} 的**后台预加载线程**调用
     * （把「读文件 + 解首帧」这段耗时挪到界面出现之前），而
     * {@code DynamicTexture} 会真的去建 OpenGL 纹理 —— 必须在渲染线程上做。
     * 所以构造器只做纯 CPU 的准备工作，GL 相关的部分留给 {@link #ensureTexture()}。
     */
    private DynamicTexture texture;
    private NativeImage image;

    /**
     * 构造时取到的首帧，等纹理建好后再写进去。
     *
     * <p>视频源在 {@code open()} 里已经把第一帧同步解好了（{@code currentFrame}），
     * 所以这里拿到的就是真实画面，不是空缓冲。
     */
    private int[] pendingPixels;

    /**
     * 直接指向 {@link NativeImage} 底层堆外内存的视图。
     *
     * <p>用 {@code getPointer()} 拿到的就是这块内存的地址。之所以要这么绕，
     * 是因为 {@code setPixel(x, y, argb)} 每写一个像素都要算偏移、做边界检查、
     * 处理 alpha 位序 —— 1280×586 就是 75 万次调用，实测要 9~32 ms。
     * 直接写内存可以把这个开销压到 1 ms 以内。
     *
     * <p>用 {@code IntBuffer} 而不是 {@code ByteBuffer}，是因为 26.2 的
     * {@code NativeImage.setPixelABGR} 内部就是
     * {@code MemoryUtil.memPutInt(pixels + (x + y*width) * 4, pixel)} ——
     * 像素是**按 int 存的**，存的是 ABGR（AABBGGRR）。
     * 而 {@code setPixel(x, y, argb)} 会先做一次 {@code ARGB.toABGR(argb)} 再落盘，
     * 所以内存里的格式和 1.21.11 完全一致，下面的换算不用改。
     */
    private java.nio.IntBuffer pixelBuffer;

    /** ARGB→ABGR 转换的临时缓冲，避免每次上传都新建数组。 */
    private int[] scratch = new int[0];

    private long lastFrameAt;
    private boolean closed;
    private int renderedFrames;

    /**
     * 上一次 {@code nextFrame()} 返回的那块数组（诊断用）。
     *
     * <p>{@code nextFrame()} 在「没有新帧可显示」时会**原样返回上一帧的同一块数组**，
     * 所以用引用比较就能区分「真的换了新画面」和「推进了但内容没变（画面卡住）」。
     * 前者才是玩家看到的画面更新率 —— 这是判断流畅度最直接的指标。
     */
    private int[] lastPixels;

    /** 真正换上新画面的次数。 */
    private int freshAdvances;
    /** 推进了但没有新帧可用（画面停在上一帧）的次数。 */
    private int staleAdvances;
    /** {@code render()} 被调用的次数（= 这个界面被渲染的游戏帧数）。 */
    private int renderCalls;

    /**
     * 纯 CPU 的准备工作：取首帧、记住尺寸。
     *
     * <p><b>这个方法不碰 OpenGL</b>，所以可以被后台预加载线程调用 ——
     * 这正是「界面出现时不再先闪一下原版背景」的关键：
     * 读文件、探测 MP4、解第一帧这些耗时操作全部提前到游戏启动阶段完成，
     * 等界面真的出现时只剩一次纹理创建 + 上传（毫秒级）。
     *
     * <p>纹理相关的部分见 {@link #ensureTexture()}。
     */
    public MediaPlayer(String key, FrameSource source) {
        this.source = source;
        this.label = key;
        this.width = source.width();
        this.height = source.height();
        this.textureId = CustomSplash.id("dynamic/" + key);

        // 视频源在 open() 里已经同步解好首帧，这里不会等解码。
        this.pendingPixels = source.nextFrame();
        this.lastPixels = pendingPixels;
        this.lastFrameAt = System.currentTimeMillis();
    }

    /**
     * 建纹理并把首帧传上去。**只能在渲染线程上调用。**
     *
     * <p>放在第一次 {@code render()} 里做，而不是构造器里 —— 构造器可能跑在
     * 后台预加载线程上（见上面），那里没有 GL 上下文。
     */
    private void ensureTexture() {
        if (texture != null) {
            return;
        }
        texture = new DynamicTexture(() -> "customsplash/" + label, width, height, false);
        image = texture.getPixels();
        pixelBuffer = org.lwjgl.system.MemoryUtil.memIntBuffer(image.getPointer(),
                width * height);
        writePixels(pendingPixels != null ? pendingPixels : new int[width * height]);
        texture.upload();
        pendingPixels = null;
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    public Identifier textureId() {
        return textureId;
    }

    /** 这一层的名字（title / loading / boot / previewN），只用于日志。 */
    public String label() {
        return label;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public FrameSource source() {
        return source;
    }

    /**
     * 这块背景实际推进并上传了多少帧。
     *
     * <p>和 {@code VideoReport.decoded()} 不是一个东西：那个是**解码线程**解出多少帧，
     * 这个是**渲染层**真正显示了多少帧。两个数差得越多，说明丢帧越严重。
     */
    public int renderedFrames() {
        return renderedFrames;
    }

    /** 真正换上新画面的次数（玩家看到的画面更新次数）。 */
    public int freshAdvances() {
        return freshAdvances;
    }

    /** 推进了但没有新帧可用的次数（画面停在上一帧）。 */
    public int staleAdvances() {
        return staleAdvances;
    }

    /** 这个界面被渲染的游戏帧数。 */
    public int renderCalls() {
        return renderCalls;
    }

    /**
     * 这个媒体有没有「看起来不对」的地方，用于在界面上提醒玩家。
     *
     * <p>现在只在**实测**解码跟不上时才提醒（见 {@link VideoFrameSource#report()}）。
     * 以前这里还会因为「宽度不是 16 的整数倍」「分辨率偏高」就报警，
     * 那套判据经实测是错的，已经删掉 —— 见 {@code VideoFrameSource} 的类注释。
     *
     * @return 需要提醒时返回一句人话，正常时返回 {@code null}
     */
    public String warning() {
        VideoReport report = report();
        if (report == null || report.isPlayable() && report.level() == VideoReport.Level.OK) {
            return null;
        }
        StringBuilder sb = new StringBuilder(report.headline());
        if (report.detail() != null) {
            sb.append("。").append(report.detail());
        }
        if (report.advice() != null) {
            sb.append(" ").append(report.advice());
        }
        return sb.toString();
    }

    /** 视频的体检报告；不是视频时返回 {@code null}。 */
    public VideoReport report() {
        if (source instanceof VideoFrameSource v) {
            return v.report();
        }
        if (source instanceof dev.customsplash.media.DecoderPool.SharedVideo sv) {
            return sv.report();
        }
        return null;
    }

    /**
     * 把 ARGB 像素批量写进 NativeImage 的底层内存。
     *
     * <p>两处优化：
     * <ol>
     *     <li>直接写内存，跳过 {@code setColorArgb} 的逐像素偏移计算与边界检查；</li>
     *     <li>一次 {@code put(int[])} 整块拷贝，而不是逐像素 put。</li>
     * </ol>
     *
     * <p>格式换算：我们的像素是 ARGB（AARRGGBB），而 NativeImage 存的是
     * ABGR（AABBGGRR），所以要把 R 和 B 对调。
     * 26.2 里这一点没变：{@code NativeImage.setPixel(x, y, argb)} 内部就是
     * {@code setPixelABGR(x, y, ARGB.toABGR(argb))}，而
     * {@code setPixelABGR} 直接 {@code memPutInt} 到 {@code (x + y*width) * 4}。
     */
    private void writePixels(int[] pixels) {
        int n = pixels.length;
        if (scratch.length < n) {
            scratch = new int[n];
        }
        for (int i = 0; i < n; i++) {
            int argb = pixels[i];
            // AARRGGBB → AABBGGRR
            scratch[i] = (argb & 0xFF00FF00)
                    | ((argb & 0x00FF0000) >>> 16)
                    | ((argb & 0x000000FF) << 16);
        }
        pixelBuffer.clear();
        pixelBuffer.put(scratch, 0, n);
    }

    /**
     * 按时间推进动画帧。
     *
     * <p>推进时刻用**累加**而不是「记下当前时间」：
     * 后者每次都会把多等的那一点时间算进下一次，误差不断累积，
     * 结果就是视频越播越慢（实测 30fps 的片子只跑到 22fps）。
     * 累加式让长期平均速率精确等于视频帧率。
     */
    private void advanceIfNeeded() {
        if (source.frameCount() == 1) {
            return;
        }
        long now = System.currentTimeMillis();
        int delay = Math.max(10, source.frameDelayMs());
        if (now - lastFrameAt < delay) {
            return;
        }

        long next = lastFrameAt + delay;
        // 落后太多说明中间被卡过（比如刚切回界面），直接对齐到当前时间，
        // 免得接下来一段时间疯狂追帧
        if (now - next > delay * 5L) {
            next = now;
        }
        lastFrameAt = next;

        // 注意：nextFrame() 绝不阻塞（解码在后台线程），这里只是取最新可用帧
        int[] pixels = source.nextFrame();
        boolean fresh = pixels != lastPixels;
        if (fresh) {
            freshAdvances++;
            lastPixels = pixels;
        } else {
            staleAdvances++;
        }
        // 把「这一次有没有拿到新帧」反馈给解码器：
        // 如果经常拿不到，说明解码器跟不上，它会自动把播放速率调慢一点，
        // 让画面永远有新帧可显示（宁可慢几%，也不要一卡一卡）。
        noteAdvanceToSource(fresh);
        writePixels(pixels);
        texture.upload();
        renderedFrames++;
    }

    /** 把推进结果反馈给底层的视频源（图片 / GIF 不需要反馈）。 */
    private void noteAdvanceToSource(boolean fresh) {
        if (source instanceof VideoFrameSource v) {
            v.noteAdvance(fresh);
        } else if (source instanceof dev.customsplash.media.DecoderPool.SharedVideo sv) {
            sv.videoSource().noteAdvance(fresh);
        }
    }

    /**
     * 把背景画到当前界面。
     *
     * <p>26.2 的绘制上下文从 {@code DrawContext} 换成了
     * {@link GuiGraphicsExtractor}，方法名也变了：
     * <ul>
     *     <li>{@code getScaledWindowWidth()} → {@code guiWidth()}</li>
     *     <li>{@code drawTexturedQuad(id, x1, y1, x2, y2, u0, u1, v0, v1)}
     *         → {@code blit(id, x1, y1, x2, y2, u0, u1, v0, v1)}</li>
     * </ul>
     * {@code blit} 的 9 参重载内部直接调 {@code innerBlit} 并把几何参数原样透传
     * （x0..x1、y0..y1），所以参数顺序和以前的 {@code drawTexturedQuad} 一模一样，
     * 这里不用改算法。它会用 {@code RenderPipelines.GUI_TEXTURED} 和纯白
     * （{@code -1}）绘制，正好是我们想要的。
     *
     * @return 是否真的画了内容（没内容时调用方不应该取消原版渲染）
     */
    public boolean render(GuiGraphicsExtractor context, String fit, float dim) {
        return render(context, fit, dim, 1.0f);
    }

    /**
     * @param alpha 整块画面的不透明度（0~1）。小于 1 时画面是半透明的，
     *              下面的内容能透出来 —— 早期启动屏淡出的那一下要用到：
     *              原版的红底是「1 秒内透明度 255→0」淡出的，我们跟着一起淡，
     *              淡完正好露出主菜单，不会突然跳一下。
     */
    public boolean render(GuiGraphicsExtractor context, String fit, float dim, float alpha) {
        if (closed) {
            return false;
        }
        // 纹理可能还没建（第一次渲染），在这里补上 —— 必须在渲染线程。
        ensureTexture();
        renderCalls++;
        advanceIfNeeded();

        int screenW = context.guiWidth();
        int screenH = context.guiHeight();

        float[] r = computeRect(fit, screenW, screenH);

        int a = Math.round(Mth.clamp(alpha, 0f, 1f) * 255f);

        // 先铺一层黑底。
        //
        // 不只是为了 contain 模式补黑边 —— 更要紧的是**防止漏出原版界面**：
        // 背景层和前景层之间还有别的绘制步骤，铺满的底色能保证我们的画面
        // 是一整块不透明区域，不会让下面那一帧原版背景透出来。
        context.fill(0, 0, screenW, screenH, a << 24);

        // 用带颜色（含 alpha）的 blit，而不是 9 参那个不透明版本：
        // 后者写死了不透明，没法做上面说的淡出。
        // 参数里的 u/v 是纹理像素坐标，srcWidth/srcHeight 是源区域大小，
        // 这里取整张纹理，所以 uv 范围正好是 0~1。
        int x1 = Math.round(r[0]);
        int y1 = Math.round(r[1]);
        context.blit(RenderPipelines.GUI_TEXTURED, textureId,
                x1, y1, 0f, 0f,
                Math.round(r[2]) - x1, Math.round(r[3]) - y1,
                width, height, width, height,
                (a << 24) | 0x00FFFFFF);

        if (dim > 0.001f) {
            int da = Math.min(255, Math.round(dim * 255.0f)) * a / 255;
            context.fill(0, 0, screenW, screenH, da << 24);
        }
        return true;
    }

    /**
     * 计算绘制矩形与纹理坐标。
     *
     * @return {@code [x1, y1, x2, y2, u0, u1, v0, v1]}
     */
    private float[] computeRect(String fit, int screenW, int screenH) {
        float sw = screenW;
        float sh = screenH;
        float iw = width;
        float ih = height;

        switch (fit == null ? "cover" : fit.toLowerCase()) {
            case "stretch" -> {
                return new float[]{0, 0, sw, sh, 0f, 1f, 0f, 1f};
            }
            case "contain" -> {
                float scale = Math.min(sw / iw, sh / ih);
                float w = iw * scale;
                float h = ih * scale;
                float x = (sw - w) / 2f;
                float y = (sh - h) / 2f;
                return new float[]{x, y, x + w, y + h, 0f, 1f, 0f, 1f};
            }
            default -> {   // cover
                float scale = Math.max(sw / iw, sh / ih);
                float w = iw * scale;
                float h = ih * scale;
                float x = (sw - w) / 2f;
                float y = (sh - h) / 2f;
                return new float[]{x, y, x + w, y + h, 0f, 1f, 0f, 1f};
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 纹理可能从来没建过（这层一次都没显示就被关掉了），要判空。
        if (texture != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(textureId);
            } catch (Exception ignored) {
            }
            try {
                texture.close();
            } catch (Exception ignored) {
            }
            texture = null;
            image = null;
            pixelBuffer = null;
        }
        try {
            source.close();
        } catch (Exception ignored) {
        }
    }
}
