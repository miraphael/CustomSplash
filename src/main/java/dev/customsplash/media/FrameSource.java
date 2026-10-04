package dev.customsplash.media;

/**
 * 一帧序列的来源：静态图片、GIF 动图、或 MP4 视频。
 *
 * <p>所有实现都按「顺序播放」的方式工作：调用 {@link #nextFrame()} 取得下一帧的
 * ARGB 像素数组（{@code 0xAARRGGBB}，长度 = {@code width() * height()}），
 * 播放到结尾后自动回到第一帧循环。
 */
public interface FrameSource extends AutoCloseable {

    int width();

    int height();

    /** 总帧数；未知时返回 -1。 */
    int frameCount();

    /** 每帧应显示的毫秒数。 */
    int frameDelayMs();

    /** 取得下一帧。返回的数组长度固定，实现方可以复用同一个数组。 */
    int[] nextFrame();

    /** 是否是动画（帧数 > 1）。 */
    default boolean isAnimated() {
        return frameCount() != 1;
    }

    @Override
    void close();
}
