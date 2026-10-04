package dev.customsplash.media;

/**
 * 一个视频的「体检报告」。
 *
 * <h2>为什么要做这个</h2>
 * jcodec 是纯 Java 的软解，对视频的要求和 FFmpeg 不一样，
 * 但它**经常不抛异常**：解不出来就给你一帧全黑，什么都不说。
 * 玩家看到黑屏，只能在「转码 → 重启 → 还是黑 → 再转码」里循环。
 *
 * <p>所以这里把「这个视频到底行不行、不行是为什么、怎么修」整理成一份报告，
 * 让它统一给界面（选文件列表、预览页、{@code /customsplash info}）和日志用。
 *
 * <h2>关于判据的说明（重要）</h2>
 * 报告里的「流畅度」结论**不是猜的**，是从真实解码耗时算出来的：
 * 解码线程会实测每帧耗时，拿它和视频自己的帧间隔比较。
 * 只要实测能跟上，就**不会**报任何告警 —— 这是为了不再出现「明明能播却一直警告」的误报。
 *
 * @param fileName     文件名
 * @param width        实际解码尺寸
 * @param height       实际解码尺寸
 * @param sourceWidth  视频流里记录的真实宽度，读不到为 -1
 * @param sourceHeight 视频流里记录的真实高度，读不到为 -1
 * @param fps          视频帧率，读不到为 -1
 * @param decoded      累计成功解码的帧数（用于算掉帧比例）
 * @param dropped      累计被丢弃的帧数（解码跟不上时跳过的那部分）
 * @param shared       是否与其他界面共用了同一个解码器
 * @param level        总体结论
 * @param headline     一句话结论（给界面大字显示）
 * @param detail       详细说明，可为 null
 * @param advice       修复建议，可为 null
 * @param ffmpegArgs   可直接照抄的 FFmpeg 参数，可为 null
 */
public record VideoReport(String fileName,
                          int width, int height,
                          int sourceWidth, int sourceHeight,
                          double fps,
                          int decoded, int dropped, boolean shared,
                          Level level,
                          String headline,
                          String detail,
                          String advice,
                          String ffmpegArgs) {

    /** 兼容性等级。 */
    public enum Level {
        /** 能正常播放。 */
        OK,
        /** 能播，但可能不够流畅。 */
        SLOW,
        /** 解不出来（黑屏）。 */
        BROKEN
    }

    public boolean isBroken() {
        return level == Level.BROKEN;
    }

    /** 能否播放（含勉强能播）。 */
    public boolean isPlayable() {
        return level != Level.BROKEN;
    }

    /**
     * 掉帧率（0.0 ~ 1.0）；总样本太少时返回 -1 表示「还看不出来」。
     *
     * <p>这个数字比「解码耗时」更直观：它就是实际播放时被跳过的帧的比例。
     * 玩家看到的「一卡一卡」就是这个数字的直接体现。
     */
    public double dropRate() {
        int total = decoded + dropped;
        if (total < 60) {
            return -1;
        }
        return dropped / (double) total;
    }

    /** 生成一份「推荐转码命令」，玩家可以直接照着敲。 */
    public String fullCommand() {
        if (ffmpegArgs == null) {
            return null;
        }
        return "ffmpeg -i \"" + fileName + "\" " + ffmpegArgs + " \"修复_" + fileName + "\"";
    }

    // ------------------------------------------------------------------
    //  工厂：造一份「没问题」的报告，省得到处 new
    // ------------------------------------------------------------------

    public static VideoReport ok(String fileName, int w, int h, int sw, int sh, double fps,
                                 int decoded, int dropped, boolean shared) {
        return new VideoReport(fileName, w, h, sw, sh, fps, decoded, dropped, shared,
                Level.OK, "兼容性正常", null, null, null);
    }

    public static VideoReport broken(String fileName, int w, int h, int sw, int sh, double fps,
                                     String headline, String detail, String advice, String ffmpegArgs) {
        return new VideoReport(fileName, w, h, sw, sh, fps, 0, 0, false,
                Level.BROKEN, headline, detail, advice, ffmpegArgs);
    }

    public static VideoReport slow(String fileName, int w, int h, int sw, int sh, double fps,
                                   int decoded, int dropped, boolean shared,
                                   String detail, String advice, String ffmpegArgs) {
        return new VideoReport(fileName, w, h, sw, sh, fps, decoded, dropped, shared,
                Level.SLOW, "解码跟不上，画面可能掉帧", detail, advice, ffmpegArgs);
    }
}
