package dev.customsplash.media;

import dev.customsplash.CustomSplash;
import dev.customsplash.core.LogGate;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 解码器共享池：**同一个视频文件只解码一份**，被多个界面共用。
 *
 * <h2>为什么必须共享</h2>
 *
 * <p>模组有三块界面（主菜单 / 世界加载 / 早期启动）。玩家的配置通常三层指向同一个视频，
 * 因为「我想让整个游戏都用这一个视频」是最自然的用法。
 *
 * <p>早期实现里，每层各自调用一次 {@code MediaLoader.load()}，于是同一个文件被
 * <b>创建了三份独立的 {@link VideoFrameSource}</b> —— 三个后台线程同时解同一份视频。
 * 实测（{@code 1336x612} H.264，Ryzen 7 5800H）：
 *
 * <pre>
 *   单路解码   32 ms/帧   视频每帧预算 33 ms    ← 已经贴着临界线
 *   三路并发   三份 32 ms 抢同一批核心 → 每路都被拖慢 → 三层全部一卡一卡
 * </pre>
 *
 * <p>这就是「视频一卡一卡」最主要的原因：不是解码器不够快，
 * 是<b>同一份工作被做了三遍</b>。
 *
 * <h2>怎么共享</h2>
 *
 * <p>用一个「解码器 + 引用计数」的池子。持有者（{@link SharedVideo}）拿到的是一层
 * 独立的播放时间轴，但底层像素尽量复用同一份解码结果。
 *
 * <p>注意：这里共享的是<b>解码结果</b>，不是播放进度。三个界面同时可见的可能性极低
 * （启动屏→主菜单→加载世界是先后出现的），所以按「后到的跟随主时间轴」处理，
 * 既省 CPU 又不会出现三层画面不一致的观感问题。
 */
public final class DecoderPool {

    private DecoderPool() {
    }

    /** 池子：文件路径 → 共享条目。 */
    private static final Map<Path, Entry> POOL = new HashMap<>();

    /** 每个文件的共享条目。 */
    private static final class Entry {
        final VideoFrameSource source;
        final AtomicInteger refs = new AtomicInteger(1);

        Entry(VideoFrameSource source) {
            this.source = source;
        }
    }

    /**
     * 取得（或建立）某个视频文件的共享解码器。
     *
     * <p>返回的对象带引用计数，用完必须 {@link SharedVideo#close()}，
     * 最后一个持有者释放时才会真正关掉底层的解码线程。
     *
     * @param file  视频文件
     * @param speed 播放速度（以**第一次**打开时的为准，后续持有者沿用）
     * @throws Exception 打不开时抛出
     */
    public static SharedVideo acquire(Path file, double speed) throws Exception {
        Path key = file.toAbsolutePath().normalize();
        synchronized (POOL) {
            Entry e = POOL.get(key);
            if (e != null && !e.source.isClosed()) {
                e.refs.incrementAndGet();
                LogGate.onceInfo("video-shared:" + key,
                        "[CustomSplash] 同一个视频被多个界面共用，已共享同一个解码器"
                                + "（避免重复解码拖慢帧率）: {}", file.getFileName());
                return new SharedVideo(key, e, false);
            }
            // 没有可用的旧条目，新开一个
            VideoFrameSource src = VideoFrameSource.open(file, speed);
            Entry fresh = new Entry(src);
            POOL.put(key, fresh);
            return new SharedVideo(key, fresh, true);
        }
    }

    /** 释放一次引用；计数归零时关掉底层解码器并从池里移除。 */
    private static void release(Path key, Entry e) {
        boolean lastOne;
        synchronized (POOL) {
            int left = e.refs.decrementAndGet();
            lastOne = left <= 0;
            if (lastOne) {
                POOL.remove(key);
            }
        }
        if (lastOne) {
            try {
                e.source.close();
            } catch (Exception ex) {
                CustomSplash.LOGGER.warn("[CustomSplash] 关闭共享解码器失败: {}", ex.toString());
            }
        }
    }

    /** 当前池子里有几个不同的视频在被解码（诊断用）。 */
    public static int activeCount() {
        synchronized (POOL) {
            return POOL.size();
        }
    }

    /** 某个文件当前是不是被多个界面共用着（引用数 > 1）。 */
    public static boolean isShared(Path file) {
        Path key = file.toAbsolutePath().normalize();
        synchronized (POOL) {
            Entry e = POOL.get(key);
            return e != null && e.refs.get() > 1;
        }
    }

    /** 某个文件当前有几个界面在引用。 */
    public static int refCount(Path file) {
        Path key = file.toAbsolutePath().normalize();
        synchronized (POOL) {
            Entry e = POOL.get(key);
            return e == null ? 0 : e.refs.get();
        }
    }

    /** 强制清空（{@code reload} 时用，防止旧解码器残留）。 */
    public static void reset() {
        synchronized (POOL) {
            for (Entry e : POOL.values()) {
                try {
                    e.source.close();
                } catch (Exception ignored) {
                }
            }
            POOL.clear();
        }
    }

    /**
     * 一个「共享视频」的句柄。
     *
     * <p>对外表现得像一个普通的 {@link FrameSource}，但底层像素来自共享解码器。
     */
    public static final class SharedVideo implements FrameSource {

        private final Path key;
        private final Entry entry;
        private final VideoFrameSource source;
        private boolean closed;

        private SharedVideo(Path key, Entry entry, boolean isOwner) {
            this.key = key;
            this.entry = entry;
            this.source = entry.source;
        }

        private VideoFrameSource raw() {
            return source;
        }

        @Override
        public int width() {
            return source.width();
        }

        @Override
        public int height() {
            return source.height();
        }

        @Override
        public int frameCount() {
            return source.frameCount();
        }

        @Override
        public int frameDelayMs() {
            return source.frameDelayMs();
        }

        @Override
        public int[] nextFrame() {
            return source.nextFrame();
        }

        /** 共享视频的体检报告。 */
        public VideoReport report() {
            return source.report();
        }

        public VideoFrameSource videoSource() {
            return source;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            release(key, entry);
        }
    }
}
