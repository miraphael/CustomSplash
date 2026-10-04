package dev.customsplash.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * 轻量 MP4 探测：只扫文件里的 box 结构，**不做任何解码**。
 *
 * <p>目的是在选择文件时就能提前发现「jcodec 放不出来」的视频。
 * 能提前查出来的、**确实会导致放不出来**的情况只有两类：
 * <ul>
 *     <li>视频编码不是 H.264（例如 H.265/HEVC、AV1、VP9）→ jcodec 直接报
 *         {@code Not a video track}；</li>
 *     <li>文件里根本没有视频轨（纯音频）。</li>
 * </ul>
 *
 * <h2>⚠️ 注意：宽度对齐**不是**问题（曾误判过）</h2>
 * 本项目早期曾以为「jcodec 要求宽度是 16 的整数倍，否则解出全黑」，
 * 并据此在读尺寸时报警。<b>这个结论已被实测推翻</b>：
 * {@code 1336x612}（宽 1336÷16 余 8）连续解 400 帧，0 黑帧、0 异常，画面正常。
 * 所以这里读尺寸**只用于显示信息，不再用来判定兼容性**。
 *
 * <p>这里从 MP4 的 {@code moov/trak} 里读信息：
 * {@code tkhd} 给尺寸，{@code stsd} 给编码四字符码。
 * 只读文件头尾的少量字节，代价可以忽略。
 *
 * <p><b>为什么不能只扫开头</b>：MP4 有两种常见布局 ——
 * 「faststart」把 {@code moov} 放在文件开头，而**手机/QQ 传出来的视频通常把
 * {@code moov} 放在文件末尾**。只扫开头会对后者完全失效，所以这里两种都要覆盖。
 */
public final class Mp4Probe {

    /** 从文件开头最多扫这么多字节。 */
    private static final int HEAD_SCAN = 2 * 1024 * 1024;

    /** 从文件末尾最多回扫这么多字节。 */
    private static final int TAIL_SCAN = 8 * 1024 * 1024;

    private Mp4Probe() {
    }

    /**
     * 读出来的视频信息。
     *
     * @param width     宽度
     * @param height    高度
     * @param codecTag  编码四字符码（如 {@code avc1} / {@code hvc1} / {@code av01}），读不到为 null
     */
    public record Info(int width, int height, String codecTag) {

        /** 这个编码 jcodec 能不能解（H.264 系可以）。 */
        public boolean isH264() {
            if (codecTag == null) {
                return true;   // 读不出来不妄下结论
            }
            return switch (codecTag) {
                case "avc1", "avc3", "h264", "H264", "x264" -> true;
                default -> false;
            };
        }

        /** 编码的友好名称。 */
        public String codecLabel() {
            if (codecTag == null) {
                return "未知";
            }
            return switch (codecTag) {
                case "avc1", "avc3", "h264", "H264", "x264" -> "H.264 (可播放)";
                case "hvc1", "hev1" -> "H.265 / HEVC (不支持)";
                case "av01" -> "AV1 (不支持)";
                case "vp09" -> "VP9 (不支持)";
                case "mp4v" -> "MPEG-4 旧编码 (不支持)";
                default -> codecTag;
            };
        }
    }

    /**
     * 读出 MP4 的视频信息。
     *
     * <h2>为什么编码信息要这么绕</h2>
     * 尺寸（{@code tkhd}）可以用宽松的字节搜索捞，因为它自带数值校验；
     * 但**编码（{@code stsd}）不行** —— 实测对压缩数据逐字节搜 {@code stsd}
     * 会大量误命中，读出来的「编码」两次运行都能不一样，全是垃圾。
     *
     * <p>而手机/QQ 视频的 {@code moov} 又在文件**末尾**（本项目的两个样例视频都是），
     * 尾部起点不在 box 边界上，没法直接严格遍历。
     *
     * <p>所以这里的做法是：<b>先反推出 {@code moov} 的起点，再从那个点严格遍历</b>。
     * {@code moov} 起点的推法：box 的 size 字段在 box 开头，
     * 所以「末尾数据里出现 'moov' 的位置往前 4 字节」就是它的 size —— 二者相减即起点。
     *
     * @return 读不出来时返回 {@code null}（调用方不应据此报错）
     */
    public static Info readInfo(Path file) {
        // 扫描结果放在一个「一次调用一个」的上下文里，不用静态字段 ——
        // 界面线程和日志线程都可能来读，静态字段会串数据。
        Scan scan = new Scan();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();

            // 1) 先扫开头（faststart 布局，moov 在文件最前面）
            try {
                findMoovInfo(raf, 0, Math.min(length, HEAD_SCAN), 0, scan);
                if (scan.size != null && scan.codec != null) {
                    return new Info(scan.size[0], scan.size[1], scan.codec);
                }
            } catch (Throwable ignored) {
                // 扫不动就试尾部
            }

            // 2) 尾部：先定位 moov 起点，再严格遍历拿编码
            if (length > HEAD_SCAN) {
                long tailStart = Math.max(0, length - TAIL_SCAN);
                try {
                    long moovStart = locateMoovStart(raf, tailStart, length);
                    if (moovStart >= 0) {
                        findMoovInfo(raf, moovStart, length, 0, scan);
                    }
                } catch (Throwable ignored) {
                }
                // 3) 尺寸兜底：moov 起点没推出来时，用宽松搜索至少把尺寸捞到
                if (scan.size == null) {
                    try {
                        findTkhdLoose(raf, tailStart, length, scan);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return scan.size == null ? null : new Info(scan.size[0], scan.size[1], scan.codec);
    }

    /** 一次扫描的累加结果。 */
    private static final class Scan {
        int[] size;
        String codec;
    }

    /**
     * 在尾部数据里反推 {@code moov} box 的起点。
     *
     * <p>方法：找到 {@code "moov"} 标记后，读它前面 4 字节的 size 字段，相减得到起点。
     * 再用「起点 + size ≒ 文件长度」做校验，滤掉压缩数据里的假命中。
     *
     * @return 起点偏移；推不出来返回 -1
     */
    private static long locateMoovStart(RandomAccessFile raf, long start, long end) throws IOException {
        int span = (int) Math.min(end - start, TAIL_SCAN);
        if (span < 12) {
            return -1;
        }
        byte[] buf = new byte[span];
        raf.seek(start);
        raf.readFully(buf);

        for (int i = 4; i + 4 <= span; i++) {
            if (buf[i] == 'm' && buf[i + 1] == 'o' && buf[i + 2] == 'o' && buf[i + 3] == 'v') {
                long size = ((long) (buf[i - 4] & 0xFF) << 24)
                        | ((long) (buf[i - 3] & 0xFF) << 16)
                        | ((long) (buf[i - 2] & 0xFF) << 8)
                        | (buf[i - 1] & 0xFF);
                long pos = start + i - 4;
                // 校验：moov 必须一直延伸到文件结尾附近（moov 通常就是最后一个 box）
                if (size >= 16 && pos >= 0 && pos + size <= end && pos + size >= end - 4096) {
                    return pos;
                }
            }
        }
        return -1;
    }

    /**
     * 只读尺寸（旧接口，保留给需要纯尺寸的地方）。
     *
     * @return {@code [宽, 高]}；读不出来时返回 {@code null}
     */
    public static int[] readSize(Path file) {
        Info info = readInfo(file);
        return info == null ? null : new int[]{info.width(), info.height()};
    }

    /**
     * 一个 trak 里是不是视频轨。
     *
     * <p>判断依据是 {@code mdia > hdlr} 里的 handler type：{@code vide} 表示视频，
     * {@code soun} 表示音频。这个标记在 hdlr 正文的第 8 字节开始（4 字节前导 + 4 字节 pre_defined）。
     */
    private static final String VIDEO_HANDLER = "vide";

    /**
     * 在尾部数据里「宽松」地找 {@code tkhd}。
     *
     * <p>尾部扫描时起点很可能落在一个 box 的中间，严格解析会失败，
     * 所以这里改成直接逐字节搜索 4 字节的 {@code "tkhd"} 标记，
     * 找到后按结构往后读尺寸，并用数值合理性做校验。
     *
     * <p><b>注意：这里只找 {@code tkhd}，不找 {@code stsd}。</b>
     * 实测过 —— 对压缩数据做逐字节搜索时，{@code stsd} 会被大量误命中
     * （两次运行甚至能命中不同的假位置），读出来的「编码」全是垃圾。
     * 编码信息只能靠严格 box 遍历拿，见 {@link #findMoovInfo}。
     * 好在 {@code tkhd} 有数值合理性校验（宽高必须 1~16384），假命中会被过滤掉。
     */
    private static boolean findTkhdLoose(RandomAccessFile raf, long start, long end, Scan scan)
            throws IOException {
        // 一次性把整段尾部读进内存再逐字节搜。
        // 之前用 64KB 滑动窗口 + 逐步推进，扫大文件要迭代上百次，既慢又容易漏，
        // 是「QQ 视频尺寸读不出来」的直接原因 —— tkhd 距文件末尾 15KB，本该稳稳扫到。
        int span = (int) Math.min(end - start, TAIL_SCAN);
        if (span < 8) {
            return false;
        }
        byte[] buf = new byte[span];
        raf.seek(start);
        raf.readFully(buf);

        for (int i = 0; i + 4 <= span; i++) {
            if (buf[i] == 't' && buf[i + 1] == 'k' && buf[i + 2] == 'h' && buf[i + 3] == 'd') {
                // "tkhd" 标记之后就是 box 正文（version 起始）
                long bodyStart = start + i + 4;
                int[] wh = readTkhdSize(raf, bodyStart, end);
                if (wh != null) {
                    scan.size = wh;
                }
            }
        }
        return scan.size != null;
    }

    /**
     * 在 {@code [start, end)} 范围内递归查找 box，读出尺寸与编码。
     *
     * <p>MP4 是嵌套的 box 结构：{@code moov > trak > tkhd / mdia > minf > stbl > stsd}。
     *
     * <h2>为什么要按 trak 分组</h2>
     * 一个 MP4 通常有**两条轨**：视频轨和音频轨。实测 {@code output.mp4} 里
     * 视频轨的 {@code stsd} 是 {@code avc1}，音频轨是 {@code mp4a}。
     * 如果只是从上往下遍历、见到 {@code stsd} 就记，最后留下的是**后面的音频轨**，
     * 于是「这个视频的编码」被误报成 {@code mp4a}，兼容性判断直接错掉。
     *
     * <p>所以这里遇到 {@code trak} 就单独进去看：先读它的 {@code hdlr} 确认是视频轨，
     * 是的话才采信它的 {@code stsd}。
     */
    private static boolean findMoovInfo(RandomAccessFile raf, long start, long end, int depth, Scan scan)
            throws IOException {
        if (depth > 8) {
            return false;
        }
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = readU32(raf);
            byte[] typeBytes = new byte[4];
            raf.readFully(typeBytes);
            String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);

            long headerSize = 8;
            if (size == 1) {
                // 64 位长度
                size = raf.readLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;   // 一直到文件结尾
            }
            if (size < headerSize || pos + size > end) {
                return false;
            }

            long bodyStart = pos + headerSize;
            long bodyEnd = pos + size;

            if ("tkhd".equals(type)) {
                int[] wh = readTkhdSize(raf, bodyStart, bodyEnd);
                if (wh != null && wh[0] > 0 && wh[1] > 0) {
                    scan.size = wh;
                }
            }

            if ("trak".equals(type)) {
                // 先问这个 trak 是不是视频轨
                if (isVideoTrack(raf, bodyStart, bodyEnd, 0)) {
                    String tag = findStsdTag(raf, bodyStart, bodyEnd, 0);
                    if (tag != null) {
                        scan.codec = tag;
                    }
                }
                // trak 内部还要继续走一遍，把 tkhd 的尺寸捞出来
                findMoovInfo(raf, bodyStart, bodyEnd, depth + 1, scan);
            } else if ("moov".equals(type) || "mdia".equals(type)
                    || "minf".equals(type) || "stbl".equals(type)) {
                // 注意 stsd 不在其中 —— 它的正文前面多了 version+count，
                // 直接按 box 遍历会把 count 当成一个 box 的 size，立刻解析失败。
                findMoovInfo(raf, bodyStart, bodyEnd, depth + 1, scan);
            }

            pos = bodyEnd;
        }
        return scan.size != null;
    }

    /**
     * 判断一个 trak 里是不是视频轨。
     *
     * <p>看 {@code mdia > hdlr} 正文里的 handler type：
     * 前面是 version+flags(4) 和 pre_defined(4)，之后 4 字节才是 handler type
     * （{@code vide} = 视频，{@code soun} = 音频）。
     */
    private static boolean isVideoTrack(RandomAccessFile raf, long start, long end, int depth)
            throws IOException {
        if (depth > 6) {
            return false;
        }
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = readU32(raf);
            byte[] t = new byte[4];
            raf.readFully(t);
            String type = new String(t, java.nio.charset.StandardCharsets.US_ASCII);
            long headerSize = 8;
            if (size == 1) {
                size = raf.readLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerSize || pos + size > end) {
                return false;
            }
            long bodyStart = pos + headerSize;
            long bodyEnd = pos + size;

            if ("hdlr".equals(type)) {
                if (bodyStart + 12 > bodyEnd) {
                    return false;
                }
                raf.seek(bodyStart + 8);
                byte[] h = new byte[4];
                raf.readFully(h);
                return VIDEO_HANDLER.equals(new String(h, java.nio.charset.StandardCharsets.US_ASCII));
            }
            if ("mdia".equals(type)) {
                return isVideoTrack(raf, bodyStart, bodyEnd, depth + 1);
            }
            pos = bodyEnd;
        }
        return false;
    }

    /** 在视频轨内部找 {@code stsd} 并读出编码四字符码。 */
    private static String findStsdTag(RandomAccessFile raf, long start, long end, int depth)
            throws IOException {
        if (depth > 8) {
            return null;
        }
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = readU32(raf);
            byte[] t = new byte[4];
            raf.readFully(t);
            String type = new String(t, java.nio.charset.StandardCharsets.US_ASCII);
            long headerSize = 8;
            if (size == 1) {
                size = raf.readLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerSize || pos + size > end) {
                return null;
            }
            long bodyStart = pos + headerSize;
            long bodyEnd = pos + size;

            if ("stsd".equals(type)) {
                return readCodecTagNear(raf, bodyStart, bodyEnd);
            }
            if ("mdia".equals(type) || "minf".equals(type) || "stbl".equals(type)) {
                String r = findStsdTag(raf, bodyStart, bodyEnd, depth + 1);
                if (r != null) {
                    return r;
                }
            }
            pos = bodyEnd;
        }
        return null;
    }

    /**
     * 从 {@code stsd} 正文里读出编码四字符码。
     *
     * <p>{@code stsd} 结构（{@code start} 是 {@code "stsd"} 标记**之后**的位置，即正文起点）：
     * <pre>
     *   +0   version(1) + flags(3)
     *   +4   entry_count(4)
     *   +8   第一个 entry 的 size(4)
     *   +12  第一个 entry 的 format(4)  ← 这里的四字符码就是编码标识
     * </pre>
     * 用真实文件核对过（{@code output.mp4} 的 {@code stsd} 正文）：
     * <pre>
     *   00 00 00 00 | 00 00 00 01 | 00 00 00 C7 | 61 76 63 31
     *   版本+flags  |  entry数=1  |  entry长=199| a  v  c  1
     * </pre>
     * 所以偏移是 **+12**，不是 +8（+8 会读到 entry 长度那几个字节）。
     *
     * <p>四字符码取 {@code avc1}(H.264) / {@code hvc1}(H.265) / {@code av01}(AV1) 等。
     * 只取第一个 entry —— 视频轨固定只有一个。
     */
    private static String readCodecTagNear(RandomAccessFile raf, long start, long end) {
        try {
            if (start + 16 > end) {
                return null;
            }
            raf.seek(start + 12);
            byte[] tag = new byte[4];
            raf.readFully(tag);
            for (byte b : tag) {
                // 必须是可见 ASCII，否则说明位置不对
                if (b < 0x20 || b > 0x7E) {
                    return null;
                }
            }
            return new String(tag, java.nio.charset.StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 tkhd box 的正文里读出宽高。
     *
     * <p>布局（version 0 时）：
     * <pre>
     *   1  version + 3 flags
     *   4  creation_time
     *   4  modification_time
     *   4  track_ID
     *   4  reserved
     *   4  duration
     *   8  reserved
     *   2  layer
     *   2  alternate_group
     *   2  volume
     *   2  reserved
     *   36 matrix
     *   4  width   (16.16 定点)
     *   4  height  (16.16 定点)
     * </pre>
     * version 1 时 creation/modification/duration 各是 8 字节。
     */
    private static int[] readTkhdSize(RandomAccessFile raf, long start, long end) throws IOException {
        raf.seek(start);
        int version = raf.readUnsignedByte();
        raf.skipBytes(3);   // flags

        long p = start + 4;
        if (version == 1) {
            p += 8 + 8 + 4 + 4 + 8 + 8;   // 64 位时间戳那一套
        } else {
            p += 4 + 4 + 4 + 4 + 4 + 8;
        }
        p += 2 + 2 + 2 + 2 + 36;   // layer..matrix

        if (p + 8 > end) {
            return null;
        }
        raf.seek(p);
        long w = readU32(raf);
        long h = readU32(raf);
        // 16.16 定点数，右移 16 位取整数部分
        int width = (int) (w >> 16);
        int height = (int) (h >> 16);
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            return null;
        }
        return new int[]{width, height};
    }

    private static long readU32(RandomAccessFile raf) throws IOException {
        return ((long) raf.readUnsignedByte() << 24)
                | ((long) raf.readUnsignedByte() << 16)
                | ((long) raf.readUnsignedByte() << 8)
                | raf.readUnsignedByte();
    }
}
