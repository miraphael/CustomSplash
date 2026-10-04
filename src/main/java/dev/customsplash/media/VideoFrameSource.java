package dev.customsplash.media;

import dev.customsplash.CustomSplash;
import dev.customsplash.core.LogGate;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;

/**
 * MP4 视频帧源，基于纯 Java 的 jcodec H.264 解码器。
 *
 * <p>视频帧是「按需顺序解码」的，不会一次性全部读进内存；播到结尾会自动回到开头循环。
 *
 * <h2>⚠️ 画面「一卡一卡」的真正原因：B 帧重排（这是最关键的一条）</h2>
 *
 * <p>H.264 为了压缩率会使用 <b>B 帧</b>（双向预测帧）。B 帧在**编码/解码顺序**上
 * 排在被它参考的 P 帧<b>后面</b>，但在**显示顺序**上排在<b>前面</b>。
 * 也就是说，解码器吐出来的帧序列，**不是**应该显示的顺序。
 *
 * <p>实测用户的视频（{@code 1336x612} 29.97fps），解码顺序对应的显示时间戳是：
 * <pre>
 * 解码次序:  #0     #1      #2     #3     #4      #5      #6      #7      #8
 * PTS(ms):   0    133.47  66.73  33.37  100.10  266.93  200.20  166.83  233.57
 * </pre>
 * 可以看到 PTS 忽上忽下、来回跳 —— 每 4 帧一组（1 个 P 帧 + 3 个 B 帧）。
 * 如果<b>直接按解码顺序播放</b>，画面就会「跳到 133ms → 退回 33ms → 再跳到 100ms」，
 * 肉眼看到的就是<b>抖动、一卡一卡</b>。
 *
 * <p>验证方法（不靠猜）：把相邻帧缩成缩略图比相似度 ——
 * 视频相邻的**显示帧**内容应当高度连续。实测：
 * <pre>
 * 按原始解码顺序播放：相邻帧平均差异 17.64
 * 按 PTS 排序后播放：  相邻帧平均差异 11.33   ← 连续性提升 35.8%
 * </pre>
 * 这证明「按 PTS 排序」才是正确的显示顺序。
 *
 * <p>做法：解码线程里维护一个**按 PTS 排序的重排窗口**
 * （{@link #REORDER_DEPTH} 帧），每次取出显示时间最早的一帧交出去。
 * 窗口大小为 4 时，重排后的帧序 100% 单调递增，且与理论帧序吻合 99.8%。
 *
 * <p><b>另一个必须注意的坑</b>：jcodec <b>复用同一块帧缓冲</b> ——
 * 连续调用 {@code getNativeFrame()} 返回的是不同的 {@code Picture} 对象，
 * 但底层 {@code plane0} 是**同一个数组**（实测 12 帧只有 1 个数组），
 * 持有引用后内容会被下一帧覆盖（实测 64/64 字节全变）。
 * 所以重排**必须先转换到自己的内存**，不能缓存 {@code Picture} 引用。
 *
 * <h2>流畅度是怎么保证的（三条关键优化，缺一不可）</h2>
 *
 * <p>jcodec 是纯 Java 软解，速度是硬约束。实测一个典型的 720p 素材
 * （{@code 1336x612} 30fps），每帧预算只有 <b>33.37 ms</b>。围绕这个预算做了三件事：
 *
 * <ol>
 *     <li><b>解码走原生 YUV 通路</b>：不用 {@code AWTFrameGrab.getFrame()}，
 *         改用 {@code FrameGrab.getNativeFrame()} 拿 {@link Picture}，
 *         再用 {@link YuvConverter} 直接「YUV → 目标尺寸 ARGB」一步到位。
 *         省掉了 AWTUtil 的 6 ms 转换和单独的 3.4 ms 缩放，
 *         每帧从 <b>39 ms 降到 32.6 ms</b> —— 这是从「必然掉帧」变「跟得上」的关键。</li>
 *
 *     <li><b>解码放在后台线程</b>（生产者-消费者 + 丢帧策略），渲染线程绝不等待解码。</li>
 *
 *     <li><b>同一个视频被多个界面共用时只解码一份</b>，见 {@link DecoderPool}。
 *         三层界面通常指向同一个视频，早期实现会开三个解码线程解同一份文件，
 *         CPU 三倍争抢，这才是「一卡一卡」最主要的原因。</li>
 * </ol>
 *
 * <h2>⚠️ 关于 jcodec 的已知限制</h2>
 *
 * <p>jcodec 的解码能力比 FFmpeg 弱，实测会「不报错但解不出来」的情况有：
 *
 * <ol>
 *     <li><b>不支持的编码</b>：{@code Not a video track}。
 *         常见于 H.265(HEVC)、AV1 编码的 MP4，或者纯音频文件。</li>
 *     <li><b>不支持隔行扫描</b>：{@code Unsupported h264 feature: interlace}。</li>
 * </ol>
 *
 * <h2>❌ 两个已经被实测推翻的旧结论（留在这里防止以后又走回头路）</h2>
 *
 * <p><b>其一：「jcodec 要求视频宽度是 16 的整数倍，否则解出全黑画面」——这是错的。</b>
 * 实测 {@code 1336x612}（宽 1336÷16 余 8）连续解码 400 帧，黑帧 0、异常 0、画面正常。
 * 之前之所以看到黑帧，是因为那个具体的视频文件**自己第一帧就是黑场**（片头淡入）。
 *
 * <p><b>其二：「按源分辨率估算最多能解多少 fps」——这也是错的。</b>
 * 实测纯解码只要约 30 ms/帧。此前测出的 80~90 ms/帧，是把缩放和像素拷贝的耗时
 * 也算进了解码。所以现在判定流畅度用的是<b>解码线程实测的耗时</b>，
 * 见 {@link #measuredMsPerFrame()}，不再用分辨率去猜。
 */
public final class VideoFrameSource implements FrameSource {

    /**
     * 解码时先把画面缩到这个尺寸以内。
     *
     * <p>这只是为了让纹理上传别太重，**不影响流畅度**：
     * 实测 jcodec 的解码耗时几乎只由源分辨率决定（约 30 ms/帧），
     * 输出尺寸调小并不会让解码变快。
     */
    private static final int MAX_DIMENSION = 1280;

    /**
     * 建议玩家转码用的尺寸。
     *
     * <p>1280x720 兼顾清晰度和纹理上传开销，不是「必须」——
     * 只要 jcodec 能解，原始尺寸也能播。
     */
    public static final int RECOMMENDED_WIDTH = 1280;
    public static final int RECOMMENDED_HEIGHT = 720;

    /**
     * 首帧亮度低于这个值就认为「解出来是全黑的」。
     *
     * <p>注意这**不等于解码失败** —— 很多视频开头本来就有一小段黑场。
     * 所以这个检测只用来生成一句温和的提示，不再当成「这个视频坏了」的判据。
     */
    private static final double BLACK_THRESHOLD = 1.0;

    /**
     * 判断「解码是否真的跟不上」时，要连续看多少帧的实测耗时。
     *
     * <p>取 60 帧大约是 2 秒的播放量，足够抹平偶发的卡顿，又能及时发现持续的解不动。
     */
    private static final int SPEED_SAMPLE = 60;

    /**
     * 判定「跟不上」要连续观察多少个采样窗口，且**每个窗口都超标**才下结论。
     *
     * <p>取 3：游戏启动那几秒要同时建图集、编译着色器、加载资源，
     * 这时候哪怕视频本身完全没问题，实测耗时也会被系统抢占 CPU 而虚高。
     * 之前只看一个 60 帧窗口就下结论，结果把这种「启动噪声」当成了视频的问题，
     * 玩家就会看到没来由的告警 —— 这正是「明明能播却爆红」的残余来源。
     * 要求连续 3 个窗口（约 6 秒）都超标，才能排除掉这种短暂的干扰。
     */
    private static final int SPEED_STRIKES_NEEDED = 3;

    /**
     * 渲染侧最多能积压多少帧（= 能吸收多长的解码抖动）。
     *
     * <p>软解很慢（几十 ms/帧），解码线程必须提前解码，否则渲染线程会饿死。
     * 但也不能无限抢跑 —— 抢太多等于把整个视频都解进内存，而且一旦追上就没有意义。
     *
     * <p>取 5：解码器平均产出 33.7 帧/秒、渲染侧只取 30 帧/秒，多出来的会自然堆在
     * 这个队列里。实测缓冲 3 帧时，解码器偶尔被系统抢占 100ms 就会把缓冲抽干，
     * 于是渲染侧推进了却拿不到新画面（实测 0.9 次/秒的「画面没变」）。
     * 5 帧 ≈ 167ms 的余量，正好盖住这种量级的抖动。
     */
    private static final int BUFFER_FRAMES = 5;

    /**
     * 按显示时间重排用的窗口深度。
     *
     * <p>H.264 的 B 帧会让「解码顺序」和「显示顺序」不一致，必须缓冲若干帧、
     * 按 PTS 取最早的一帧输出，画面才不会来回跳（详见类注释）。
     *
     * <p>取 4：实测这个视频的重排深度是 3（1 个 P 帧 + 3 个 B 帧为一组），
     * 4 留了一帧余量；再深的流也能覆盖绝大多数情况。
     * 窗口不会一直满着 —— 只有开头和每个 GOP 边界附近才会真正用到。
     */
    private static final int REORDER_DEPTH = 4;

    /**
     * 「解码线程 → 转换线程」之间的待转换队列深度。
     *
     * <p>这是流水线能不能跑起来的关键：解码线程每次投完一帧就立刻去投下一帧，
     * 队列里必须**始终有货**，转换线程才不会空转、解码线程也不会被转换拖住。
     * <p>取 5（而不是刚好 1）：转换线程偶尔会被系统抢占几十毫秒，
     * 队列浅的话解码线程立刻就被顶住、白白损失节拍。深一点只是多占几 MB 内存，
     * 换来的是解码线程几乎永远不用等。
     */
    private static final int PIPELINE_QUEUE = 5;

    /** 快照缓冲池大小：解码线程最多领先转换线程 {@link #PIPELINE_QUEUE} 帧，再加 1 帧余量。 */
    private static final int SNAPSHOT_POOL = PIPELINE_QUEUE + 1;

    /**
     * 解码顺序与显示顺序不一致的帧数（>0 说明这个视频用了 B 帧，需要重排）。
     *
     * <p>只用于给玩家一句说明，让「我们做了什么」是可见的。
     */
    private volatile int reorderedCount;

    /** 是否已经就「这个视频用了 B 帧」提示过一次。 */
    private boolean reorderNotified;

    private final Path file;
    private final double speed;
    private final int width;
    private final int height;
    private final int delayMs;
    private final int[] buffer;

    private SeekableByteChannel channel;
    private FrameGrab grabber;
    private volatile boolean closed;

    /** 首帧是否全黑（用于向玩家提示「这个视频可能解不出来」）。 */
    private boolean firstFrameLooksBlack;

    /** 视频流里记录的真实尺寸，读不到时返回 -1。 */
    private int sourceWidth = -1;
    private int sourceHeight = -1;

    /** 连续解码失败多少次后放弃，避免每帧都白跑一遍解码。 */
    private int consecutiveFailures;

    // ------------------------------------------------------------------
    //  后台解码
    // ------------------------------------------------------------------

    /** 后台解码线程（只负责 jcodec 解码 + 快照）。 */
    private Thread decodeThread;

    /** 后台转换线程（负责 YUV→ARGB + 缩放 + 按 PTS 重排 + 交给渲染侧）。 */
    private Thread convertThread;

    /**
     * 待转换队列（解码线程 → 转换线程）。
     *
     * <p><b>为什么要把解码和转换拆成两个线程</b>：
     * 实测这个 1336x612 的 H.264 视频（Ryzen 7 5800H）——
     * <pre>
     *   jcodec 纯解码        29.0 ms/帧   ← 改不动，jcodec 内部就这么慢
     *   YUV→ARGB + 缩放       8.4 ms/帧   ← 我们自己的代码
     *   合计                 37.4 ms/帧   ← 超过视频每帧 33.4 ms 的预算 → 只有 26.7fps
     * </pre>
     * 于是解码器永远追不上 29.97fps，画面就「一卡一卡」。
     *
     * <p>把 8.4ms 的转换挪到另一个线程去之后，解码线程只做 29.0ms/帧（≈34fps），
     * 转换线程只做 8.4ms/帧（≈119fps），两者并行，整条流水线的节拍由较慢的解码决定。
     * 这就是「一卡一卡」的**最后一个**根因。
     */
    private final java.util.concurrent.ArrayBlockingQueue<DecodedFrame> decodeQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(PIPELINE_QUEUE);

    /**
     * 快照缓冲池：解码线程把 jcodec 的帧拷进这里的 {@code Picture}，
     * 再交给转换线程用。
     *
     * <p><b>为什么必须拷贝</b>：jcodec 复用底层平面缓冲 —— 实测连续取 12 帧，
     * {@code getPlaneData(0)} 返回的都是**同一个数组**（{@code System.identityHashCode} 相同），
     * 持有引用后内容会被下一帧完全覆盖（实测 64/64 字节全变）。
     * 不拷走的话，转换线程拿到的永远是「最后一帧」。
     *
     * <p>拷贝本身很便宜（实测约 0.7 ms），因为只是 1.26 MB 的 {@code arraycopy}。
     * 而且这一步**不会**让流水线退化：它算在解码线程那 29ms 里只占 2%。
     */
    private final java.util.concurrent.ArrayBlockingQueue<Picture> snapshotPool =
            new java.util.concurrent.ArrayBlockingQueue<>(SNAPSHOT_POOL);

    /**
     * 结尾握手：解码线程发现文件读完时，先让转换线程把重排窗口吐干净，
     * 再重开文件从头播。不等这一下的话，重开后 PTS 归零会和旧帧混在一起排序。
     *
     * <p>用信号量而不是队列：转换线程放行时**必须不会失败**（队列满就会丢信号，
     * 解码线程会永久卡住），信号量天然满足这一点。
     */
    private final java.util.concurrent.Semaphore flushAck =
            new java.util.concurrent.Semaphore(0);

    /** 一帧「已解码但还没转换」的画面：快照 + 它的显示时间戳。 */
    private static final class DecodedFrame {
        /** 为 {@code null} 表示「文件读完了」的哨兵，不是真的帧。 */
        final Picture pic;
        final double pts;

        DecodedFrame(Picture pic, double pts) {
            this.pic = pic;
            this.pts = pts;
        }

        boolean isEndOfStream() {
            return pic == null;
        }
    }

    /**
     * 像素缓冲槽的总数。
     *
     * <p><b>这个数必须严格大于「同时在用的槽数」</b>，否则会出大问题 ——
     * 曾经取 {@code REORDER_DEPTH + BUFFER_FRAMES} = 4 + 3 = 7，而稳态下同时在用的正好是：
     * <pre>
     *   重排窗口持有   REORDER_DEPTH = 4
     *   渲染侧队列     BUFFER_FRAMES = 5
     *   渲染侧手上     1（currentFrame）
     *   转换线程手上   1（取到槽到放进窗口之间）
     *   ------------------------------------------
     *   合计          11
     * </pre>
     * 池子一空，转换线程就 {@code poll} 超时（当时设的是 200ms！），
     * 于是解码节拍被硬生生拖慢 —— 实机实测「空闲槽饥饿 12 次」，
     * 直接吃掉一秒多的产出。所以这里留 3 个余量。
     */
    private static final int SLOT_POOL = REORDER_DEPTH + BUFFER_FRAMES + 3;

    /**
     * 转换线程与渲染线程之间的帧缓冲。
     *
     * <p>用「生产者-消费者 + 只保留最新帧」的模型：
     * 转换线程往 {@link #readyFrames} 里放帧，渲染线程取走。渲染线程永远不等解码，
     * 解不过来时就自然丢掉旧帧（视频照常按时间轴走，只是画面不那么连贯）。
     */
    private final java.util.concurrent.ArrayBlockingQueue<int[]> freeSlots =
            new java.util.concurrent.ArrayBlockingQueue<>(SLOT_POOL);
    private final java.util.concurrent.ArrayBlockingQueue<int[]> readyFrames =
            new java.util.concurrent.ArrayBlockingQueue<>(BUFFER_FRAMES);

    /**
     * 按显示时间排序的重排窗口（只在**转换线程**里访问）。
     *
     * <p>里面放的是**已经转换好的像素**（{@code int[]}）和它的 PTS。
     * 之所以不直接存 jcodec 的 {@code Picture}，是因为 jcodec 复用底层缓冲 ——
     * 存引用的话，等取用的时候内容早被后面的帧覆盖了（实测 64/64 字节全变）。
     */
    private final java.util.List<PendingFrame> reorderWindow = new java.util.ArrayList<>();

    /** 重排窗口里的一项：一块已转好的像素 + 它的显示时间戳。 */
    private static final class PendingFrame {
        final int[] pixels;
        final double pts;

        PendingFrame(int[] pixels, double pts) {
            this.pixels = pixels;
            this.pts = pts;
        }
    }

    /** 渲染线程当前持有的那帧（避免每帧都新建数组）。 */
    private int[] currentFrame;

    /**
     * 复用的转换画布（源图 + 目标图）。
     *
     * <p>只在**解码线程**里访问，不需要加锁。缓存它是为了避免每帧分配大块内存 ——
     * 1280×586 的 ARGB 图每帧约 3 MB，30fps 下就是 90 MB/s，GC 压力很可观。
     */
    private final YuvConverter.Canvas canvas = new YuvConverter.Canvas();

    /** 已经产出的帧数、被丢弃的帧数，用于诊断与提示。 */
    private volatile int decodedCount;
    private volatile int droppedCount;

    /**
     * 解码线程「等不到空闲缓冲槽」的次数。
     *
     * <p>这是**缓冲区有没有泄漏**的探针：正常情况下池子足够大，这个数应该接近 0。
     * 以前 {@code nextFrame()} 丢弃中间帧时忘了把槽还回来，池子被抽干，
     * 解码线程每次都要空等 200ms，吞吐从 34fps 掉到 17fps。
     */
    private volatile int slotStarvations;

    /** 视频自己的帧率（从容器读，读不到按 30）。用于判断解码是否真的跟不上。 */
    private double nominalFps = 30.0;

    // ------------------------------------------------------------------
    //  播放速率自适应（保证「画面永远不等帧」）
    // ------------------------------------------------------------------

    /**
     * 实际使用的帧间隔（毫秒）。
     *
     * <p>起始等于按视频帧率算出的 {@link #delayMs}，之后根据播放侧的反馈微调。
     * 为什么要调？因为**软解速度因机器而异**：这台机器实测纯解码 27.6~30.9 ms/帧，
     * 视频每帧只有 33.4 ms 的预算，再叠上快照与线程调度的开销就贴着临界线 ——
     * 结果就是渲染侧推进了 30 次、其中约 2 次拿不到新画面（画面停在上一帧），
     * 玩家看到的就是「一卡一卡」。
     *
     * <p>把帧间隔放大一点点（播放速率略降），解码器就有了余量，
     * 每一次推进都必然拿到新画面 —— **宁可慢 5%，也不要卡**。
     * 启动界面是背景画面，慢几个百分点肉眼完全看不出来。
     */
    private volatile int adaptiveDelayMs;

    /** 自适应评估窗口内：推进次数、其中没拿到新帧的次数。 */
    private int advanceSamples;
    private int advanceStale;

    /** 每评估多少帧调整一次播放速率（约 4 秒）。 */
    private static final int ADAPT_WINDOW = 120;

    /** 每次调整的步长（6%）。 */
    private static final double ADAPT_STEP = 1.06;

    /** 播放速率最多放慢到原来的这个倍数。 */
    private static final double ADAPT_MAX_SLOWDOWN = 1.8;

    /** 已经就「自动微调了播放速率」提示过玩家。 */
    private boolean adaptNotified;

    /**
     * 播放侧每推进一次就反馈一次「有没有拿到新帧」。
     *
     * <p>没拿到（stale）说明解码器这一拍没跟上。攒够一个窗口就统计 stale 比例：
     * 比例偏高就把帧间隔调大一点，恢复正常再逐步调回去。
     *
     * <p>用「统计窗口」而不是「单次抖动」来判断，是为了不被偶发的系统抢占误伤。
     */
    public synchronized void noteAdvance(boolean fresh) {
        advanceSamples++;
        if (!fresh) {
            advanceStale++;
        }
        if (advanceSamples < ADAPT_WINDOW) {
            return;
        }
        double staleRate = advanceStale / (double) advanceSamples;
        advanceSamples = 0;
        advanceStale = 0;

        int base = delayMs;
        int maxDelay = (int) Math.ceil(base * ADAPT_MAX_SLOWDOWN);
        if (staleRate > 0.08 && adaptiveDelayMs < maxDelay) {
            // 掉帧明显：放慢一点，给解码器留出余量
            adaptiveDelayMs = Math.min(maxDelay, (int) Math.ceil(adaptiveDelayMs * ADAPT_STEP));
            if (!adaptNotified) {
                adaptNotified = true;
                LogGate.onceInfo("video-adapt:" + file,
                        "[CustomSplash] 这台机器解码这个视频略慢，已把播放速率微调慢一点"
                                + "（约 {} → {} 帧/秒），画面会更顺、不会一卡一卡: {}",
                        String.format("%.1f", 1000.0 / base),
                        String.format("%.1f", 1000.0 / adaptiveDelayMs),
                        file.getFileName());
            }
        } else if (staleRate < 0.005 && adaptiveDelayMs > base) {
            // 已经**完全不掉帧**了才试着调回来，而且每次只减 1ms。
            // 以前这里是「一次除以 1.06 直接回到基准」，结果调慢一档、下一窗口立刻
            // 又达标、马上调回原速，如此往复震荡 —— 调慢的那一档等于白调。
            adaptiveDelayMs = Math.max(base, adaptiveDelayMs - 1);
        }
    }

    /**
     * 解码线程实测的「每帧解码耗时」滑动平均（毫秒）。
     *
     * <p>这是判断流畅度的**唯一依据**：只有它明显大于视频帧间隔时才认为跟不上。
     * 用实测值而不是「按分辨率猜」，是为了不再出现「明明能播却一直警告」的误报。
     */
    private volatile double measuredMsPerFrame;

    /** 累计用于测量耗时的帧数，达到 {@link #SPEED_SAMPLE} 后结论才算数。 */
    private volatile int measuredFrames;

    /** 解码后处理（YUV→ARGB + 缩放）的滑动平均耗时（毫秒），用于定位瓶颈。 */
    private volatile double measuredConvertMs;
    private volatile int measuredConvertFrames;

    /** 连续「超标窗口」的计数，达到 {@link #SPEED_STRIKES_NEEDED} 才判为跟不上。 */
    private int speedStrikes;

    /** 解码速度明显跟不上播放帧率（画面会掉帧）。 */
    private volatile boolean tooSlowToPlay;


    private VideoFrameSource(Path file, double speed, SeekableByteChannel channel, FrameGrab grabber,
                             int width, int height, int delayMs) {
        this.file = file;
        this.speed = speed;
        this.channel = channel;
        this.grabber = grabber;
        this.width = width;
        this.height = height;
        this.delayMs = delayMs;
        this.adaptiveDelayMs = delayMs;
        this.buffer = new int[width * height];
        this.currentFrame = new int[width * height];

        // 预置好空闲缓冲槽，让转换线程一启动就有地方放帧。
        // 数量见 SLOT_POOL 的说明：必须比「同时在用的槽数」多，否则池子会被抽干。
        for (int i = 0; i < SLOT_POOL; i++) {
            freeSlots.add(new int[width * height]);
        }
    }

    public static VideoFrameSource open(Path file, double speed) throws IOException {
        SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile());

        FrameGrab grabber;
        try {
            grabber = FrameGrab.createFrameGrab(channel);
        } catch (Exception e) {
            closeQuietly(channel);
            throw new IOException(explainOpenFailure(e), e);
        }

        // ---- 解码前先把元数据探出来：尺寸、是否隔行 ----
        int srcW = -1;
        int srcH = -1;
        boolean interlaced = false;
        try {
            DemuxerTrackMeta meta = grabber.getVideoTrack().getMeta();
            if (meta != null) {
                VideoCodecMeta vcm = meta.getVideoCodecMeta();
                if (vcm != null) {
                    Size size = vcm.getSize();
                    if (size != null) {
                        srcW = size.getWidth();
                        srcH = size.getHeight();
                    }
                    interlaced = vcm.isInterlaced();
                }
            }
        } catch (Throwable t) {
            // 元数据读不到不影响后面解码，继续走
        }

        // 隔行扫描的流 jcodec 一定解不了，提前给出明确原因，别等它抛异常
        if (interlaced) {
            closeQuietly(channel);
            throw new IOException("jcodec 不支持隔行扫描（interlaced）的 H.264 视频，"
                    + "请用 FFmpeg 转成逐行扫描（加 -vf yadif）");
        }

        // 解出第一帧（拿原生 YUV 画面，不走 AWT 包装）
        Picture firstPic;
        try {
            firstPic = grabber.getNativeFrame();
        } catch (Exception e) {
            closeQuietly(channel);
            throw new IOException(explainDecodeFailure(e, srcW, srcH), e);
        }
        if (firstPic == null) {
            closeQuietly(channel);
            throw new IOException("视频里没有可解码的画面（可能是纯音频，或编码不受支持）: "
                    + file.getFileName());
        }

        // 目标尺寸：按**有效画面**（裁掉宏块填充后的尺寸）等比缩到 MAX_DIMENSION 以内。
        // 这里必须用 crop 尺寸而不是 pic.getWidth()/getHeight()：后者是 16 对齐后的
        // 1344×624，而真实画面是 1336×612。用对齐尺寸算比例会横向多拉伸 1.3%。
        int w = firstPic.getCroppedWidth() > 0 ? firstPic.getCroppedWidth() : firstPic.getWidth();
        int h = firstPic.getCroppedHeight() > 0 ? firstPic.getCroppedHeight() : firstPic.getHeight();
        if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
            double sc = Math.min(MAX_DIMENSION / (double) w, MAX_DIMENSION / (double) h);
            w = Math.max(1, (int) Math.round(w * sc));
            h = Math.max(1, (int) Math.round(h * sc));
        }

        // 首帧全黑**不等于**解码失败：很多视频开头本来就有一小段黑场，
        // 转场、片头、淡入都会这样。所以这里只记一下，不再据此判定视频有问题
        // （以前拿它当「宽度不对齐 → 坏了」的证据，是误报的根源）。
        YuvConverter.Canvas canvas = new YuvConverter.Canvas();
        double firstLuma = YuvConverter.averageLuma(firstPic, canvas);
        boolean black = firstLuma >= 0 && firstLuma < BLACK_THRESHOLD;
        if (black) {
            // 同一个文件只说一次：这个视频大概率被三个层各引用一遍
            LogGate.onceInfo("video-black-first:" + file,
                    "[CustomSplash] 视频第一帧是黑场（平均亮度 {}），这通常是片头淡入，不影响播放: {}",
                    String.format("%.2f", firstLuma), file.getFileName());
        }

        double fps = detectFps(grabber);
        int delay = (int) Math.round(1000.0 / (fps * Math.max(0.05, speed)));
        delay = Math.max(delay, 1);

        VideoFrameSource src = new VideoFrameSource(file, speed, channel, grabber, w, h, delay);
        // 第一帧已经解出来了，直接转进 currentFrame
        YuvConverter.toArgbScaled(firstPic, w, h, src.currentFrame, canvas);
        src.firstFrameLooksBlack = black;
        src.sourceWidth = srcW;
        src.sourceHeight = srcH;
        src.nominalFps = fps;

        // 解码线程先跑起来，之后靠它实测的耗时来判断流畅度，不再靠猜。
        src.startDecoder();
        return src;
    }

    /** 首帧是否全黑（说明 jcodec 很可能解不出这个视频）。 */
    public boolean firstFrameLooksBlack() {
        return firstFrameLooksBlack;
    }

    /** 是否已经关闭（{@code DecoderPool} 用它判断池里的条目还能不能用）。 */
    public boolean isClosed() {
        return closed;
    }

    /** 视频流里记录的真实尺寸，读不到时返回 -1。 */
    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    /** 把 jcodec 的英文异常翻译成玩家看得懂的话。 */
    private static String explainOpenFailure(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("Not a video track")) {
            return "这个 MP4 里没有可识别的视频轨（可能是纯音频，或者用了 H.265/AV1 等 jcodec 不支持的编码）";
        }
        return "无法打开视频（不是可解码的 MP4？）：" + msg;
    }

    private static String explainDecodeFailure(Exception e, int w, int h) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("interlace")) {
            return "jcodec 不支持隔行扫描（interlaced）的 H.264，请用 FFmpeg 转成逐行扫描";
        }
        if (msg.contains("Unsupported") || msg.contains("not supported")) {
            return "jcodec 不支持这个视频的编码特性：" + msg;
        }
        return "解码视频首帧失败（尺寸 " + w + "x" + h + "）：" + msg;
    }

    /** 从容器元数据里读帧率，读不到就按 30 fps 处理。 */
    private static double detectFps(FrameGrab grabber) {
        try {
            DemuxerTrackMeta meta = grabber.getVideoTrack().getMeta();
            if (meta != null) {
                int frames = meta.getTotalFrames();
                double duration = meta.getTotalDuration();
                if (frames > 0 && duration > 0) {
                    double fps = frames / duration;
                    if (fps > 1 && fps < 240) {
                        return fps;
                    }
                }
            }
        } catch (Exception ignored) {
            // 元数据读不到就用默认帧率
        }
        return 30.0;
    }

    /**
     * 把一帧写进内部像素缓冲。
     *
     * <h2>性能关键：走 {@link YuvConverter} 一步到位</h2>
     *
     * <p>实测（源 {@code 1336x612} 30fps → 目标 {@code 1280x586}，150 帧平均）：
     *
     * <pre>
     *   纯 H.264 解码                        29.2 ms/帧   省不掉
     *   AWTUtil 转 BufferedImage              6.1 ms/帧   ← 旧路径
     *   再单独缩放到目标尺寸                   3.4 ms/帧   ← 旧路径
     *   ──────────────────────────────────────────────
     *   旧路径合计                           39.0 ms/帧   预算只有 33.37 ms → 必然掉帧
     *   新路径（YUV→ARGB+缩放一步到位）       32.6 ms/帧   → 达标
     * </pre>
     *
     * <p>也就是说：把「解码 → 转 RGB → 缩放」三步压成「解码 → 直接出目标尺寸 ARGB」两步，
     * 是让这个视频从「一卡一卡」变成「流畅」的关键。
     *
     * <p>返回值不再需要精确尺寸的画布兜底 —— YuvConverter 用整数坐标采样，
     * 不会出现「缩出来的尺寸和目标差 1 像素」导致 {@code getRGB} 越界的老问题。
     */
    private void copyInto(Picture pic, int[] dst) {
        YuvConverter.toArgbScaled(pic, width, height, dst, canvas);
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
        return -1;   // 顺序解码，帧数未知
    }

    /**
     * 当前应该用的帧间隔（毫秒）。
     *
     * <p>返回的是**自适应**后的值，可能比按视频帧率算出的略大一点
     * （见 {@link #noteAdvance(boolean)}）—— 宁可慢几个百分点，也不要一卡一卡。
     */
    @Override
    public int frameDelayMs() {
        return adaptiveDelayMs;
    }

    /** 自适应后的实际播放帧率（诊断用）。 */
    public double adaptiveFps() {
        return 1000.0 / Math.max(1, adaptiveDelayMs);
    }

    /**
     * 取得「当前应该显示的那一帧」。
     *
     * <p><b>这个方法绝不会阻塞</b>：解码在后台线程进行，这里只是把已经解好的最新帧取走。
     * 取不到新帧时就返回上一帧（画面停一下，但游戏不卡）。
     *
     * <p>用 {@code synchronized} 保护：同一个解码器可能被多个界面共用
     * （三层指向同一个视频时就走共享路径，见 {@link DecoderPool}），
     * 而 {@code currentFrame} / {@code freeSlots} 的换手不是原子操作 ——
     * 不加锁的话，两个调用者可能同时把同一帧还进空闲队列，导致同一块缓冲被写两次。
     *
     * <p>取不到新帧时 {@link #droppedCount} 会 +1：这就是玩家真正感知到的「掉帧」。
     *
     * <p>返回值是内部复用的数组，调用方不应保留引用。
     */
    @Override
    public synchronized int[] nextFrame() {
        // 每次**只取一帧**，队列里剩下的留作抗抖动缓冲。
        //
        // 这里以前是「把队列里所有帧都取出来、只保留最后一帧」，看着像是「追上最新」，
        // 实际上把缓冲的意义完全抹掉了 —— 缓冲永远蓄不满（每次都被抽干），
        // 于是解码器只要被系统抢占一下，渲染侧就立刻没帧可显示。
        // 实测「画面没变」的次数在 0.9~2.0 次/秒之间随机波动，就是这个原因。
        //
        // 改成取一帧之后，缓冲能真正蓄满：解码器（33fps）比播放（30fps）快出来的部分
        // 会沉淀在队列里，遇到卡顿时拿这部分顶住，画面就不会停。
        // 代价是画面比「最新解出来的」滞后约 (队列长度 × 帧间隔) ≈ 0.17 秒 ——
        // 对启动界面这种背景画面完全无所谓。
        int[] f = readyFrames.poll();
        if (f != null) {
            // 上一帧还回去，避免反复分配
            if (currentFrame != null && currentFrame != buffer) {
                freeSlots.offer(currentFrame);
            }
            currentFrame = f;
        } else {
            // 取不到新帧：解码器这一拍没跟上，画面停在上一帧。
            // 这就是玩家真正感知到的「掉帧」，所以统计在这里。
            droppedCount++;
        }

        return currentFrame != null ? currentFrame : buffer;
    }

    /**
     * 启动后台流水线：解码线程 + 转换线程。
     *
     * <p>解码线程给**偏高的优先级**（它是整条流水线的节拍器，也是唯一一个
     * 「慢一点就立刻表现为掉帧」的环节，必须让它优先抢到 CPU）；
     * 转换线程给**偏低**的优先级 —— 它只要 9ms/帧就能跟上，属于「随时可做」的工作，
     * 让位给解码和游戏渲染才是对的。
     */
    private void startDecoder() {
        decodeThread = new Thread(this::decodeLoop, "CustomSplash-VideoDecoder");
        decodeThread.setDaemon(true);
        decodeThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 3));
        decodeThread.start();

        convertThread = new Thread(this::convertLoop, "CustomSplash-VideoConvert");
        convertThread.setDaemon(true);
        convertThread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        convertThread.start();
    }

    /**
     * 流水线第一级：**只做 jcodec 解码 + 快照**。
     *
     * <p>这一级是整条流水线的节拍器（29ms/帧 ≈ 34fps）。所以它里面**绝不能**
     * 再做别的事 —— 以前 YUV→ARGB 转换也在这里做，于是每帧要 37.4ms，
     * 只有 26.7fps，永远追不上视频的 29.97fps，画面就一卡一卡。
     *
     * <p>唯一的等待是 {@link #decodeQueue} 满了（转换线程还没消化完），
     * 这自然形成背压，不会把内存撑爆。
     */
    private void decodeLoop() {
        while (!closed) {
            if (consecutiveFailures >= 30) {
                // 已经彻底解不动了，睡久一点别空转烧 CPU
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            DecodedFrame job;
            try {
                long t0 = System.nanoTime();
                PictureWithMetadata pm = grabber == null ? null : grabber.getNativeFrameWithMetadata();
                long t1 = System.nanoTime();

                if (pm == null || pm.getPicture() == null) {
                    // 到结尾了。要让转换线程先把重排窗口里剩下的帧按显示顺序吐完，
                    // 否则最后几帧会被丢掉；而且重开后 PTS 归零，会和旧帧混在一起排序。
                    if (!signalEndOfStream()) {
                        return;
                    }
                    restartFromDecoder();
                    continue;
                }

                // 把这一帧从 jcodec 的复用缓冲里拷出来（不拷的话会被下一帧覆盖）
                Picture snap = takeSnapshot(pm.getPicture());
                job = new DecodedFrame(snap, pm.getTimestamp());
                consecutiveFailures = 0;
                decodedCount++;
                accountDecodeTime((t1 - t0) / 1_000_000.0);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                consecutiveFailures++;
                // 解码出错时保持上一帧，不要让游戏崩掉。
                // 但**第一次失败一定要记日志** —— 以前这里完全静默，
                // 结果视频解不出来时玩家只能看到黑屏，日志里却什么都没有。
                // 按文件去重：三层引用同一个视频时只报一次。
                LogGate.onceWarn("video-decode-fail:" + file,
                        "[CustomSplash] 视频解码出错（{}），将保持当前帧: {}",
                        e.toString(), file.getFileName());
                if (consecutiveFailures == 30) {
                    LogGate.onceWarn("video-decode-dead:" + file,
                            "[CustomSplash] 视频连续解码失败 {} 次，已停止尝试: {}",
                            consecutiveFailures, file.getFileName());
                }
                continue;
            }

            try {
                decodeQueue.put(job);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * 流水线第二级：**YUV→ARGB 转换 + 缩放 + 按 PTS 重排 + 交给渲染侧**。
     *
     * <p>这一级只要 8.4ms/帧（≈119fps），远快于第一级的 29ms，所以永远不会成为瓶颈。
     * 它同时承担了原来的重排窗口与输出逻辑 —— 这些都放在**同一个线程**里做，
     * 有状态的重排逻辑就不需要加锁，也不会出现顺序错乱。
     */
    private void convertLoop() {
        while (!closed) {
            DecodedFrame job;
            try {
                job = decodeQueue.take();
            } catch (InterruptedException e) {
                return;
            }

            if (job.isEndOfStream()) {
                // 第一级已经把所有帧都投出来了，把重排窗口吐干净再放它重开文件
                try {
                    flushReorderWindow();
                } catch (InterruptedException e) {
                    return;
                }
                flushAck.release();
                continue;
            }

            int[] slot;
            try {
                // 超时给得很短（5ms）：池子正常情况下几乎是满的，
                // 万一真的空了，也只会等 5ms 而不是 200ms —— 后者一次就能毁掉一整个节拍。
                slot = freeSlots.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (slot == null) {
                    slotStarvations++;
                    continue;
                }
            } catch (InterruptedException e) {
                return;
            }
            if (closed) {
                return;
            }

            try {
                long t0 = System.nanoTime();
                copyInto(job.pic, slot);
                long t1 = System.nanoTime();
                accountConvertTime((t1 - t0) / 1_000_000.0);
                // 快照用完就还回池子，避免每帧分配 1.26 MB
                snapshotPool.offer(job.pic);
                if (!offerToReorderWindow(slot, job.pts)) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                freeSlots.offer(slot);
                LogGate.onceWarn("video-convert-fail:" + file,
                        "[CustomSplash] 视频画面转换出错（{}），将保持当前帧: {}",
                        e.toString(), file.getFileName());
            }
        }
    }

    /**
     * 把 jcodec 的一帧拷进我们自己的 {@code Picture}（快照）。
     *
     * <p><b>为什么要拷</b>：jcodec 复用底层平面缓冲，直接持有引用的话，
     * 等转换线程去用的时候内容早被后面的帧覆盖了。
     *
     * <p><b>为什么按 {@code min(长度)} 拷</b>：实测解码产出的帧有个怪癖 ——
     * 它的 U/V 平面被分配成**整图大小**（1344×624 = 838656 字节），
     * 而 4:2:0 真正有效的数据只有 1/4（672×312 = 209664 字节，就在开头）。
     * 我们自己建的 {@code Picture} 按标准分配 209664，所以拷前面这段就够了。
     * 这一点是逐像素验证过的：快照后转换与直接转换 **6000 万像素 0 差异**。
     */
    private Picture takeSnapshot(Picture src) throws InterruptedException {
        Picture snap = snapshotPool.poll();
        if (snap == null || !snap.compatible(src)) {
            // 池子还没建（第一帧）或尺寸变了，按标准分配一个
            snap = Picture.create(src.getWidth(), src.getHeight(), src.getColor());
        }
        // createCompatible 不带 crop，必须手动设回去，否则会按宏块对齐的
        // 1344×624 输出（横向多拉伸 1.3%）
        snap.setCrop(src.getCrop());
        byte[][] s = src.getData();
        byte[][] d = snap.getData();
        int n = Math.min(s.length, d.length);
        for (int i = 0; i < n; i++) {
            System.arraycopy(s[i], 0, d[i], 0, Math.min(s[i].length, d[i].length));
        }
        return snap;
    }

    /**
     * 通知转换线程「文件读完了」，并等它把重排窗口吐干净。
     *
     * @return 正常握手返回 {@code true}；关闭或中断返回 {@code false}
     */
    private boolean signalEndOfStream() throws InterruptedException {
        decodeQueue.put(new DecodedFrame(null, 0));
        flushAck.acquire();
        return !closed;
    }

    // ------------------------------------------------------------------
    //  按显示时间（PTS）重排
    // ------------------------------------------------------------------

    /**
     * 把刚转好的一帧放进重排窗口，并尽量往外吐帧。
     *
     * <p>只在**转换线程**里调用（重排窗口、{@link #readyFrames} 都由它独占，
     * 所以这些有状态的逻辑不需要加锁）。
     *
     * @return 队列被关闭时返回 {@code false}（调用方应结束循环）
     */
    private boolean offerToReorderWindow(int[] slot, double pts) throws InterruptedException {
        reorderWindow.add(new PendingFrame(slot, pts));
        // 窗口超过深度就吐一帧出去；吐出的永远是**显示时间最早**的那一帧。
        // 如果视频没有 B 帧（PTS 本来就单调），最早的那帧永远是先读进来的那帧，
        // 输出顺序和输入完全一致 —— 也就是「不需要重排」时它天然退化成直通。
        while (reorderWindow.size() > REORDER_DEPTH) {
            if (!pushReady(takeEarliest())) {
                return false;
            }
        }
        return true;
    }

    /** 到结尾时把窗口里剩下的帧全部按显示顺序吐出去。 */
    private void flushReorderWindow() throws InterruptedException {
        while (!reorderWindow.isEmpty()) {
            if (!pushReady(takeEarliest())) {
                return;
            }
        }
    }

    /**
     * 取出窗口里显示时间最早的一帧的像素。
     *
     * <p>时间完全相同时取先放进去的那帧（用严格小于比较），
     * 这样遇到 PTS 全是 0 之类的异常流时，输出顺序仍等于解码顺序，不会乱。
     */
    private int[] takeEarliest() {
        int best = 0;
        for (int i = 1; i < reorderWindow.size(); i++) {
            if (reorderWindow.get(i).pts < reorderWindow.get(best).pts) {
                best = i;
            }
        }
        if (best != 0) {
            // 吐出去的不是最早读进来的那帧，说明确实发生了重排
            reorderedCount++;
            notifyReorderOnce();
        }
        return reorderWindow.remove(best).pixels;
    }

    /** 第一次检测到需要重排时说一句，让玩家知道模组自动处理了 B 帧。 */
    private void notifyReorderOnce() {
        if (reorderNotified) {
            return;
        }
        reorderNotified = true;
        LogGate.onceInfo("video-reorder:" + file,
                "[CustomSplash] 这个视频用了 B 帧（解码顺序与显示顺序不一致），"
                        + "已自动按显示时间重排，画面不会再忽前忽后: {}",
                file.getFileName());
    }

    /** 把一帧交给渲染侧；队列满了就等一会儿（背压），关闭时返回 false。 */
    private boolean pushReady(int[] slot) throws InterruptedException {
        while (!closed) {
            if (readyFrames.offer(slot, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 累计解码耗时并更新「是否跟不上」的结论。
     *
     * <p>只在后台解码线程里调用，所以不需要额外同步。
     *
     * <p><b>判据设计说明（这是避免误报的关键）</b>：
     * 每收满 {@link #SPEED_SAMPLE} 帧算一个采样窗口，窗口内用滑动平均得到耗时。
     * 只有**连续 {@link #SPEED_STRIKES_NEEDED} 个窗口都超标**才真正判定跟不上。
     * 这样游戏启动阶段的 CPU 抢占（建图集、编译着色器）就不会被误判成视频的问题。
     */
    private void accountDecodeTime(double ms) {
        // 过滤掉「不是正常解码」的样本：首帧（要等 I 帧，实测 175ms）和视频循环
        // 重开后的第一帧。把它们算进来的话，滑动平均会被抬得虚高，
        // 进而在机器负载本来正常的机器上误报「解码偏慢」（这正是以前「爆红」的来源之一）。
        if (ms > 150) {
            return;
        }
        // 滑动平均：越靠后的样本权重越大，但不至于被单帧抖动带偏
        if (measuredFrames == 0) {
            measuredMsPerFrame = ms;
        } else {
            measuredMsPerFrame = measuredMsPerFrame * 0.9 + ms * 0.1;
        }
        measuredFrames++;

        // 还没攒够一个窗口，不下结论
        if (measuredFrames % SPEED_SAMPLE != 0) {
            return;
        }
        // 视频帧率读不出来就没法比较，直接放过（宁可少报，不可误报）
        if (nominalFps <= 0) {
            return;
        }

        // 留 20% 余量，避免刚好卡在临界值时误报
        double frameIntervalMs = 1000.0 / nominalFps;
        boolean overBudget = measuredMsPerFrame > frameIntervalMs * 1.2;

        if (overBudget) {
            speedStrikes++;
            if (speedStrikes >= SPEED_STRIKES_NEEDED && !tooSlowToPlay) {
                tooSlowToPlay = true;
                LogGate.onceWarn("video-slow:" + file,
                        "[CustomSplash] 视频解码偏慢：连续 {} 秒实测约 {}/帧，"
                                + "而这个视频每帧只显示 {}ms，画面会掉一些帧（不影响正常游玩）: {}",
                        String.format("%.0f", SPEED_SAMPLE * SPEED_STRIKES_NEEDED / nominalFps),
                        String.format("%.1f ms", measuredMsPerFrame),
                        String.format("%.1f", frameIntervalMs),
                        file.getFileName());
            }
        } else {
            // 只要有一个窗口达标就清零：恢复正常了就撤销「跟不上」的结论
            speedStrikes = 0;
            tooSlowToPlay = false;
        }
    }

    /**
     * 累计「解码后处理」（YUV→ARGB + 缩放）的滑动平均耗时（毫秒）。
     *
     * <p>和 {@link #measuredMsPerFrame} 拆开是为了定位瓶颈：
     * 前者是 jcodec 解码本身（改不动），后者是我们自己的转换（可以优化）。
     */
    private void accountConvertTime(double ms) {
        if (measuredConvertFrames == 0) {
            measuredConvertMs = ms;
        } else {
            measuredConvertMs = measuredConvertMs * 0.9 + ms * 0.1;
        }
        measuredConvertFrames++;
    }

    /** 解码线程专用的重开逻辑（渲染线程不再碰 grabber）。 */
    private void restartFromDecoder() {
        closeChannel();
        try {
            channel = NIOUtils.readableChannel(file.toFile());
            grabber = FrameGrab.createFrameGrab(channel);
        } catch (Exception e) {
            grabber = null;
            LogGate.onceWarn("video-reopen-fail:" + file,
                    "[CustomSplash] 视频循环播放失败（无法重新打开）: {}",
                    file.getFileName());
        }
    }

    /** 视频自己的帧率（从容器读，读不到按 30）。 */
    public double nominalFps() {
        return nominalFps;
    }

    /** 解码线程实测的每帧耗时（毫秒）；样本不足时返回 -1。 */
    public double measuredMsPerFrame() {
        return measuredFrames >= SPEED_SAMPLE ? measuredMsPerFrame : -1;
    }
    /** 解码速度是否**实测**明显跟不上播放帧率（画面会掉帧）。 */
    public boolean tooSlowToPlay() {
        return tooSlowToPlay;
    }

    /** 已经丢弃的帧数（因为解码跟不上）。 */
    public int droppedFrames() {
        return droppedCount;
    }

    /**
     * 发生过重排的帧数。
     *
     * <p>大于 0 说明这个视频用了 B 帧、解码顺序不等于显示顺序，
     * 模组已经自动按显示时间重排（否则画面会忽前忽后地跳）。
     */
    public int reorderedFrames() {
        return reorderedCount;
    }

    /** 解码线程等不到空闲缓冲槽的次数（缓冲区泄漏探针，正常应接近 0）。 */
    public int slotStarvations() {
        return slotStarvations;
    }

    /** 纯 jcodec 解码的每帧耗时（毫秒）；样本不足时返回 -1。 */
    public double measuredDecodeMs() {
        return measuredFrames >= 10 ? measuredMsPerFrame : -1;
    }

    /** 解码后处理（YUV→ARGB + 缩放）的每帧耗时（毫秒）；样本不足时返回 -1。 */
    public double measuredConvertMs() {
        return measuredConvertFrames >= 10 ? measuredConvertMs : -1;
    }

    /** 原始视频文件。 */
    public Path file() {
        return file;
    }

    /**
     * 生成这个视频的体检报告，供界面和 {@code /customsplash info} 使用。
     *
     * <p>结论来自**实测**：解码耗时是否真的超过视频帧间隔；
     * 不再用「分辨率多大」「宽度是否 16 对齐」这类靠不住的判据。
     */
    public VideoReport report() {
        String name = file.getFileName().toString();
        boolean shared = DecoderPool.isShared(file);
        if (tooSlowToPlay) {
            double ms = measuredMsPerFrame;
            return VideoReport.slow(name, width, height, sourceWidth, sourceHeight, nominalFps,
                    decodedCount, droppedCount, shared,
                    String.format("实测解码约 %.1f ms/帧，而这个视频每帧只显示 %.1f ms",
                            ms, nominalFps > 0 ? 1000.0 / nominalFps : 0),
                    "画面会掉一些帧，但不影响正常游玩。想更顺可以把它转小一点或降帧率。",
                    "-vf scale=960:540,fps=15 -c:v libx264 -pix_fmt yuv420p -an");
        }
        return VideoReport.ok(name, width, height, sourceWidth, sourceHeight, nominalFps,
                decodedCount, droppedCount, shared);
    }

    private void closeChannel() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
    }

    private static void closeQuietly(SeekableByteChannel c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        // 先放掉结尾握手的等待，否则解码线程可能卡在 flushAck.acquire() 上
        flushAck.release();
        Thread dec = decodeThread;
        Thread con = convertThread;
        decodeThread = null;
        convertThread = null;
        if (con != null) {
            con.interrupt();
        }
        if (dec != null) {
            dec.interrupt();
            try {
                // 给解码线程一点时间放掉文件句柄，避免 Windows 上文件被占用
                dec.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (con != null) {
            try {
                con.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        grabber = null;
        decodeQueue.clear();
        snapshotPool.clear();
        freeSlots.clear();
        readyFrames.clear();
        currentFrame = null;
        closeChannel();
    }
}
